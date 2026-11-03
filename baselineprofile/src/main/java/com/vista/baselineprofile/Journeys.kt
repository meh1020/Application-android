package com.vista.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/**
 * Parcours de Vista communs au profil et aux mesures. Les écrans portent des repères (`testTag`
 * exposés comme identifiants, voir MainActivity) : « album_front », « album_grid », « album_photo »,
 * « viewer_pager ». Chaque étape renonce sans échouer si son écran n'apparaît pas (galerie vide,
 * par exemple) : le profil couvre alors ce qui a pu être parcouru.
 */
internal const val PACKAGE = "com.vista.photoeditor"

private const val WAIT_MILLIS = 5_000L

/**
 * Accès aux photos et vidéos accordé d'avance : aucune fenêtre de permission ne bloque le parcours.
 * Une permission inconnue de cette version d'Android est simplement refusée par `pm`.
 */
internal fun MacrobenchmarkScope.grantMediaAccess() {
    listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION").forEach {
        device.executeShellCommand("pm grant $packageName android.permission.$it")
    }
}

/** Écran de bienvenue (premier lancement) : « Capturer la vie » mène à l'accueil. */
internal fun MacrobenchmarkScope.passOnboarding() {
    device.findObject(By.text("Capturer la vie"))?.click()
    device.wait(Until.hasObject(By.res("album_front")), WAIT_MILLIS)
}

/** Accueil : la pile d'albums, quelques cartes passées au premier plan. */
internal fun MacrobenchmarkScope.flipAlbumStack() {
    repeat(2) {
        device.findObject(By.res("album_back"))?.click() ?: return
        device.waitForIdle()
    }
}

/** Album : défilement de la mosaïque, puis une photo ouverte et quelques voisines parcourues. */
internal fun MacrobenchmarkScope.browseAlbum() {
    val front = device.findObject(By.res("album_front")) ?: return
    front.click()
    if (!device.wait(Until.hasObject(By.res("album_grid")), WAIT_MILLIS)) return
    val grid = device.findObject(By.res("album_grid")) ?: return
    // Marge : le geste ne part pas du bord, que le système prendrait pour un retour.
    grid.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        grid.fling(Direction.DOWN)
        device.waitForIdle()
    }
    grid.fling(Direction.UP)
    device.waitForIdle()

    device.findObject(By.res("album_photo"))?.let { photo ->
        photo.click()
        if (device.wait(Until.hasObject(By.res("viewer_pager")), WAIT_MILLIS)) {
            val pager = device.findObject(By.res("viewer_pager"))
            pager.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                pager.swipe(Direction.LEFT, 0.8f)
                device.waitForIdle()
            }
            device.pressBack()
            device.wait(Until.hasObject(By.res("album_grid")), WAIT_MILLIS)
        }
    }
    device.pressBack()
    device.wait(Until.hasObject(By.res("album_front")), WAIT_MILLIS)
}

/** Explorer : catégories et souvenirs, puis retour à l'accueil. */
internal fun MacrobenchmarkScope.openExplore() {
    val tab = device.findObject(By.desc("Explorer")) ?: device.findObject(By.text("Explorer")) ?: return
    tab.click()
    device.waitForIdle()
    device.findObject(By.scrollable(true))?.let {
        it.setGestureMargin(device.displayWidth / 5)
        it.fling(Direction.DOWN)
        device.waitForIdle()
    }
    device.pressBack()
    device.wait(Until.hasObject(By.res("album_front")), WAIT_MILLIS)
}
