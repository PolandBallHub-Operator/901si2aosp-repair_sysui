# 901SI UI Restore — Android 8.1 / LibXposed API 100

This LSPosed module preserves the already-working navigation-bar hooks and adds narrowly scoped UI routing for Quick Settings, the Oreo MR1 legacy power menu, and the volume dialog's OEM side-channel.

## Implemented hooks

- **Navigation bar:** Existing 48dp resource, `config_showNavigationBar`, `PhoneWindowManager.hasNavigationBar()`, and `BarTransitions` -1-sentinel hooks are retained unchanged.
- **Quick Settings:** `StatusBar.panelsEnabled()`, `PhoneStatusBarView.panelEnabled()`, and `PanelBar.panelEnabled()` are forced to `true`. The latter two are the direct `PanelBar.onTouchEvent()` gates implicated by the device log `all panels disabled`; the first remains as the underlying status-bar policy gate. The first executed panel-gate hook emits a one-time `QS panel gate invoked and forced true` log.
- **Power menu:** In `system_server`, the existing Android 8.1 `com.android.server.policy.GlobalActions.showDialog(boolean, boolean)` implementation runs with its `mStatusBarConnected` flag temporarily set false. The framework wrapper then executes its built-in `LegacyGlobalActions.showDialog(...)` branch. The previous connection flag is restored in the after-hook. In SystemUI, the receiver for OEM-only action `com.example.broadcasttest.KEYCODE_POWER_DOWN_TWO_S` is skipped so it cannot open the KidsUI global-actions path.
- **Volume:** In the SystemUI process, only `Intent` broadcasts with action `com.android.showui` are skipped at `ContextWrapper` / `ContextImpl.sendBroadcast(Intent)`. The `VolumeDialogImpl.showH()` method itself is not skipped; its regular AOSP dialog state, motion, controller notification, and timeout handling continue.

## Scope

`META-INF/xposed/scope.list` remains limited to:

- `android` (system_server)
- `com.android.systemui`

No third-party KidsUI package is disabled, and no Settings value is changed.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug --no-daemon
```

Install `app/build/outputs/apk/debug/app-debug.apk`, enable the module in LSPosed, verify the two scopes above, then reboot. Android 8.1/API 27 is the target runtime; LibXposed module API is 100.

## Verification after reboot

Capture `adb logcat -b all -v threadtime` after testing one feature at a time. Expected module tag: `901SI-UI-Restore`.

- QS: `PhoneStatusBarView.panelEnabled hook installed`, then on a swipe `QS panel gate invoked and forced true`; `PanelBar: all panels disabled` should stop appearing.
- Power: `GlobalActions.showDialog routed through built-in LegacyGlobalActions`, followed by `AOSP LegacyGlobalActions.showDialog completed` on a long press.
- KidsUI power receiver: `Dropped OEM KidsUI power-action receiver action`.
- Volume: `Dropped OEM com.android.showui volume side-channel`; the volume level and standard dialog must still respond.

If the direct panel hook logs that it was invoked but the panel still does not open, capture `dumpsys window windows` while swiping and the full SystemUI log around the gesture. That would indicate a later panel attachment/expansion gate rather than the `DISABLE_EXPAND` flags or `PanelBar.panelEnabled()` result.

If a hook installation message is absent or errors, retain the existing working Navbar rather than changing framework packages or disabling system apps. Use the module disable switch or remove its LSPosed scope to roll back, then reboot. Keep the prior APK and project source as a recovery copy.
