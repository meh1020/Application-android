package com.vista.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mesures avant / après le Baseline Profile, sur l'appareil branché :
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 *
 * - Démarrage à froid (`timeToInitialDisplayMs`), sans profil puis avec.
 * - Images de la mosaïque d'un album pendant le défilement (`frameDurationCpuMs`, `frameOverrunMs`) :
 *   les saccades du premier défilement.
 *
 * Résultats dans `baselineprofile/build/outputs/connected_android_test_additional_output/`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun scrollAlbumWithoutProfile() = scrollAlbum(CompilationMode.None())

    @Test
    fun scrollAlbumWithProfile() = scrollAlbum(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            grantMediaAccess()
            pressHome()
        },
    ) {
        startActivityAndWait()
        passOnboarding()
    }

    private fun scrollAlbum(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            grantMediaAccess()
            pressHome()
            startActivityAndWait()
            passOnboarding()
        },
    ) {
        browseAlbum()
    }
}
