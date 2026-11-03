package com.vista.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produit le Baseline Profile de Vista : le code parcouru ici (démarrage, pile d'albums, mosaïque,
 * visionneuse, Explorer) est compilé d'avance à l'installation au lieu d'être interprété les
 * premières fois. Résultat : démarrage à froid plus court et premiers défilements sans saccades.
 *
 * `./gradlew :app:generateReleaseBaselineProfile`, avec un appareil sous Android 13 ou plus
 * branché (ou un émulateur), qui a quelques photos dans sa galerie.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        // Le démarrage seul forme aussi le « startup profile » (ordre du code dans l'APK).
        includeInStartupProfile = true,
    ) {
        grantMediaAccess()
        pressHome()
        startActivityAndWait()
        passOnboarding()
        flipAlbumStack()
        browseAlbum()
        openExplore()
    }
}
