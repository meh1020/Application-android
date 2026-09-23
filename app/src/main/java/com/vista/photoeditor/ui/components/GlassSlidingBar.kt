package com.vista.photoeditor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.LocalVistaColors
import com.vista.photoeditor.ui.theme.VistaColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class GlassBarItem(val icon: ImageVector, val label: String)

/** Délai de rattrapage de la goutte derrière le doigt (lissage exponentiel), en secondes. */
private const val FOLLOW_TIME = 0.012f

/** Temps de résorption de l'étirement quand le doigt ralentit, en secondes. */
private const val STRETCH_DECAY_TIME = 0.08f

private class BarMotion {
    var follow: Job? = null
    var settle: Job? = null
}

/**
 * Barre en verre qui reproduit la barre d'onglets de Photos sur iOS 26.
 *
 * Au toucher, la barre gonfle légèrement et la pastille se soulève en goutte de verre clair.
 * La goutte suit le doigt image par image (en s'étirant selon sa vitesse), grossit les icônes
 * qui passent dessous comme une loupe, résiste en élastique au-delà des extrémités et marque
 * chaque élément franchi d'une vibration. Au relâchement, elle se pose sur l'élément choisi
 * avec un léger rebond, en repartant de la vitesse du doigt. Une simple tape fonctionne aussi.
 *
 * La goutte reste ensuite sur l'élément choisi. [selectedIndex] fixe l'élément de départ et,
 * s'il change, y ramène la goutte ; à null, elle n'apparaît qu'au premier choix.
 * [highlightIndex] met une action en avant (pastille pleine, comme « Modifier » dans Photos).
 * [showLabels] affiche le nom sous chaque icône, comme dans une barre d'onglets.
 *
 * Fluidité : le verre est une couche à part, derrière la goutte et les icônes, que le glissé ne
 * redessine pas ; et toutes les valeurs animées sont lues au dessin, sans recalcul d'interface.
 */
@Composable
fun GlassSlidingBar(
    items: List<GlassBarItem>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 60.dp,
    height: Dp = 56.dp,
    highlightIndex: Int? = null,
    showLabels: Boolean = false,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val dark = LocalVistaColors.current.isDark
    val primary = VistaColors.Primary
    val onSelectLatest by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()

    val padding = 6.dp
    val itemPx = with(density) { itemWidth.toPx() }
    val paddingPx = with(density) { padding.toPx() }
    val lensWidth = itemWidth - 6.dp
    val lensHeight = height - 10.dp
    val lensWidthPx = with(density) { lensWidth.toPx() }
    val lensTopPx = with(density) { 5.dp.toPx() }
    // Au-delà des extrémités, la goutte ne peut pas dépasser 40 % d'un élément.
    val stretchLimitPx = itemPx * 0.4f

    fun centerOf(index: Int) = paddingPx + itemPx * (index + 0.5f)
    fun indexAt(x: Float) = ((x - paddingPx) / itemPx).toInt().coerceIn(0, items.lastIndex)

    var committed by remember { mutableStateOf(selectedIndex) }
    // Élément survolé : ne change qu'au franchissement, pas à chaque image.
    var hovered by remember { mutableIntStateOf(selectedIndex ?: 0) }
    var dragging by remember { mutableStateOf(false) }

    // Position affichée de la goutte, et position visée (sous le doigt, ou élément choisi).
    val lensPos = remember { mutableFloatStateOf(centerOf(selectedIndex ?: 0)) }
    val lensTarget = remember { mutableFloatStateOf(lensPos.floatValue) }
    // Étirement (lié à la vitesse du doigt) et écrasement contre un bord (0 à 1).
    val stretch = remember { mutableFloatStateOf(0f) }
    val overshoot = remember { mutableFloatStateOf(0f) }
    // 0 : pastille posée ; 1 : goutte soulevée sous le doigt.
    val lift = remember { Animatable(0f) }
    val lensAlpha = remember { Animatable(if (selectedIndex != null) 1f else 0f) }
    val motion = remember { BarMotion() }

    /** Pose la goutte sur [target] par ressort, en partant de sa vitesse actuelle. */
    fun settleTo(target: Float, velocity: Float = 0f) {
        motion.settle?.cancel()
        motion.settle = scope.launch {
            animate(
                initialValue = lensPos.floatValue,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
            ) { value, _ -> lensPos.floatValue = value }
        }
    }

    /**
     * Pendant le glissé, à chaque image, la goutte comble une part fixe de l'écart avec le doigt :
     * elle le suit sans relancer d'animation à chaque mouvement (environ une image de retard).
     */
    fun startFollowing() {
        motion.settle?.cancel()
        if (motion.follow?.isActive == true) return
        motion.follow = scope.launch {
            var last = withFrameNanos { it }
            while (dragging) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                lensPos.floatValue += (lensTarget.floatValue - lensPos.floatValue) * (1f - exp(-dt / FOLLOW_TIME))
                stretch.floatValue *= exp(-dt / STRETCH_DECAY_TIME)
            }
        }
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex != null) {
            committed = selectedIndex
            hovered = selectedIndex
            // Déjà en route vers cet élément (relâchement du doigt) : on ne coupe pas son rebond.
            val target = centerOf(selectedIndex)
            if (lensTarget.floatValue != target) {
                lensTarget.floatValue = target
                settleTo(target)
            }
            lensAlpha.animateTo(1f, tween(180))
        }
    }

    // Au repos, la pastille est un gris discret ; soulevée, elle devient du verre clair.
    val restFill = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f)
    val liftedFill = Color.White.copy(alpha = if (dark) 0.22f else 0.4f)
    val lensRim = Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (dark) 0.55f else 0.95f), Color.White.copy(alpha = 0.12f)),
    )
    val capsule = RoundedCornerShape(percent = 50)

    Box(
        modifier
            .width(itemWidth * items.size + padding * 2)
            .height(height)
            // La barre entière gonfle un peu tant que le doigt est posé.
            .graphicsLayer {
                val grow = 1f + 0.035f * lift.value
                scaleX = grow
                scaleY = grow
            }
            .pointerInput(items.size, itemPx) {
                val first = centerOf(0)
                val last = centerOf(items.lastIndex)
                val tracker = VelocityTracker()

                fun follow(x: Float) {
                    val (position, over) = rubberBand(x, first, last, stretchLimitPx)
                    lensTarget.floatValue = position
                    overshoot.floatValue = over
                    val index = indexAt(position)
                    if (index != hovered) {
                        hovered = index
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    dragging = true
                    follow(down.position.x)
                    startFollowing()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { lensAlpha.animateTo(1f, tween(120)) }
                    scope.launch { lift.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f)) }

                    drag(down.id) { change ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        follow(change.position.x)
                        stretch.floatValue = (abs(tracker.calculateVelocity().x) / 4500f).coerceAtMost(0.35f)
                    }

                    // La goutte se pose sur l'élément choisi et y reste.
                    val chosen = indexAt(lensTarget.floatValue)
                    val releaseVelocity = tracker.calculateVelocity().x
                    dragging = false
                    committed = chosen
                    hovered = chosen
                    overshoot.floatValue = 0f
                    stretch.floatValue = 0f
                    lensTarget.floatValue = centerOf(chosen)
                    settleTo(lensTarget.floatValue, releaseVelocity)
                    scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 380f)) }
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectLatest(chosen)
                }
            },
    ) {
        // Le verre, seul dans sa couche : la goutte et les icônes qui bougent au-dessus ne
        // l'obligent pas à recalculer son flou et sa courbure à chaque image.
        Box(
            Modifier
                .matchParentSize()
                .liquidGlass(capsule)
        )

        Box(
            Modifier
                .offset { IntOffset((lensPos.floatValue - lensWidthPx / 2f).roundToInt(), lensTopPx.roundToInt()) }
                .size(lensWidth, lensHeight)
                .graphicsLayer {
                    val lifted = lift.value
                    val over = overshoot.floatValue
                    val s = stretch.floatValue
                    val grow = 1f + 0.2f * lifted
                    alpha = lensAlpha.value
                    // S'étire avec la vitesse ; contre un bord, s'écrase (plus étroite, plus haute).
                    scaleX = grow * (1f + s) * (1f - 0.35f * over)
                    scaleY = grow * (1f - s * 0.25f) * (1f + 0.18f * over)
                    // Soulevée, la goutte projette une ombre : elle se détache même sur du verre clair.
                    shadowElevation = 9.dp.toPx() * lifted
                    shape = capsule
                    clip = true
                }
                .drawBehind {
                    drawRoundRect(
                        lerp(restFill, liftedFill, lift.value.coerceIn(0f, 1f)),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
                .border(1.dp, lensRim, capsule)
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = padding),
        ) {
            items.forEachIndexed { index, item ->
                val highlighted = index == highlightIndex
                val active = index == hovered
                // Couleur changée d'un coup au franchissement : pas d'animation qui recalcule
                // icônes et libellés pendant le glissé.
                val tint = when {
                    highlighted -> VistaColors.OnPrimary
                    active -> primary
                    else -> VistaColors.Text
                }
                val itemCenter = centerOf(index)
                val edgeOrigin = when (index) {
                    0 -> 0.2f
                    items.lastIndex -> 0.8f
                    else -> 0.5f
                }
                Column(
                    Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        // Effet loupe : ce qui passe sous la goutte soulevée grossit.
                        .graphicsLayer {
                            val distance = abs(lensPos.floatValue - itemCenter) / itemPx
                            val magnify = 1f + 0.25f * lift.value * (1f - distance).coerceIn(0f, 1f)
                            scaleX = magnify
                            scaleY = magnify
                            // Aux extrémités, l'élément grossit vers l'intérieur de la barre.
                            transformOrigin = TransformOrigin(edgeOrigin, 0.5f)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = item.label
                            role = Role.Tab
                            selected = index == committed
                            onClick {
                                committed = index
                                hovered = index
                                lensTarget.floatValue = centerOf(index)
                                settleTo(lensTarget.floatValue)
                                onSelectLatest(index)
                                true
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (highlighted) {
                            Box(
                                Modifier
                                    .size(height - 14.dp)
                                    .clip(CircleShape)
                                    .background(primary)
                            )
                        }
                        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                    if (showLabels) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            color = tint,
                            fontFamily = Kanit,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Élasticité façon iOS : au-delà de [first] ou [last], le déplacement est freiné de plus en plus
 * fort, sans jamais dépasser [limit]. Renvoie la position freinée et la part du dépassement (0 à 1).
 */
private fun rubberBand(x: Float, first: Float, last: Float, limit: Float): Pair<Float, Float> {
    fun eased(distance: Float) = limit * (1f - 1f / (1f + distance / limit * 0.55f))
    return when {
        x < first -> eased(first - x).let { first - it to it / limit }
        x > last -> eased(x - last).let { last + it to it / limit }
        else -> x to 0f
    }
}
