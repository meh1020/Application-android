package com.vista.photoeditor.ui.viewer

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.data.VideoFrames
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.GlassSurface
import com.vista.photoeditor.ui.theme.Kanit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Lecture des vidéos de la visionneuse, comme dans Photos sur iPhone : la vidéo affichée démarre
 * d'elle-même, une seule à la fois ; le son coupé le reste d'une vidéo à l'autre.
 *
 * Le lecteur n'est créé qu'à la première vidéo : parcourir des photos ne coûte rien. L'état
 * (lecture, position, durée) est observable par l'interface ; la position se met à jour à chaque
 * image tant que la vidéo joue (voir [VideoFilmstrip]).
 */
@Stable
@OptIn(UnstableApi::class)
class VideoPlayback(private val context: Context) {

    private var player: ExoPlayer? = null
    private var surface: TextureView? = null

    /** Vidéo chargée dans le lecteur, ou null. */
    var uri by mutableStateOf<Uri?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    /** Lecture demandée (elle peut attendre le chargement) : c'est ce que montre le bouton. */
    var wantsToPlay by mutableStateOf(false)
        private set
    var isEnded by mutableStateOf(false)
        private set
    var isMuted by mutableStateOf(false)
        private set
    var hasError by mutableStateOf(false)
        private set

    /** La première image est à l'écran : l'affiche peut s'effacer derrière la vidéo. */
    var hasFirstFrame by mutableStateOf(false)
        private set

    /** Format de l'image décodée (rotation comprise), ou null tant qu'il n'est pas connu. */
    var aspectRatio by mutableStateOf<Float?>(null)
        private set

    var durationMillis by mutableLongStateOf(0L)
        private set
    var positionMillis by mutableLongStateOf(0L)
        private set
    var isScrubbing by mutableStateOf(false)
        private set
    private var resumeAfterScrub = false

    val progress: Float
        get() = if (durationMillis <= 0) 0f else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            wantsToPlay = playWhenReady
        }

        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            if (p.duration != C.TIME_UNSET) durationMillis = p.duration
            isEnded = state == Player.STATE_ENDED
            if (isEnded) positionMillis = durationMillis
        }

        override fun onRenderedFirstFrame() {
            hasFirstFrame = true
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && size.height > 0) aspectRatio = size.width * size.pixelWidthHeightRatio / size.height
        }

        override fun onPlayerError(error: PlaybackException) {
            hasError = true
        }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        // Écouteurs débranchés : la vidéo se met en pause au lieu de passer sur le haut-parleur.
        p.setHandleAudioBecomingNoisy(true)
        p.volume = if (isMuted) 0f else 1f
        p.addListener(listener)
        surface?.let(p::setVideoTextureView)
        player = p
    }

    /** Charge [video] et la lance ; sans effet si c'est déjà elle. */
    fun open(video: MediaPhoto) {
        if (uri == video.uri) return
        val p = ensurePlayer()
        uri = video.uri
        hasFirstFrame = false
        hasError = false
        isEnded = false
        aspectRatio = null
        durationMillis = video.durationMillis
        positionMillis = 0L
        p.setMediaItem(MediaItem.fromUri(video.uri))
        p.prepare()
        p.playWhenReady = true
        wantsToPlay = true
    }

    /** Photo affichée : plus rien ne joue. */
    fun stop() {
        if (uri == null) return
        uri = null
        player?.run {
            stop()
            clearMediaItems()
        }
        isPlaying = false
        wantsToPlay = false
        hasFirstFrame = false
    }

    fun pause() {
        player?.pause()
    }

    fun togglePlay() {
        val p = player ?: return
        when {
            isEnded -> {
                p.seekTo(0)
                positionMillis = 0
                p.play()
            }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
    }

    /** Recule ou avance de [deltaMillis], sans sortir de la vidéo. */
    fun seekBy(deltaMillis: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMillis).coerceIn(0, durationMillis.coerceAtLeast(0))
        positionMillis = target
        isEnded = false
        p.seekTo(target)
    }

    fun toggleMute() {
        isMuted = !isMuted
        player?.volume = if (isMuted) 0f else 1f
    }

    /** Relevé de la position, à chaque image tant que la vidéo joue. */
    fun syncPosition() {
        if (isScrubbing) return
        player?.let { positionMillis = it.currentPosition.coerceAtLeast(0) }
    }

    fun beginScrub() {
        val p = player ?: return
        resumeAfterScrub = p.playWhenReady && !isEnded
        p.pause()
        // Pendant le glissé, l'image clé la plus proche : la vidéo suit le doigt sans à-coups.
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        isScrubbing = true
    }

    fun scrubTo(fraction: Float) {
        val p = player ?: return
        if (durationMillis <= 0) return
        positionMillis = (durationMillis * fraction.coerceIn(0f, 1f)).toLong()
        p.seekTo(positionMillis)
    }

    fun endScrub() {
        val p = player ?: return
        // Au relâchement, l'image exacte sous le doigt.
        p.setSeekParameters(SeekParameters.EXACT)
        p.seekTo(positionMillis)
        isScrubbing = false
        isEnded = false
        if (resumeAfterScrub) p.play()
    }

    fun attach(view: TextureView) {
        surface = view
        player?.setVideoTextureView(view)
    }

    fun detach(view: TextureView) {
        if (surface !== view) return
        player?.clearVideoTextureView(view)
        surface = null
    }

    fun release() {
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        uri = null
    }
}

@Composable
fun rememberVideoPlayback(): VideoPlayback {
    val context = LocalContext.current.applicationContext
    val playback = remember { VideoPlayback(context) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}

/**
 * Image de la vidéo en cours. Une [TextureView] plutôt qu'une SurfaceView : elle suit le zoom,
 * la glissade de fermeture et le zoom « héros » vers la grille comme n'importe quelle image.
 */
@Composable
fun VideoSurface(playback: VideoPlayback, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { TextureView(it).also(playback::attach) },
        onRelease = { playback.detach(it) },
        modifier = modifier.graphicsLayer {
            // Tant que la première image n'est pas prête, l'affiche (la vignette) reste visible.
            alpha = if (playback.hasFirstFrame) 1f else 0f
        },
    )
}


/** Hauteur des images de la pellicule de lecture. */
private val FILMSTRIP_HEIGHT = 40.dp

/**
 * Pellicule de lecture, comme dans Photos sur iPhone : les images de la vidéo défilent sous un
 * repère blanc fixe, au centre. Glisser la pellicule déplace la lecture (la vidéo suit le doigt,
 * et le temps s'affiche au-dessus) ; au relâchement, la lecture reprend si elle était en cours.
 *
 * Elle prend la place de la rangée de miniatures, dans la même capsule de verre. Sa position est
 * lue au placement seulement : son défilement pendant la lecture ne recompose rien.
 */
@Composable
fun VideoFilmstrip(
    playback: VideoPlayback,
    video: MediaPhoto,
    modifier: Modifier = Modifier,
) {
    // Position relevée à chaque image tant que la vidéo joue : la pellicule défile en continu.
    LaunchedEffect(playback, playback.isPlaying) {
        while (playback.isPlaying) {
            withFrameMillis { playback.syncPosition() }
        }
        playback.syncPosition()
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val ratio = remember(video.id) {
        ((video.displayWidth.takeIf { it > 0 } ?: 16).toFloat() / (video.displayHeight.takeIf { it > 0 } ?: 9))
            .coerceIn(0.5f, 1.8f)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Temps écoulé et durée, le temps de chercher un passage.
        AnimatedVisibility(
            visible = playback.isScrubbing,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
        ) {
            ScrubTime(playback, Modifier.padding(bottom = 8.dp))
        }
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val stripPx = constraints.maxWidth.toFloat()
                val frameHeightPx = with(density) { FILMSTRIP_HEIGHT.toPx() }
                // Assez d'images pour couvrir la pellicule sans trou, au format de la vidéo.
                val count = (stripPx / (frameHeightPx * ratio)).let { ceil(it).toInt() }.coerceIn(4, 30)
                val frames = remember(video.uri, count) { mutableStateListOf<ImageBitmap?>().apply { repeat(count) { add(null) } } }
                LaunchedEffect(video.uri, count) {
                    VideoFrames.filmstrip(context, video.uri, count, frameHeightPx.roundToInt()) { index, frame ->
                        if (index < frames.size) frames[index] = frame.asImageBitmap()
                    }
                }

                // Fenêtre de la pellicule : la vidéo y glisse de droite à gauche pendant la lecture.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(FILMSTRIP_HEIGHT)
                        .clipToBounds()
                        .pointerInput(playback, stripPx) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                val startFraction = playback.progress
                                var dragged = 0f
                                var scrubbing = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    dragged += change.position.x - change.previousPosition.x
                                    if (!scrubbing && abs(dragged) > slop) {
                                        scrubbing = true
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        playback.beginScrub()
                                    }
                                    if (scrubbing) {
                                        change.consume()
                                        // Tirer la pellicule vers la gauche avance dans la vidéo.
                                        playback.scrubTo(startFraction - dragged / stripPx)
                                    }
                                }
                                if (scrubbing) playback.endScrub()
                            }
                        },
                ) {
                    Row(
                        Modifier
                            .offset { IntOffset((stripPx / 2f - playback.progress * stripPx).roundToInt(), 0) }
                            .width(with(density) { stripPx.toDp() })
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color.Black.copy(alpha = 0.35f)),
                    ) {
                        frames.forEach { frame ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                if (frame != null) {
                                    Image(
                                        bitmap = frame,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }

                // Repère de lecture, fixe au centre, un peu plus haut que la pellicule.
                Box(
                    Modifier
                        .width(4.dp)
                        .height(FILMSTRIP_HEIGHT + 10.dp)
                        .shadow(3.dp, RoundedCornerShape(2.dp))
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White)
                )
            }
        }
    }
}

/** Temps écoulé et durée, sur une pastille de verre sombre au-dessus de la pellicule. */
@Composable
private fun ScrubTime(playback: VideoPlayback, modifier: Modifier = Modifier) {
    // Les temps ne changent qu'à chaque seconde, pas à chaque image.
    val elapsed by remember(playback) { derivedStateOf { DateLabels.duration(playback.positionMillis) } }
    val total by remember(playback) { derivedStateOf { DateLabels.duration(playback.durationMillis) } }
    Text(
        "$elapsed / $total",
        color = Color.White,
        fontFamily = Kanit,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
