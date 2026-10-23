import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Telegram API credentials from my.telegram.org, kept out of git:
//   telegram.apiId=12345
//   telegram.apiHash=0123456789abcdef0123456789abcdef
// telegram.testDc=true makes debug builds use Telegram's test servers. Their test
// accounts (99966XYYYY numbers) no longer work since 2025, so production is the default.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val telegramApiId = localProperties.getProperty("telegram.apiId") ?: "0"
val telegramApiHash = localProperties.getProperty("telegram.apiHash") ?: ""
val telegramTestDc = localProperties.getProperty("telegram.testDc")?.toBoolean() ?: false

android {
    namespace = "dev.otmgram"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.otmgram"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    buildTypes {
        debug {
            // x86_64 for the emulator.
            ndk { abiFilters += "x86_64" }
            buildConfigField("boolean", "TELEGRAM_TEST_DC", "$telegramTestDc")
        }
        release {
            buildConfigField("boolean", "TELEGRAM_TEST_DC", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: sign release with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Unit tests run on the JVM: Android calls like Log.w do nothing instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":tdlib"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.maplibre)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.lottie.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // UI tests on a device: run scripts/ui-tests.sh, which keeps the app's data
    // (Gradle's connectedAndroidTest uninstalls the app afterwards, logging it out).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Compose testing brings Espresso 3.5, which can't inject input on Android 16+.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
}
