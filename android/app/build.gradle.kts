plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "app.scamshield"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.scamshield"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-ai:18.2.0")
    // On-device Gemini Nano via ML Kit Prompt API / AICore; not in the BoM yet.
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08")
    // shortcut: debug App Check provider in every build (allowed for #BuildHybridAI); switch release to Play Integrity before shipping.
    implementation("com.google.firebase:firebase-appcheck-debug:20.1.0")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-config")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    ksp("com.google.firebase:firebase-ai-ksp-processor:17.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    // Hybrid / on-device inference is an experimental Firebase AI Logic preview.
    compilerOptions.optIn.addAll("com.google.firebase.ai.type.PublicPreviewAPI", "androidx.compose.foundation.layout.ExperimentalLayoutApi")
}
