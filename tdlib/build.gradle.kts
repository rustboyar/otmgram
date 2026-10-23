// TDLib's generated Java API (TdApi, Client) and prebuilt libtdjni.so.
// Both are produced by scripts/build-tdlib.sh and are not committed.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val tdlibOutputs = listOf(
    "src/main/jniLibs/arm64-v8a/libtdjni.so",
    "src/main/java/org/drinkless/tdlib/TdApi.java",
)
tasks.named("preBuild") {
    val outputs = tdlibOutputs.map(::file)
    doFirst {
        val missing = outputs.filterNot { it.exists() }
        check(missing.isEmpty()) { "TDLib is not built, run scripts/build-tdlib.sh (missing: $missing)" }
    }
}
