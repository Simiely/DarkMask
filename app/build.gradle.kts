plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.sim.darkmask"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sim.darkmask"
        minSdk = 23
        targetSdk = 35
        // 版本号仅在用户明确要求时手动更新，不再每次构建自增。
        versionCode = 6
        versionName = "1.6"

        // 仅保留中文资源，去掉 Material3 自带的 50+ 种语言翻译
        resourceConfigurations += setOf("zh")
    }

    // 固定 debug 签名：所有 CI 构建复用仓库内 keystore/debug.keystore，
    // 保证每次产物签名一致，可直接覆盖安装、无需卸载。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // 开启代码压缩(R8) + 资源裁剪，大幅缩小 APK
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
