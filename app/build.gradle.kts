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

    /**
     * Un APK par type de processeur : les modèles embarqués (reconnaissance d'images et
     * détection de visages) livrent une bibliothèque native par architecture, et un téléphone
     * n'en utilise qu'une. L'APK universel reste produit pour les cas où l'on ne sait pas
     * à l'avance sur quel appareil installer.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        jniLibs {
            // Bibliothèques natives compressées dans l'APK (moteur ONNX : 17,5 Mo brutes, 6,3 Mo
            // compressées pour arm64). Android les décompresse à l'installation.
            useLegacyPackaging = true
        }
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
    // Compréhension d'image (MobileCLIP) : catégories, souvenirs et recherche par contenu.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Analyse de toute la galerie pendant la recharge.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
