import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mushussubabylon.gitdropuploader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mushussubabylon.gitdropuploader"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.0"
    }

    // Release signing details are kept OUT of version control.
    // Copy keystore.properties.example to keystore.properties and fill it in.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !keystoreProps.getProperty(it).isNullOrBlank() }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the code and resources: smaller APK, faster install and start-up.
            // The app uses no reflection, so no extra keep rules are needed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without keystore.properties the release build is signed with the
            // debug key so the APK is still installable. Add keystore.properties
            // before publishing a build to other people.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
    // Only for the unit tests under app/src/test (run with: gradle testDebugUnitTest).
    // The app itself still has no dependencies at all.
    testImplementation("junit:junit:4.13.2")
}
