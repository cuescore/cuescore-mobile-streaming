import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Git hash in versionName identifies the build installed on the device
val gitHash: String =
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "nogit" }

// Release signing. The password file lives OUTSIDE the repository and is never
// committed. BRIX_KEYSTORE_PROPERTIES sets its path; by default, look for
// keystore.properties beside the project.
//
// Without it, releases use the debug key, deliberately allowing contributors
// and CI to build without secrets. Such APKs are suitable for testing,
// not distribution.
val keystoreProperties =
    Properties().apply {
        val f =
            rootProject.file(
                System.getenv("BRIX_KEYSTORE_PROPERTIES") ?: "keystore.properties",
            )
        if (f.exists()) f.inputStream().use { load(it) }
    }

android {
    namespace = "app.brix"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.brix"
        minSdk = 29
        targetSdk = 36
        // Human-readable version without a hash: shown to users, app catalogs
        // and the release page. Add the commit hash only to debug builds
        // (see buildTypes below), where the HUD needs to identify exactly
        // what is installed on the phone.
        //
        // Put beta in the version itself, not just the description, so it
        // appears in app catalogs and the streaming HUD. Users should know
        // what they installed without reading the project page.
        // Untested features are listed in README.
        //
        // versionCode must strictly increase with each release, otherwise
        // existing installations will not receive the update.
        versionCode = 10
        versionName = "0.2.1-beta"
    }

    // Restricts which locale-qualified resources actually get packaged into
    // the APK — without this, context.assets.locales() (used by
    // LanguageScreen to build the language picker) returns every locale any
    // dependency ships (AndroidX alone carries ~80), not just the ones BRIX
    // itself supports.
    androidResources {
        localeFilters += listOf("en")
    }

    buildFeatures {
        compose = true
    }

    // APK dependency metadata is unnecessary and harmful. AGP embeds an encrypted
    // dependency list for Google Play. Its contents cannot be inspected, so
    // F-Droid's scanner flags it and rejects the build (15.09, check apk task).
    // We do not use Play at all.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["storeAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Commit hash only in debug builds: visible in the HUD to identify
            // the installed build after a field outing. Releases should have
            // no such suffix; their version is human-readable.
            versionNameSuffix = "-$gitHash"
        }
        release {
            // Do not embed a commit reference in the APK. AGP writes the source
            // tree's HEAD hash into META-INF/version-control-info.textproto.
            // Our private and public trees necessarily have different hashes,
            // so F-Droid's reproducible build differed on this file on its
            // first run (15.09). The file adds no value: the HUD already
            // shows the version and debug build hash.
            vcsInfo { include = false }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Release suitable for the test bench (bench/). Tests previously used
        // only debug, while users get R8 and resource shrinking. If R8 removes
        // something needed by kotlinx serialization or reflection, users would
        // discover it first (analysis 03.10). Use the same R8 rules here, but
        // debuggable (the bench needs run-as) and debug-signed, allowing
        // installation over debug without uninstalling. Never distribute
        // this build to users.
        create("bench") {
            initWith(getByName("release"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-bench-$gitHash"
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":streaming"))
    implementation(project(":bonding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
