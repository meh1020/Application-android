package com.vista.photoeditor.ui.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.data.VideoFrames
import com.vista.photoeditor.data.VideoTrimmer
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.bouncyClickable
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import com.vista.photoeditor.ui.viewer.VideoPlayback
import com.vista.photoeditor.ui.viewer.VideoSurface
import com.vista.photoeditor.ui.viewer.rememberVideoPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Découpage d'une vidéo, comme dans Photos sur iPhone : la pellicule de la vidéo, deux poignées
 * pour le début et la fin, la lecture qui tourne en boucle sur la sélection. « Enregistrer une
 * copie » écrit la partie gardée dans un nouveau fichier, sans réencodage (voir [VideoTrimmer]) ;
 * l'original reste tel quel.
 */
@Composable
fun VideoTrimScreen(
    video: MediaPhoto?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onMessage: (String) -> Unit,
) {
    if (video == null) {
        // Vidéo supprimée entre-temps (ou relue après un arrêt de l'app) : rien à découper.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = rememberVideoPlayback()
    LaunchedEffect(video.id) { playback.open(video) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }

    val duration = maxOf(video.durationMillis, playback.durationMillis)
    var start by remember { mutableLongStateOf(0L) }
    // -1 : pas encore choisie, la fin de la vidéo (dont la durée peut arriver avec le lecteur).
    var chosenEnd by remember { mutableLongStateOf(-1L) }
    val end = if (chosenEnd < 0) duration else chosenEnd
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    // La lecture tourne en boucle sur la sélection.
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    LaunchedEffect(playback, playback.isPlaying) {
        while (playback.isPlaying) {
            withFrameMillis { playback.syncPosition() }
            if (currentEnd > 0 && playback.positionMillis >= currentEnd) {
                playback.pause()
                playback.seekTo(currentStart)
            }
        }
        playback.syncPosition()
    }

    val togglePlay: () -> Unit = {
        val position = playback.positionMillis
        val playing = playback.wantsToPlay && !playback.isEnded
        if (!playing && (position < start || position >= end - END_MARGIN_MILLIS)) playback.seekTo(start)
        playback.togglePlay()
    }

    val save: () -> Unit = save@{
        if (saving || duration <= 0) return@save
        saving = true
        progress = 0f
        playback.pause()
        scope.launch {
            try {
                VideoTrimmer.trim(context, video.uri, start, end, video.dateMillis) { progress = it }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoTrimmer.UnsupportedVideoException) {
                onMessage("Ce format de vidéo ne se découpe pas ici. « Ouvrir avec… » la confie à une autre app.")
            } catch (e: Exception) {
                onMessage("Découpage impossible : la copie n'a pas pu être écrite")
            } finally {
                saving = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Découper", onBack, Icons.AutoMirrored.Filled.KeyboardArrowLeft)

            // Aperçu : la vidéo au format, l'affiche dessous le temps que la première image arrive.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = playback.aspectRatio
                    ?: (video.displayWidth.takeIf { it > 0 }?.toFloat()?.div(video.displayHeight.coerceAtLeast(1)) ?: (9f / 16f))
                Box(
                    Modifier
                        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .plainClickable(togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    PhotoImage(video.uri, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    VideoSurface(playback, Modifier.fillMaxSize())
                    if (!(playback.wantsToPlay && !playback.isEnded)) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Lire la sélection", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeLabel("Début", DateLabels.duration(start))
                    TimeLabel("Durée", DateLabels.duration((end - start).coerceAtLeast(0)), center = true)
                    TimeLabel("Fin", DateLabels.duration(end), alignEnd = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(VistaColors.SurfaceSoft)
                            .plainClickable(togglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        val playing = playback.wantsToPlay && !playback.isEnded
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Lire la sélection",
                            tint = VistaColors.Text,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TrimBar(
                        video = video,
                        playback = playback,
                        durationMillis = duration,
                        start = start,
                        end = end,
                        enabled = !saving && duration > 0,
                        onStartChange = { start = it },
                        onEndChange = { chosenEnd = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Le début se cale sur l'image complète la plus proche, juste avant : souvent moins d'une seconde. " +
                        "L'original reste intact ; la copie rejoint « Créations Vista ».",
                    fontFamily = Kanit,
                    fontSize = 12.sp,
                    color = VistaColors.Muted,
                )
                Spacer(Modifier.height(14.dp))
                SaveButton(
                    label = if (saving) "Enregistrement… ${(progress * 100).roundToInt()} %" else "Enregistrer une copie",
                    enabled = !saving && duration > 0 && !TrimRange.isWhole(start, end, duration),
                    busy = saving,
                    onClick = save,
                )
            }
        }
    }
}

/**
 * Pellicule de la vidéo entre deux poignées : à gauche le début, à droite la fin. Ce qui est
 * écarté s'assombrit ; un trait blanc suit la lecture. Glisser une poignée la déplace (l'aperçu
 * montre l'image sous elle) ; glisser entre les deux déplace la lecture.
 */
@Composable
private fun TrimBar(
    video: MediaPhoto,
    playback: VideoPlayback,
    durationMillis: Long,
    start: Long,
    end: Long,
    enabled: Boolean,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val accent = VistaColors.Primary
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    val currentDuration by rememberUpdatedState(durationMillis)
    val currentEnabled by rememberUpdatedState(enabled)
    val changeStart by rememberUpdatedState(onStartChange)
    val changeEnd by rememberUpdatedState(onEndChange)

    BoxWithConstraints(modifier.height(BAR_HEIGHT)) {
        val widthPx = constraints.maxWidth.toFloat()
        val handlePx = with(density) { HANDLE_WIDTH.toPx() }
        val stripPx = (widthPx - 2 * handlePx).coerceAtLeast(1f)
        val heightPx = with(density) { BAR_HEIGHT.toPx() }
        val ratio = remember(video.id) {
            ((video.displayWidth.takeIf { it > 0 } ?: 16).toFloat() / (video.displayHeight.takeIf { it > 0 } ?: 9)).coerceIn(0.5f, 1.8f)
        }
        val count = ceil(stripPx / (heightPx * ratio)).toInt().coerceIn(4, 24)
        val frames = remember(video.uri, count) { mutableStateListOf<ImageBitmap?>().apply { repeat(count) { add(null) } } }
        LaunchedEffect(video.uri, count) {
            VideoFrames.filmstrip(context, video.uri, count, heightPx.roundToInt()) { index, frame ->
                if (index < frames.size) frames[index] = frame.asImageBitmap()
            }
        }

        // Pellicule, entre les deux poignées.
        Row(
            Modifier
                .offset { IntOffset(handlePx.roundToInt(), 0) }
                .width(with(density) { stripPx.toDp() })
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            frames.forEach { frame ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (frame != null) {
                        Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    val grab = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val duration = currentDuration
                        if (!currentEnabled || duration <= 0) return@awaitEachGesture
                        fun timeAt(x: Float) = (((x - handlePx) / stripPx).coerceIn(0f, 1f) * duration).toLong()
                        val startX = handlePx + currentStart.toFloat() / duration * stripPx
                        val endX = handlePx + currentEnd.toFloat() / duration * stripPx
                        val toStart = abs(down.position.x - (startX - handlePx / 2))
                        val toEnd = abs(down.position.x - (endX + handlePx / 2))
                        val target = when {
                            toStart <= grab && toStart <= toEnd -> Target.START
                            toEnd <= grab -> Target.END
                            down.position.x < startX -> Target.START
                            down.position.x > endX -> Target.END
                            else -> Target.PLAYHEAD
                        }
                        down.consume()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        playback.beginScrub()
                        // Le doigt posé à côté d'une poignée la tire depuis là où elle est.
                        val grabOffset = when (target) {
                            Target.START -> (startX - handlePx / 2) - down.position.x
                            Target.END -> (endX + handlePx / 2) - down.position.x
                            Target.PLAYHEAD -> 0f
                        }
                        fun moveTo(x: Float) {
                            when (target) {
                                Target.START -> {
                                    val t = TrimRange.moveStart(timeAt(x + grabOffset + handlePx / 2), currentEnd, duration)
                                    changeStart(t)
                                    playback.scrubTo(t.toFloat() / duration)
                                }
                                Target.END -> {
                                    val t = TrimRange.moveEnd(timeAt(x + grabOffset - handlePx / 2), currentStart, duration)
                                    changeEnd(t)
                                    playback.scrubTo(t.toFloat() / duration)
                                }
                                Target.PLAYHEAD -> {
                                    val t = timeAt(x).coerceIn(currentStart, currentEnd)
                                    playback.scrubTo(t.toFloat() / duration)
                                }
                            }
                        }
                        moveTo(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            moveTo(change.position.x)
                        }
                        playback.endScrub()
                    }
                },
        ) {
            val duration = durationMillis.coerceAtLeast(1)
            val sx = handlePx + start.toFloat() / duration * stripPx
            val ex = handlePx + end.toFloat() / duration * stripPx
            val h = size.height
            val veil = Color.Black.copy(alpha = 0.55f)
            // Parties écartées, assombries.
            if (sx > handlePx) drawRect(veil, Offset(handlePx, 0f), Size(sx - handlePx, h))
            if (ex < handlePx + stripPx) drawRect(veil, Offset(ex, 0f), Size(handlePx + stripPx - ex, h))
            // Cadre de la sélection et ses deux poignées.
            val border = 3.dp.toPx()
            drawRect(accent, Offset(sx, 0f), Size(ex - sx, border))
            drawRect(accent, Offset(sx, h - border), Size(ex - sx, border))
            val radius = CornerRadius(6.dp.toPx())
            drawRoundRect(accent, Offset(sx - handlePx, 0f), Size(handlePx, h), radius)
            drawRoundRect(accent, Offset(ex, 0f), Size(handlePx, h), radius)
            val grip = h * 0.32f
            val stroke = 2.5.dp.toPx()
            drawLine(Color.White, Offset(sx - handlePx / 2, h / 2 - grip / 2), Offset(sx - handlePx / 2, h / 2 + grip / 2), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(ex + handlePx / 2, h / 2 - grip / 2), Offset(ex + handlePx / 2, h / 2 + grip / 2), stroke, StrokeCap.Round)
            // Repère de lecture.
            val position = playback.positionMillis
            if (position in start..end) {
                val px = handlePx + position.toFloat() / duration * stripPx
                drawRoundRect(Color.White, Offset(px - 1.5.dp.toPx(), -2.dp.toPx()), Size(3.dp.toPx(), h + 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

private enum class Target { START, END, PLAYHEAD }

@Composable
private fun TimeLabel(label: String, value: String, center: Boolean = false, alignEnd: Boolean = false) {
    Column(
        horizontalAlignment = when {
            center -> Alignment.CenterHorizontally
            alignEnd -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        Text(label, fontFamily = Kanit, fontSize = 12.sp, color = VistaColors.Muted)
        Text(value, fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = VistaColors.Text)
    }
}

@Composable
private fun SaveButton(label: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (enabled || busy) VistaColors.Primary else VistaColors.Primary.copy(alpha = 0.35f))
            .then(if (enabled) Modifier.bouncyClickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = VistaColors.OnPrimary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = VistaColors.OnPrimary, textAlign = TextAlign.Center)
    }
}

private val BAR_HEIGHT = 56.dp
private val HANDLE_WIDTH = 16.dp

/** Lecture relancée depuis le début si elle était à moins de ça de la fin. */
private const val END_MARGIN_MILLIS = 150L
