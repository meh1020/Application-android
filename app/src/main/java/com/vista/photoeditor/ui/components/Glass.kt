package com.vista.photoeditor.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vista.photoeditor.ui.theme.LocalVistaColors
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * Matière « Liquid Glass » : les surfaces flottantes floutent, teintent et courbent le contenu
 * qui passe dessous. Chaque écran a sa zone de flou ([GlassScope]) ; le contenu à flouter est
 * enveloppé dans [GlassBackdrop], et les surfaces posées au-dessus utilisent [liquidGlass].
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Intensité « élevée », celle des commandes de l'appareil photo sur iOS 26 : verre clair,
 * flou léger qui laisse reconnaître la scène, bords en lentille et reflets marqués.
 */
private object GlassLook {
    /** Flou léger : le contenu derrière reste reconnaissable. */
    val blur = 14.dp

    /** Voile presque incolore. */
    const val LIGHT_TINT = 0.14f
    const val DARK_TINT = 0.22f

    /**
     * Largeur du bord où le contenu se courbe : limitée à l'arête, pour ne pas déformer
     * les icônes et vignettes posées sur le verre (toutes à plus de 7 dp du bord).
     */
    val edgeBand = 7.dp

    /** Déplacement maximal du contenu sur le bord (effet lentille). */
    val refraction = 7.dp

    /** Écart des canaux rouge et bleu dans la courbure : légère irisation des bords. */
    const val DISPERSION = 0.08f
}

/** Donne à un écran sa propre zone de flou. */
@Composable
fun GlassScope(content: @Composable () -> Unit) {
    val state = remember { HazeState() }
    CompositionLocalProvider(LocalHazeState provides state, content = content)
}

/**
 * Contenu que le verre floute. Les surfaces en verre placées *à l'intérieur* ne peuvent pas
 * flouter leur propre source : elles prennent alors un voile translucide, sans flou.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = LocalHazeState.current
    Box(if (state != null) modifier.hazeSource(state) else modifier) {
        val scope = this
        CompositionLocalProvider(LocalHazeState provides null) { scope.content() }
    }
}

/** Voile du verre neutre, adapté au thème. */
@Composable
fun glassTint(): Color = if (LocalVistaColors.current.isDark) {
    Color(0xFF202028).copy(alpha = GlassLook.DARK_TINT)
} else {
    Color.White.copy(alpha = GlassLook.LIGHT_TINT)
}

/**
 * Surface en verre : flou de l'arrière-plan, voile teinté ([tint], ou neutre), bords qui
 * courbent le contenu comme une lentille, reflet en haut, ombre d'épaisseur en bas et
 * liseré spéculaire vif sur les arêtes.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.Unspecified,
    elevation: Dp = 8.dp,
    /** La courbure de lentille est réservée aux grandes surfaces : invisible sur un petit
     *  bouton, elle y coûterait une couche de rendu supplémentaire. */
    lens: Boolean = true,
): Modifier {
    val colors = LocalVistaColors.current
    val state = LocalHazeState.current
    val dark = colors.isDark
    val fill = if (tint.isSpecified) tint else glassTint()

    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.6f else 1f),
        0.35f to Color.White.copy(alpha = if (dark) 0.08f else 0.25f),
        0.65f to Color.White.copy(alpha = if (dark) 0.05f else 0.15f),
        1f to Color.White.copy(alpha = if (dark) 0.38f else 0.8f),
    )
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.12f else 0.28f),
        0.4f to Color.Transparent,
        0.8f to Color.Transparent,
        1f to Color.Black.copy(alpha = if (dark) 0.2f else 0.07f),
    )
    val material = if (state != null) {
        Modifier.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = colors.background,
                tints = listOf(HazeTint(fill)),
                blurRadius = GlassLook.blur,
                noiseFactor = 0f,
                // Sans flou disponible (Android 11), un voile plus couvrant garde la lisibilité.
                fallbackTint = HazeTint(fill.copy(alpha = (fill.alpha + 0.45f).coerceAtMost(0.9f))),
            ),
        )
    } else {
        Modifier.background(fill.copy(alpha = (fill.alpha + 0.4f).coerceAtMost(0.9f)))
    }
    // Le liseré et le reflet sont posés par-dessus la couche courbée : ils restent nets.
    return this
        .softShadow(shape, elevation)
        .clip(shape)
        .border(1.2.dp, rim, shape)
        .drawWithContent {
            drawContent()
            drawRect(sheen)
        }
        .then(if (lens) Modifier.refraction(shape) else Modifier)
        .then(material)
}

/** Courbure de lentille sur les bords (Android 13+ ; sans effet avant). */
@Composable
private fun Modifier.refraction(shape: Shape): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) refractionEffect(shape) else this

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.refractionEffect(shape: Shape): Modifier {
    val shader = remember { RuntimeShader(REFRACTION_SHADER) }
    return graphicsLayer {
        val radius = (shape as? CornerBasedShape)?.topStart?.toPx(size, this) ?: 0f
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("band", GlassLook.edgeBand.toPx())
        shader.setFloatUniform("strength", GlassLook.refraction.toPx())
        shader.setFloatUniform("dispersion", GlassLook.DISPERSION)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * Près du bord, chaque pixel est lu plus à l'intérieur, le long de la normale au contour :
 * le contenu se courbe comme dans une lentille, avec les canaux rouge et bleu légèrement décalés.
 */
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float strength;
uniform float dispersion;

float boxSdf(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float r = min(radius, min(halfSize.x, halfSize.y));
    float inside = -boxSdf(p, halfSize, r);
    float t = clamp(1.0 - inside / band, 0.0, 1.0);
    t = t * t;
    float2 e = float2(1.0, 0.0);
    float2 normal = float2(
        boxSdf(p + e.xy, halfSize, r) - boxSdf(p - e.xy, halfSize, r),
        boxSdf(p + e.yx, halfSize, r) - boxSdf(p - e.yx, halfSize, r));
    normal = normal / max(length(normal), 0.0001);
    float2 offset = normal * strength * t;
    half4 base = content.eval(coord - offset);
    half4 redSample = content.eval(coord - offset * (1.0 + dispersion));
    half4 blueSample = content.eval(coord - offset * (1.0 - dispersion));
    // L'irisation n'est appliquée que sur le fond opaque : pas de frange au bord d'un contenu.
    if (base.a < 0.99 || redSample.a < 0.99 || blueSample.a < 0.99) {
        return base;
    }
    return half4(redSample.r, base.g, blueSample.b, base.a);
}
"""

/**
 * Flou progressif en haut de l'écran : le contenu qui remonte sous la barre d'état et l'en-tête
 * s'y estompe au lieu de chevaucher l'heure, comme sur iOS.
 */
@Composable
fun GlassTopEdge(height: Dp, modifier: Modifier = Modifier) {
    val state = LocalHazeState.current
    val colors = LocalVistaColors.current
    val fade = Brush.verticalGradient(
        0f to colors.background.copy(alpha = 0.72f),
        0.55f to colors.background.copy(alpha = 0.25f),
        1f to Color.Transparent,
    )
    val blur = if (state != null) {
        Modifier.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = colors.background,
                tints = listOf(HazeTint(colors.background.copy(alpha = 0.2f))),
                blurRadius = 20.dp,
                noiseFactor = 0f,
            ),
        ) {
            progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
        }
    } else {
        Modifier
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(blur)
            .background(fade)
    )
}

/** Conteneur en verre (barres, capsules, panneaux). */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.liquidGlass(shape, tint), content = content)
}
