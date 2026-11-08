plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "vn.uneti.ai05"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "vn.uneti.ai05"
        buildConfigField("String", "COMMUNITY_URL", "\"https://community.trkhoang.dev\"")
        buildConfigField("String", "EXTERNAL_EVIDENCE_SHA256", "\"${file("src/main/external_evidence.sha256").readText().trim()}\"")
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MODEL_SHA256",
            "\"${file("src/main/model.sha256").readText().trim()}\"",
        )
        buildConfigField(
            "String",
            "EVIDENCE_SHA256",
            "\"${file("src/main/evidence.sha256").readText().trim()}\"",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.klinkerapps:android-smsmms:5.2.6") { isTransitive = false }
    implementation("com.klinkerapps:logger:1.0.3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
