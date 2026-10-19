package com.vista.photoeditor.ui.viewer

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlin.math.roundToInt

/** Saut des boutons « reculer » et « avancer ». */
private const val SKIP_MILLIS = 10_000L

/**
 * Lecture des vidéos de la visionneuse, comme dans une galerie : la vidéo affichée démarre
 * d'elle-même, une seule à la fois ; le son coupé le reste d'une vidéo à l'autre.
 *
 * Le lecteur n'est créé qu'à la première vidéo : parcourir des photos ne coûte rien. L'état
 * (lecture, position, durée) est observable par l'interface ; la position se met à jour à chaque
 * image tant que la vidéo joue (voir [VideoControls]).
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

/**
 * Commandes du milieu de l'écran, comme dans un lecteur vidéo : reculer de 10 s, lecture ou
 * pause (ou « revoir » une fois la vidéo finie), avancer de 10 s.
 */
@Composable
fun VideoCenterControls(playback: VideoPlayback, onInteract: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundPlayerButton(Icons.Filled.Replay10, "Reculer de 10 secondes", 52.dp, 30.dp) {
            onInteract()
            playback.seekBy(-SKIP_MILLIS)
        }
        val playing = playback.wantsToPlay && !playback.isEnded
        RoundPlayerButton(
            icon = when {
                playback.isEnded -> Icons.Filled.Replay
                playing -> Icons.Filled.Pause
                else -> Icons.Filled.PlayArrow
            },
            description = when {
                playback.isEnded -> "Revoir"
                playing -> "Pause"
                else -> "Lire"
            },
            size = 76.dp,
            iconSize = 44.dp,
        ) {
            onInteract()
            playback.togglePlay()
        }
        RoundPlayerButton(Icons.Filled.Forward10, "Avancer de 10 secondes", 52.dp, 30.dp) {
            onInteract()
            playback.seekBy(SKIP_MILLIS)
        }
    }
}

/**
 * Commandes du bas, sur un dégradé sombre : temps écoulé, barre de progression à faire glisser,
 * durée totale ; puis le son, les actions sur la vidéo et le plein écran paysage.
 */
@Composable
fun VideoControls(
    playback: VideoPlayback,
    isLandscape: Boolean,
    onToggleLandscape: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Position relevée à chaque image tant que la vidéo joue : la barre avance en continu.
    LaunchedEffect(playback, playback.isPlaying) {
        while (playback.isPlaying) {
            withFrameMillis { playback.syncPosition() }
        }
        playback.syncPosition()
    }

    // Les temps ne changent qu'à chaque seconde, pas à chaque image.
    val elapsed by remember(playback) { derivedStateOf { DateLabels.duration(playback.positionMillis) } }
    val total by remember(playback) { derivedStateOf { DateLabels.duration(playback.durationMillis) } }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(elapsed)
            SeekBar(playback, onInteract, Modifier.weight(1f).padding(horizontal = 10.dp))
            TimeLabel(total)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                if (playback.isMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                if (playback.isMuted) "Activer le son" else "Couper le son",
            ) {
                onInteract()
                playback.toggleMute()
            }
            PlayerIconButton(Icons.Outlined.IosShare, "Partager", onShare)
            PlayerIconButton(Icons.Outlined.Info, "Informations", onInfo)
            PlayerIconButton(Icons.Outlined.Delete, "Supprimer", onDelete)
            PlayerIconButton(
                if (isLandscape) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                if (isLandscape) "Quitter le plein écran" else "Plein écran",
                onToggleLandscape,
            )
        }
    }
}

/**
 * Barre de progression : toucher ou glisser n'importe où déplace la lecture ; la poignée grossit
 * sous le doigt. La position est lue au dessin seulement : son avance ne recompose rien.
 */
@Composable
private fun SeekBar(playback: VideoPlayback, onInteract: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val thumb by animateDpAsState(if (playback.isScrubbing) 18.dp else 13.dp, label = "seekThumb")
    BoxWithConstraints(
        modifier
            .height(36.dp)
            .pointerInput(playback) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onInteract()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    playback.beginScrub()
                    playback.scrubTo(down.position.x / size.width)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        playback.scrubTo(change.position.x / size.width)
                    }
                    playback.endScrub()
                    onInteract()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = constraints.maxWidth.toFloat()
        val trackHeight = with(density) { 3.dp.toPx() }
        val played = VistaColors.Primary
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .drawBehind {
                    val top = (size.height - trackHeight) / 2f
                    val radius = CornerRadius(trackHeight / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, top), Size(size.width, trackHeight), radius)
                    drawRoundRect(played, Offset(0f, top), Size(size.width * playback.progress, trackHeight), radius)
                }
        )
        Box(
            Modifier
                .offset {
                    val half = thumb.toPx() / 2f
                    IntOffset((width * playback.progress - half).roundToInt(), 0)
                }
                .size(thumb)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontFamily = Kanit,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 38.dp),
    )
}

@Composable
private fun PlayerIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RoundPlayerButton(icon: ImageVector, description: String, size: Dp, iconSize: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}
