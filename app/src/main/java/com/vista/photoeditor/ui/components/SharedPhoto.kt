package com.vista.photoeditor.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntOffset

/**
 * Mouvements inspirés de Photos sur iOS : ressorts sans rebond d'environ 0,35 s pour la
 * navigation, et un zoom « héros » à peine élastique pour l'ouverture d'une photo.
 */
object AppleMotion {
    const val STIFFNESS = 380f

    val push = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = STIFFNESS,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    val hero = BoundsTransform { _, _ ->
        spring(dampingRatio = 0.86f, stiffness = 320f, visibilityThreshold = Rect.VisibilityThreshold)
    }
}

/** Portée de la transition partagée (fournie autour de la navigation). */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Portée d'animation de l'écran courant, nécessaire aux éléments partagés. */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Lie une photo entre la grille et la visionneuse : à l'ouverture, la vignette s'agrandit
 * jusqu'à sa place dans la visionneuse, et revient dans la grille à la fermeture.
 * Sans portée fournie (aperçus, tests), le modificateur n'a aucun effet.
 */
@Composable
fun Modifier.sharedPhoto(photoId: Long, shape: Shape): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            state = rememberSharedContentState(key = "photo-$photoId"),
            animatedVisibilityScope = visibility,
            boundsTransform = AppleMotion.hero,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
