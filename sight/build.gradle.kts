plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// BrainOS Sight: reads text and describes scenes for people who are blind or have low vision.
// Everything runs on the phone: ML Kit text recognition, Gemma 3n (via MediaPipe) for scenes,
// Android's on-device speech recognition and text-to-speech. Photos never leave the phone.
android {
    namespace = "com.brainos.sight"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brainos.sight"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-sight"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device text recognition (Latin scripts), model bundled in the app: works offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // On-device LLM runtime for Gemma 3n (scene descriptions and questions about a photo).
    implementation("com.google.mediapipe:tasks-genai:0.10.+")
    // MediaPipe's image type (MPImage / BitmapImageBuilder), used to hand photos to Gemma.
    implementation("com.google.mediapipe:tasks-core:0.10.+")

    testImplementation("junit:junit:4.13.2")
}
