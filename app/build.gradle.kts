import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing material stays out of version control. Supply it either in
// keystore.properties at the repo root (gitignored) or via AERODESK_* environment
// variables. Without it the release build simply stays unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val storeFilePath = signingValue("storeFile", "AERODESK_KEYSTORE")
val hasReleaseSigning = storeFilePath != null && rootProject.file(storeFilePath).exists()

// Debug builds sign with keystore/debug.keystore (gitignored) when it is there: the same key on
// every machine, so a debug build from another Mac installs over the one already on the headset.
// Without it, the machine's own ~/.android/debug.keystore, as usual.
val sharedDebugKeystore = rootProject.file("keystore/debug.keystore")

android {
    namespace = "com.therealityteam.aeropages"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.therealityteam.aeropages"
        minSdk = 35
        targetSdk = 35
        versionCode = 9
        versionName = "1.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
    }

    signingConfigs {
        if (sharedDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = sharedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = signingValue("storePassword", "AERODESK_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "AERODESK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "AERODESK_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        // Pseudo-locales in debug builds (en-XA: longer accented strings; ar-XB is not used — no right-to-left
        // languages are shipped) to find labels that do not fit before real translations do.
        debug { isPseudoLocalesEnabled = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // Only the 18 languages the app is translated into (res/xml/locales_config.xml), and the debug pseudo-locale:
    // libraries' strings in other languages would otherwise show up half-translated and weigh on the APK.
    androidResources {
        localeFilters += listOf(
            "en", "zh", "zh-rTW", "ja", "ko", "de", "fr", "es", "it", "pt-rBR", "ru", "nl", "pl", "tr", "vi", "th", "in", "hi", "en-rXA",
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gates the adb `debug.aero.*` developer hooks
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        lintConfig = file("lint.xml")
        // Deliberate, documented decisions rather than oversights:
        disable += setOf(
            "ScopedStorage",           // the built-in file browser needs All files access; SAF is the fallback (README)
            "CustomSplashScreen",      // the launch activity must be the Spatial SDK's SpatialLaunchActivity
            "TypographyFractions",     // "Heading 1/2/3" is a list of style names, not a fraction
            "OldTargetApi",            // targetSdk is pinned to the PICO OS 6 platform (35)
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "UseTomlInstead",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.bom))
    implementation(libs.core)
    implementation(libs.platform)
    implementation(libs.foundation)
    implementation(libs.design)
    implementation(libs.sense)
    implementation(libs.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    // Real XmlPullParser implementation for JVM unit tests (android.jar stubs throw).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Compose UI tests (androidTest/DeskFlowsTest): same Compose line as the SpatialUI fork (1.10.4).
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.test.manifest)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
// Unit tests dry-run every scoped undo delta against a full snapshot (see editor/UndoHistory.kt).
tasks.withType<Test>().configureEach {
    systemProperty("aero.verifyUndo", "true")
    // -Daero.<name>=… reaches the tests: sampleDir (SampleFileMakerTest writes the device smoke-test
    // documents there), crossAudit (RoundTripAuditTest's cross-format pass), codesDir (NumberStylesTest).
    System.getProperties().stringPropertyNames().filter { it.startsWith("aero.") }.forEach { systemProperty(it, System.getProperty(it)) }
}
