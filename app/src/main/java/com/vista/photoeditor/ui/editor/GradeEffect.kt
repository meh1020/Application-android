package com.vista.photoeditor.ui.editor

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import com.vista.photoeditor.editor.Grade

/** L'aperçu applique l'étalonnage par un shader (Android 13 et plus) ; sinon, voir [PhotoPreview]. */
val supportsGradeShader get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Étalonnage (ombres, hautes lumières, virage partiel, couleur sélective) sur ce que dessine
 * l'élément, déjà passé par la matrice de couleurs, en temps réel. Sans effet avant Android 13.
 */
@Composable
fun Modifier.grade(grade: Grade): Modifier {
    if (grade.isIdentity || !supportsGradeShader) return this
    val effect = rememberGradeEffect(grade)
    return graphicsLayer { renderEffect = effect }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberGradeEffect(grade: Grade): androidx.compose.ui.graphics.RenderEffect {
    // Le shader n'est compilé qu'une fois ; seules ses valeurs changent avec les curseurs.
    val shader = remember { RuntimeShader(Grade.SHADER) }
    return remember(grade) {
        val zero = floatArrayOf(0f, 0f, 0f)
        shader.setFloatUniform("shadowAmp", grade.tone.shadowAmp)
        shader.setFloatUniform("highlightAmp", grade.tone.highlightAmp)
        shader.setFloatUniform("splitShadow", grade.split?.shadow ?: zero)
        shader.setFloatUniform("splitHighlight", grade.split?.highlight ?: zero)
        shader.setFloatUniform("selHue", grade.selective?.hue ?: 0f)
        shader.setFloatUniform("selWidth", grade.selective?.width ?: 0f)
        shader.setFloatUniform("selStrength", grade.selective?.strength ?: 0f)
        RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}
