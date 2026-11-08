import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName: String = System.getenv("VERSION_NAME") ?: "1.0.0"

// Read the optional key as data, then quote it explicitly for Java BuildConfig.
// The Secrets plugin emits raw Java expressions, so an empty .env value would
// otherwise generate the invalid expression: OPENSUBTITLES_API_KEY = ;
val openSubtitlesApiKey: String =
  providers.environmentVariable("OPENSUBTITLES_API_KEY").orNull
    ?: rootProject.file(".env").takeIf { it.isFile }?.let { envFile ->
      Properties().apply { envFile.inputStream().use { load(it) } }
        .getProperty("OPENSUBTITLES_API_KEY")
    }.orEmpty()

val escapedOpenSubtitlesApiKey = openSubtitlesApiKey
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.sonixtv.wzkmq"
    minSdk = 24
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName

    buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$escapedOpenSubtitlesApiKey\"")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters.clear()
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
  }

  packaging {
    jniLibs {
      excludes += listOf(
        "lib/x86/**",
        "lib/x86_64/**"
      )
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for building,
      // installing, and updating Android apps in AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for building,
      // installing, and updating Android apps in AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  // This key is added above with explicit Java-string escaping instead.
  ignoreList.add("OPENSUBTITLES_API_KEY")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  // Android WorkManager: periodic, network-constrained catalog refresh while the app is closed.
  implementation("androidx.work:work-runtime-ktx:2.10.1")
  // AndroidX Media3 ExoPlayer engine from Lumen-App for IPTV live streaming, HLS, DASH, VOD and UI
  implementation("androidx.media3:media3-exoplayer:1.10.0")
  implementation("androidx.media3:media3-exoplayer-hls:1.10.0")
  implementation("androidx.media3:media3-exoplayer-dash:1.10.0")
  implementation("androidx.media3:media3-ui:1.10.0")

  // libmpv backup video engine for wide codec support (ARM64, ARMv7, x86_64, Android TV)
  implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")

  // Core AndroidX and Jetpack Compose libraries
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Unit and JVM tests
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  // Instrumented Android tests
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  // Debug tools & Firebase App Check Debug for debug builds only
  debugImplementation(platform(libs.firebase.bom))
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.firebase.appcheck.debug)
}
