plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {

    // SettingsStore.load() calls android.util.Log when restoring from .bak;
    // without this, that path's unit test would fail with "not mocked".
    // :streaming uses the same flag.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    namespace = "app.brix.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
