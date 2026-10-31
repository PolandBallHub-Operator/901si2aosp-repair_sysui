package jp.co.softbank.kidsphone.restore;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

/** Android 8.1 / LibXposed API 100 runtime hooks for the SoftBank 901SI. */
public final class MainModule extends XposedModule {
    private static final String TAG = "901SI-UI-Restore";
    private static final int DISABLE_EXPAND = 0x00010000;
    private static final String OEM_VOLUME_ACTION = "com.android.showui";
    private static final String OEM_POWER_ACTION =
            "com.example.broadcasttest.KEYCODE_POWER_DOWN_TWO_S";

    // Resource IDs from this device's framework-res.apk public.xml (API 27 build).
    private static final int BOOL_CONFIG_SHOW_NAVIGATION_BAR = 0x01120093;
    private static final int DIMEN_NAVIGATION_BAR_HEIGHT = 0x010500f5;
    private static final int DIMEN_NAVIGATION_BAR_HEIGHT_LANDSCAPE = 0x010500f7;
    private static final int DIMEN_NAVIGATION_BAR_HEIGHT_PORTRAIT = 0x010500f9;
    private static final int DIMEN_NAVIGATION_BAR_WIDTH = 0x010500fa;
    private static final float NAVIGATION_BAR_DP = 48.0f;

    private static final ThreadLocal<LegacyRouteState> LEGACY_ROUTE_STATE = new ThreadLocal<>();
    private static final AtomicBoolean QS_GATE_LOGGED = new AtomicBoolean(false);

    public MainModule(@NonNull XposedInterface base,
                      @NonNull XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onSystemServerLoaded(@NonNull XposedModuleInterface.SystemServerLoadedParam param) {
        ClassLoader loader = param.getClassLoader();
        hookNavigationResources(loader, "system_server");
        hookHasNavigationBar(loader);
        hookLegacyGlobalActionsRoute(loader);
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) return;
        ClassLoader loader = param.getClassLoader();
        hookNavigationResources(loader, "SystemUI");
        hookStatusBarDisable(loader);
        hookBarModeSentinel(loader);
        hookQuickSettingsPanelGate(loader);
        hookSuppressKidsPowerMenuBroadcast(loader);
        hookSuppressOemVolumeBroadcast(loader);
    }

    private void hookNavigationResources(ClassLoader loader, String process) {
        try {
            Class<?> resources = Class.forName("android.content.res.Resources", false, loader);
            hook(resources.getDeclaredMethod("getDimensionPixelSize", int.class), NavDimensionHooker.class);
            hook(resources.getDeclaredMethod("getDimensionPixelOffset", int.class), NavDimensionHooker.class);
            hook(resources.getDeclaredMethod("getDimension", int.class), NavDimensionHooker.class);
            hook(resources.getDeclaredMethod("getBoolean", int.class), ShowNavigationBarHooker.class);
            Log.i(TAG, "Navbar resource hooks installed in " + process + " (48dp)");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to install navbar resource hooks in " + process, t);
        }
    }

    private void hookHasNavigationBar(ClassLoader loader) {
        try {
            Class<?> pwm = Class.forName("com.android.server.policy.PhoneWindowManager", false, loader);
            hook(pwm.getDeclaredMethod("hasNavigationBar"), HasNavigationBarHooker.class);
            Log.i(TAG, "PhoneWindowManager.hasNavigationBar will return true");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook PhoneWindowManager.hasNavigationBar", t);
        }
    }

    private void hookStatusBarDisable(ClassLoader loader) {
        try {
            Class<?> statusBar = Class.forName("android.app.StatusBarManager", false, loader);
            hook(statusBar.getDeclaredMethod("disable", int.class), DisableExpandHooker.class);
            Log.i(TAG, "StatusBarManager.disable hook installed; DISABLE_EXPAND is cleared");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook StatusBarManager.disable", t);
        }
    }

    private void hookBarModeSentinel(ClassLoader loader) {
        try {
            Class<?> barTransitions = Class.forName(
                    "com.android.systemui.statusbar.phone.BarTransitions", false, loader);
            Method transitionTo = barTransitions.getDeclaredMethod("transitionTo", int.class, boolean.class);
            hook(transitionTo, IgnoreUnchangedBarModeHooker.class);
            Log.i(TAG, "BarTransitions -1/no-change sentinel guard installed");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to install BarTransitions sentinel guard", t);
        }
    }

    private void hookQuickSettingsPanelGate(ClassLoader loader) {
        try {
            Class<?> statusBar = Class.forName(
                    "com.android.systemui.statusbar.phone.StatusBar", false, loader);
            hook(statusBar.getDeclaredMethod("panelsEnabled"), EnableQuickSettingsHooker.class);
            Log.i(TAG, "StatusBar.panelsEnabled hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook StatusBar.panelsEnabled", t);
        }
        try {
            Class<?> phoneStatusBarView = Class.forName(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView", false, loader);
            hook(phoneStatusBarView.getDeclaredMethod("panelEnabled"), EnableQuickSettingsHooker.class);
            Log.i(TAG, "PhoneStatusBarView.panelEnabled hook installed (direct PanelBar touch gate)");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook PhoneStatusBarView.panelEnabled", t);
        }
        try {
            Class<?> panelBar = Class.forName(
                    "com.android.systemui.statusbar.phone.PanelBar", false, loader);
            hook(panelBar.getDeclaredMethod("panelEnabled"), EnableQuickSettingsHooker.class);
            Log.i(TAG, "PanelBar.panelEnabled fallback hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook PanelBar.panelEnabled", t);
        }
    }

    /**
     * Android 8.1's PhoneWindowManager already reaches
     * PhoneWindowManager.showGlobalActionsInternal -> GlobalActions.showDialog.
     * GlobalActions normally delegates to StatusBarManagerInternal when SystemUI is
     * connected. Temporarily make that existing wrapper take its built-in
     * LegacyGlobalActions.showDialog path, then restore the connection flag.
     */
    private void hookLegacyGlobalActionsRoute(ClassLoader loader) {
        try {
            Class<?> globalActions = Class.forName(
                    "com.android.server.policy.GlobalActions", false, loader);
            hook(globalActions.getDeclaredMethod("showDialog", boolean.class, boolean.class),
                    ForceLegacyGlobalActionsHooker.class);
            Log.i(TAG, "GlobalActions.showDialog routed through built-in LegacyGlobalActions");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to route power menu to LegacyGlobalActions", t);
        }
    }

    private void hookSuppressKidsPowerMenuBroadcast(ClassLoader loader) {
        try {
            Class<?> receiver = Class.forName(
                    "com.android.systemui.globalactions.GlobalActionsDialog$1", false, loader);
            hook(receiver.getDeclaredMethod("onReceive", Context.class, Intent.class),
                    SuppressKidsPowerMenuBroadcastHooker.class);
            Log.i(TAG, "KidsUI power-action receiver suppressed; system_server legacy route retained");
        } catch (Throwable t) {
            Log.e(TAG, "Unable to suppress KidsUI power-action receiver", t);
        }
    }

    private void hookSuppressOemVolumeBroadcast(ClassLoader loader) {
        int installed = 0;
        try {
            Class<?> wrapper = Class.forName("android.content.ContextWrapper", false, loader);
            hook(wrapper.getDeclaredMethod("sendBroadcast", Intent.class),
                    SuppressOemVolumeBroadcastHooker.class);
            installed++;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook ContextWrapper.sendBroadcast", t);
        }
        try {
            // ContextImpl is hidden from the compile SDK but exists on Android 8.1.
            Class<?> impl = Class.forName("android.app.ContextImpl", false, loader);
            hook(impl.getDeclaredMethod("sendBroadcast", Intent.class),
                    SuppressOemVolumeBroadcastHooker.class);
            installed++;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to hook ContextImpl.sendBroadcast", t);
        }
        if (installed > 0) {
            Log.i(TAG, "OEM com.android.showui sends will be dropped in SystemUI; volume controller remains active");
        } else {
            Log.e(TAG, "No SystemUI sendBroadcast implementation could be hooked");
        }
    }

    private static boolean isNavigationDimension(int id) {
        return id == DIMEN_NAVIGATION_BAR_HEIGHT
                || id == DIMEN_NAVIGATION_BAR_HEIGHT_LANDSCAPE
                || id == DIMEN_NAVIGATION_BAR_HEIGHT_PORTRAIT
                || id == DIMEN_NAVIGATION_BAR_WIDTH;
    }

    @XposedHooker
    public static final class NavDimensionHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (!(callback.getThisObject() instanceof Resources)
                    || args.length == 0 || !(args[0] instanceof Integer)
                    || !isNavigationDimension((Integer) args[0])) return;

            Resources resources = (Resources) callback.getThisObject();
            float pixels = NAVIGATION_BAR_DP * resources.getDisplayMetrics().density;
            Member member = callback.getMember();
            if (member instanceof Method && ((Method) member).getReturnType() == float.class) {
                callback.returnAndSkip(pixels);
            } else {
                callback.returnAndSkip(Math.max(1, Math.round(pixels)));
            }
        }
    }

    @XposedHooker
    public static final class ShowNavigationBarHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (args.length > 0 && args[0] instanceof Integer
                    && ((Integer) args[0]) == BOOL_CONFIG_SHOW_NAVIGATION_BAR) {
                callback.returnAndSkip(true);
            }
        }
    }

    @XposedHooker
    public static final class HasNavigationBarHooker implements XposedInterface.Hooker {
        @AfterInvocation
        public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
            callback.setResult(true);
        }
    }

    @XposedHooker
    public static final class IgnoreUnchangedBarModeHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (args.length > 0 && args[0] instanceof Integer && ((Integer) args[0]) == -1) {
                // The OEM implementation treats AOSP's no-change sentinel as an invalid mode.
                callback.returnAndSkip(null);
            }
        }
    }

    @XposedHooker
    public static final class DisableExpandHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (args.length > 0 && args[0] instanceof Integer) {
                args[0] = ((Integer) args[0]) & ~DISABLE_EXPAND;
            }
        }
    }

    @XposedHooker
    public static final class EnableQuickSettingsHooker implements XposedInterface.Hooker {
        @AfterInvocation
        public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
            callback.setResult(true);
            if (QS_GATE_LOGGED.compareAndSet(false, true)) {
                Log.i(TAG, "QS panel gate invoked and forced true: " + callback.getMember());
            }
        }
    }

    @XposedHooker
    public static final class ForceLegacyGlobalActionsHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object target = callback.getThisObject();
            try {
                Field connected = target.getClass().getDeclaredField("mStatusBarConnected");
                connected.setAccessible(true);
                boolean previous = connected.getBoolean(target);
                LEGACY_ROUTE_STATE.set(new LegacyRouteState(target, connected, previous));
                if (previous) connected.setBoolean(target, false);
            } catch (Throwable t) {
                LEGACY_ROUTE_STATE.remove();
                Log.e(TAG, "Could not select LegacyGlobalActions branch; allowing original route", t);
            }
        }

        @AfterInvocation
        public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
            LegacyRouteState state = LEGACY_ROUTE_STATE.get();
            LEGACY_ROUTE_STATE.remove();
            if (state == null || state.target != callback.getThisObject()) return;
            try {
                state.connectedField.setBoolean(state.target, state.wasConnected);
                Log.i(TAG, "AOSP LegacyGlobalActions.showDialog completed; statusbar state restored");
            } catch (Throwable t) {
                Log.e(TAG, "Could not restore GlobalActions statusbar state", t);
            }
        }
    }

    private static final class LegacyRouteState {
        final Object target;
        final Field connectedField;
        final boolean wasConnected;

        LegacyRouteState(Object target, Field connectedField, boolean wasConnected) {
            this.target = target;
            this.connectedField = connectedField;
            this.wasConnected = wasConnected;
        }
    }

    @XposedHooker
    public static final class SuppressKidsPowerMenuBroadcastHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (args.length < 2 || !(args[1] instanceof Intent)) return;
            Intent intent = (Intent) args[1];
            if (OEM_POWER_ACTION.equals(intent.getAction())) {
                Log.i(TAG, "Dropped OEM KidsUI power-menu receiver action");
                callback.returnAndSkip(null);
            }
        }
    }

    @XposedHooker
    public static final class SuppressOemVolumeBroadcastHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (args.length == 0 || !(args[0] instanceof Intent)) return;
            Intent intent = (Intent) args[0];
            if (OEM_VOLUME_ACTION.equals(intent.getAction())) {
                Log.i(TAG, "Dropped OEM com.android.showui volume side-channel");
                callback.returnAndSkip(null);
            }
        }
    }
}
