plugins {
    id("com.android.application")
}

android {
    namespace = "com.hper.qqbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hper.qqbot"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "qqbot123"
            keyAlias = "qqbot"
            keyPassword = "qqbot123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
}
