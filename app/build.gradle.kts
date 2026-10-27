plugins {
    id("com.android.application")
}

android {
    namespace = "com.ultradim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ultradim"
        minSdk = 34
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 零第三方依赖：以下 dependencies 块刻意留空
dependencies {
}
