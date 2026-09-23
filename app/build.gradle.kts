plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vista.photoeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vista.photoeditor"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signé avec la clé debug pour pouvoir installer l'APK release directement.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Transitions partagées (zoom « héros » grille ⇄ visionneuse).
        freeCompilerArgs += listOf("-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi")
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    // Flou d'arrière-plan des surfaces « Liquid Glass » (Android 12+ ; voile teinté en dessous).
    implementation("dev.chrisbanes.haze:haze:1.2.2")
    // Lecture/écriture des métadonnées (date, lieu, appareil) des JPEG.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Reconnaissance d'images embarquée, pour la recherche par contenu hors ligne.
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
