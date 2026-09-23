package com.vista.photoeditor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.editor.VIGNETTE_INNER_STOP
import com.vista.photoeditor.editor.VIGNETTE_MAX_ALPHA
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.LocalVistaColors
import com.vista.photoeditor.ui.theme.VistaColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Ombre douce et diffuse du design, teintée selon le thème. */
@Composable
fun Modifier.softShadow(shape: androidx.compose.ui.graphics.Shape, elevation: Dp = 10.dp): Modifier {
    val colors = LocalVistaColors.current
    return shadow(elevation, shape, clip = false, ambientColor = colors.shadowAmbient, spotColor = colors.shadowSpot)
}

/** Léger enfoncement pendant l'appui, relâché avec un petit rebond. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.93f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Clic sans ondulation, mais avec l'enfoncement animé. */
@Composable
fun Modifier.bouncyClickable(pressedScale: Float = 0.96f, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .pressScale(interaction, pressedScale)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Bouton rond blanc (ou violet si [selected]). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val glass by animateColorAsState(
        if (selected) VistaColors.Primary.copy(alpha = 0.85f) else glassTint(),
        tween(220),
        label = "circleGlass",
    )
    val tint by animateColorAsState(
        when {
            selected -> VistaColors.OnPrimary
            enabled -> VistaColors.Text
            else -> VistaColors.Text.copy(alpha = 0.3f)
        },
        tween(220),
        label = "circleTint",
    )
    Box(
        modifier
            .size(size)
            .pressScale(interaction)
            .liquidGlass(CircleShape, tint = glass, elevation = if (selected) 8.dp else 6.dp, lens = false)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Bouton d'action de la visionneuse : cercle blanc souligné de violet, ou plein. */
@Composable
fun ActionCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    size: Dp = 60.dp,
) {
    val primary = VistaColors.Primary
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .pressScale(interaction)
            .liquidGlass(
                CircleShape,
                tint = if (filled) VistaColors.Primary.copy(alpha = 0.88f) else Color.Unspecified,
                lens = false,
            )
            .drawBehind {
                if (!filled) {
                    val stroke = 2.dp.toPx()
                    drawArc(
                        primary, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(stroke),
                    )
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (filled) VistaColors.OnPrimary else VistaColors.Primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Bouton « + » violet flottant. */
@Composable
fun PrimaryFab(onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Add, size: Dp = 58.dp) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction, pressedScale = 0.9f)
            .liquidGlass(CircleShape, tint = VistaColors.Primary.copy(alpha = 0.88f), elevation = 12.dp, lens = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = "Ajouter", tint = VistaColors.OnPrimary, modifier = Modifier.size(26.dp))
    }
}

/** Étiquette translucide façon verre dépoli posée sur une photo. */
@Composable
fun GlassLabel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    dark: Boolean = false,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0x99303036), Color(0xB31C1C22))
                    else listOf(Color(0x66FFFFFF), Color(0x33FFFFFF))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.45f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (dark) Alignment.Start else Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = if (dark) 18.sp else 26.sp)
            if (subtitle != null) {
                Text(subtitle, color = Color.White.copy(alpha = 0.9f), fontFamily = Kanit, fontSize = 12.sp)
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 16.dp)) { trailing() }
        }
    }
}

/** En-tête : bouton retour, titre centré, action optionnelle à droite. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    backIcon: ImageVector,
    modifier: Modifier = Modifier,
    titleInGlass: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            CircleIconButton(backIcon, "Retour", onBack, Modifier.align(Alignment.CenterStart), iconSize = 20.dp)
        }
        // Titre posé sur une capsule de verre quand le contenu défile dessous.
        val titleModifier = if (titleInGlass) {
            Modifier
                .liquidGlass(RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 9.dp)
        } else {
            Modifier
        }
        Text(
            title,
            fontFamily = Kanit,
            fontWeight = FontWeight.Medium,
            fontSize = if (titleInGlass) 17.sp else 19.sp,
            color = VistaColors.Text,
            modifier = titleModifier,
        )
        if (trailing != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/**
 * Règle graduée qui défile sous un repère fixe (triangle violet).
 * Glisser vers la gauche augmente la valeur.
 */
@Composable
fun RulerDial(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    unitsPerTick: Float = 1f,
    ticksPerLabel: Int = 5,
    label: (Float) -> String = { it.roundToInt().toString() },
) {
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val latestValue by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val labelStyle = remember { TextStyle(fontFamily = Kanit, fontSize = 10.sp, textAlign = TextAlign.Center) }
    val ink = VistaColors.Text
    val accent = VistaColors.Primary

    Canvas(
        modifier
            .fillMaxWidth()
            .height(70.dp)
            .pointerInput(range, unitsPerTick) {
                val spacing = TICK_SPACING.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var raw = latestValue
                    var current = latestValue
                    drag(down.id) { change ->
                        val dx = change.positionChange().x
                        change.consume()
                        raw = (raw - dx / spacing * unitsPerTick).coerceIn(range.start, range.endInclusive)
                        val tick = (raw / unitsPerTick).roundToInt()
                        val snapped = tick * unitsPerTick
                        if (snapped != current) {
                            if (tick % ticksPerLabel == 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            current = snapped
                            onChange(snapped)
                        }
                    }
                    onFinished()
                }
            }
    ) {
        val spacing = TICK_SPACING.toPx()
        val cx = size.width / 2f
        val baseY = 42.dp.toPx()
        val centerTick = value / unitsPerTick
        val visible = size.width / 2f / spacing + 1
        val first = maxOf(ceil(range.start / unitsPerTick).toInt(), floor(centerTick - visible).toInt())
        val last = minOf(floor(range.endInclusive / unitsPerTick).toInt(), ceil(centerTick + visible).toInt())

        for (tick in first..last) {
            val x = cx + (tick - centerTick) * spacing
            val major = tick % ticksPerLabel == 0
            val fade = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f) * 0.8f
            val top = baseY - if (major) 24.dp.toPx() else 14.dp.toPx()
            drawLine(
                ink.copy(alpha = (if (major) 0.85f else 0.4f) * fade),
                Offset(x, top), Offset(x, baseY),
                strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
            )
            if (major) {
                val layout = measurer.measure(label(tick * unitsPerTick), labelStyle)
                drawText(
                    layout,
                    color = ink,
                    alpha = fade,
                    topLeft = Offset(x - layout.size.width / 2f, baseY + 8.dp.toPx()),
                )
            }
        }

        val tri = Path().apply {
            moveTo(cx - 6.dp.toPx(), 0f)
            lineTo(cx + 6.dp.toPx(), 0f)
            lineTo(cx, 9.dp.toPx())
            close()
        }
        drawPath(tri, accent)
        drawLine(accent, Offset(cx, 8.dp.toPx()), Offset(cx, baseY), 1.6.dp.toPx())
    }
}

private val TICK_SPACING = 11.dp

/** Boîte centrée à la taille maximale respectant le rapport de l'image. */
@Composable
fun FittedImageBox(
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = FittedImageBox(imageWidth.toFloat() / imageHeight, modifier, content)

/** Variante prenant directement le rapport largeur / hauteur. */
@Composable
fun FittedImageBox(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = if (aspectRatio > 0f && aspectRatio.isFinite()) aspectRatio else 1f
        val boxRatio = maxWidth.value / maxHeight.value
        val width: Dp
        val height: Dp
        if (ratio > boxRatio) {
            width = maxWidth
            height = maxWidth / ratio
        } else {
            height = maxHeight
            width = maxHeight * ratio
        }
        Box(Modifier.size(width, height), content = content)
    }
}

/** Même dégradé que [com.vista.photoeditor.editor.ImageIO.render]. */
fun vignetteBrush(size: Size, amount: Float): Brush = Brush.radialGradient(
    0f to Color.Transparent,
    VIGNETTE_INNER_STOP to Color.Transparent,
    1f to Color.Black.copy(alpha = VIGNETTE_MAX_ALPHA * amount),
    center = size.center,
    radius = hypot(size.width, size.height) / 2f,
)

/** Clic sans effet d'ondulation. */
@Composable
fun Modifier.plainClickable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
