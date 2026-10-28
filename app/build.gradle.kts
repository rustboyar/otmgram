import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

// Release signing key, outside the repo (see local.properties). Keep using the same key:
// Android only installs updates signed like the installed app.
val releaseStoreFile = localProperties.getProperty("release.storeFile")?.let(::file)?.takeIf { it.exists() }

// What a release build can't do without; checked before building one, so a missing key
// can't end up as an APK signed with another one (or credentials as a login that fails).
val missingForRelease = buildList {
    if (telegramApiId == "0") add("telegram.apiId")
    if (telegramApiHash.isEmpty()) add("telegram.apiHash")
    if (releaseStoreFile == null) add("release.storeFile (missing, or the file isn't there)")
    listOf("release.storePassword", "release.keyAlias", "release.keyPassword").forEach {
        if (localProperties.getProperty(it).isNullOrEmpty()) add(it)
    }
}

// The version is set in gradle.properties (by scripts/release.sh). The version code follows
// from it, MAJOR * 10000 + MINOR * 100 + PATCH, so it always grows with the version, as
// Android needs to install an update: 0.2.0 is 200, 1.4.12 is 10412.
val appVersion = providers.gradleProperty("otmgram.version").get()
val appVersionCode = run {
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)?.destructured?.toList()?.map(String::toInt)
    require(parts != null && parts[1] < 100 && parts[2] < 100) { "otmgram.version must be MAJOR.MINOR.PATCH, minor and patch below 100: $appVersion" }
    parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "dev.otmgram"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.otmgram"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
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
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    lint {
        // Not a ChromeOS app: x86_64 is only built for the emulator.
        disable += "ChromeOsAbiSupport"
        // A newer release is news, not an error: one out today would fail the build. Updates
        // wait until a release is a couple of weeks old.
        informational += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        abortOnError = true
        warningsAsErrors = true
    }

    // Unit tests run on the JVM: Android calls like Log.w do nothing instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val checkReleaseSetup = tasks.register("checkReleaseSetup") {
    val missing = missingForRelease
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException("A release build needs these in local.properties: ${missing.joinToString()}")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSetup) }

kotlin {
    compilerOptions {
        // Warnings are fixed, not collected: deprecations and needless code, as lint's are.
        allWarningsAsErrors = true
    }
}

dependencies {
    // Compose's own pitfalls (state, modifiers, effects), checked with lint.
    lintChecks(libs.compose.lint.checks)
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
