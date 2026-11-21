plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
