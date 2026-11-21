import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase config is read from the gitignored google-services.json (no Google Services plugin);
// without the file the app builds and runs with push disabled.
@Suppress("UNCHECKED_CAST")
val firebase: Map<String, String>? = providers.fileContents(layout.projectDirectory.file("google-services.json"))
    .asText.orNull?.let { txt ->
        val root = JsonSlurper().parseText(txt) as Map<String, Any?>
        val project = root["project_info"] as Map<String, Any?>
        val client = (root["client"] as List<Map<String, Any?>>).first {
            val info = it["client_info"] as Map<String, Any?>
            (info["android_client_info"] as Map<String, Any?>)["package_name"] == "app.eventborg"
        }
        mapOf(
            "google_app_id" to ((client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String),
            "gcm_defaultSenderId" to project["project_number"].toString(),
            "project_id" to (project["project_id"] as String),
            "google_api_key" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        )
    }

val keystore: String? = providers.environmentVariable("EB_KEYSTORE").orNull

android {
    namespace = "app.eventborg"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.eventborg"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.7.0"
        buildConfigField("String", "DATA_URL", "\"https://sicko-moud.github.io/EventBorg/\"")
        buildConfigField("String", "WORKER_URL", "\"https://eventborg.eventborg-worker.workers.dev\"")
        buildConfigField("boolean", "FIREBASE", "${firebase != null}")
        firebase?.forEach { (k, v) -> resValue("string", k, v) }
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("EB_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("EB_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("EB_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("en", "da")
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "/META-INF/*.version", "/kotlin/**")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
}
