import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.precisionmeasure.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.precisionmeasure.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        // Update this single line with your Play Store support email before publishing.
        resValue("string", "feedback_email", "feedback@androidmeasure.app")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                val storeFilePath = requireNotNull(keystoreProperties.getProperty("storeFile")) {
                    "keystore.properties is missing storeFile"
                }
                val storePass = requireNotNull(keystoreProperties.getProperty("storePassword")) {
                    "keystore.properties is missing storePassword"
                }
                val alias = requireNotNull(keystoreProperties.getProperty("keyAlias")) {
                    "keystore.properties is missing keyAlias"
                }
                val keyPass = requireNotNull(keystoreProperties.getProperty("keyPassword")) {
                    "keystore.properties is missing keyPassword"
                }
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(project(":core-geometry"))
    implementation("com.google.ar:core:1.57.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
