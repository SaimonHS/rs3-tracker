import java.io.File
import java.net.URI

plugins {
    id("com.android.application")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.rstracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rstracker"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // A fixed key so every new build installs over the previous one.
    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress.add("tflite")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// The person / face detection models are downloaded at build time (Google MediaPipe, Apache 2.0).
val models = mapOf(
    "person.tflite" to "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite",
    "face.tflite" to "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite",
)

val downloadModels by tasks.registering {
    val dir = file("src/main/assets")
    doLast {
        dir.mkdirs()
        models.forEach { (name, url) ->
            val out = File(dir, name)
            if (!out.exists() || out.length() < 10_000) {
                logger.lifecycle("Downloading $name")
                URI(url).toURL().openStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(downloadModels)
}
