import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.floatingapplauncher.orbt"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${localProperties.getProperty("TELEGRAM_BOT_TOKEN", "")}\"")
    buildConfigField("String", "TELEGRAM_CHAT_ID", "\"${localProperties.getProperty("TELEGRAM_CHAT_ID", "")}\"")
  }

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
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
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
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation("androidx.cardview:cardview:1.0.0")
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.dynamicanimation)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // Tesseract4Android (JitPack) — on-device OCR with real Arabic support, unlike ML Kit's
  // text recognizer which has no Arabic model. Used by the Vault "Scan Screen" feature.
  implementation(libs.tesseract4android)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// --- Tesseract OCR trained-data provisioning -------------------------------------------------
//
// Downloads eng.traineddata and ara.traineddata (LSTM "fast" models, Tesseract 5.x compatible)
// from the official tesseract-ocr/tessdata_fast GitHub repository directly into
// app/src/main/assets/tessdata/, then asserts each file is non-zero and at least as large as
// the expected lower bound (see OcrConstants.ENG_TRAINEDDATA_MIN_BYTES / ARA_TRAINEDDATA_MIN_BYTES).
//
// This exists specifically because a previous attempt at this feature shipped 0-byte placeholder
// trained-data files multiple times without anyone catching it. Making the download+verification
// a real build task (rather than a one-off manual step) means it is structurally impossible to
// ship broken/empty tessdata again: the build fails loudly instead.
val tessdataDir = layout.projectDirectory.dir("src/main/assets/tessdata")
val tessdataBaseUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"

// Keep these in sync with OcrConstants.ENG_TRAINEDDATA_MIN_BYTES / ARA_TRAINEDDATA_MIN_BYTES.
val engMinBytes = 3_500_000L
val araMinBytes = 1_200_000L

tasks.register("downloadAndVerifyTessdata") {
    group = "ocr"
    description = "Downloads and byte-size-verifies eng.traineddata and ara.traineddata for the Screen Text Extractor feature."

    doLast {
        val targetDir = tessdataDir.asFile
        targetDir.mkdirs()

        val files = mapOf(
            "eng.traineddata" to engMinBytes,
            "ara.traineddata" to araMinBytes
        )

        for ((fileName, minBytes) in files) {
            val targetFile = File(targetDir, fileName)

            val needsDownload = !targetFile.exists() || targetFile.length() < minBytes
            if (needsDownload) {
                logger.lifecycle("Downloading $fileName from tessdata_fast...")
                val url = java.net.URI("$tessdataBaseUrl/$fileName").toURL()
                url.openStream().use { input: java.io.InputStream ->
                 targetFile.outputStream().use { output: java.io.FileOutputStream ->
                   input.copyTo(output)
                  }
               }
            }

            val actualBytes = targetFile.length()
            logger.lifecycle("Verified $fileName: $actualBytes bytes (minimum required: $minBytes bytes)")

            if (actualBytes < minBytes) {
                throw GradleException(
                    "Tessdata verification FAILED for $fileName: got $actualBytes bytes, " +
                    "expected at least $minBytes bytes. This file is likely empty/corrupt/truncated " +
                    "and OCR would silently fail to initialize. Aborting build rather than shipping it."
                )
            }
        }
    }
}

// Run before every build so tessdata is always present and verified, without requiring a
// manual step from the developer.
tasks.named("preBuild") {
    dependsOn("downloadAndVerifyTessdata")
}
