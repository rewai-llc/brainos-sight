plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The Bluetooth camera link shared by BrainOS and BrainOS Sight: the BrainOS Cam protocol (with pairing),
// the E12 headset protocol, and the E12's Wi-Fi photo download. One copy, used by both apps.
android {
    namespace = "com.brainos.camlink"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
