/**
 * Baseline Profile de Vista et mesures de démarrage (Macrobenchmark).
 *
 * - `./gradlew :app:generateReleaseBaselineProfile` : parcourt l'app sur l'appareil branché
 *   (Android 13 ou plus, émulateur compris) et écrit le profil dans `app/src/release/generated/`.
 * - `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` : mesure le démarrage à froid
 *   sans profil puis avec, pour vérifier le gain.
 */
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.vista.baselineprofile"
    compileSdk = 35

    defaultConfig {
        // Profil sans accès root : Android 13 (API 33) ou plus pour le générer ; mesures dès 30.
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    // Le téléphone ou l'émulateur branché (adb devices).
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}
