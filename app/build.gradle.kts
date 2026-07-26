plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.obsidianwear.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.obsidianwear.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SERVER_URL", "\"http://192.168.1.170:5001\"")
        buildConfigField("String", "SERVER_URL_REMOTE", "\"http://100.83.52.91:5001\"")
        // Klucz API; w CI przez env, lokalnie fallback pustego (serwer bez auth)
        buildConfigField(
            "String",
            "VOICE_API_KEY",
            "\"${System.getenv("VOICE_API_KEY") ?: ""}\""
        )
        buildConfigField("String", "WHISPER_KEY", "\"***\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
