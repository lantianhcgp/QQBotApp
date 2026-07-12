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
        versionCode = 6
        versionName = "3.3.0"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
