plugins {
    id("com.android.application")
}

android {
    namespace = "jp.co.softbank.kidsphone.restore"
    compileSdk = 23

    defaultConfig {
        applicationId = "jp.co.softbank.kidsphone.restore"
        minSdk = 23
        targetSdk = 23
        versionCode = 5
        versionName = "1.4.0-api100-qsgate"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(files("libs/libxposed-api-100.jar"))
    compileOnly("androidx.annotation:annotation:1.5.0")
}
