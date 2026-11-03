package com.vista.photoeditor.ui.hidden

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.vista.photoeditor.data.ChunkedCipher
import com.vista.photoeditor.data.HiddenPhoto
import com.vista.photoeditor.data.HiddenVault
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.theme.Kanit
import kotlinx.coroutines.delay

/**
 * Source de lecture d'une vidéo du dossier masqué : le lecteur demande des octets à une position,
 * le bloc chiffré qui les contient est déchiffré à ce moment-là (voir [ChunkedCipher]). Rien de
 * la vidéo n'est jamais écrit en clair, ni sur le disque ni ailleurs qu'en mémoire, un bloc à la fois.
 */
@UnstableApi
internal class EncryptedVideoDataSource(private val openReader: () -> ChunkedCipher.Reader) : BaseDataSource(/* isNetwork = */ false) {

    private var reader: ChunkedCipher.Reader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = try {
            openReader()
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        reader = r
        if (dataSpec.position > r.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        val left = r.length - position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, left) else left
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: return C.RESULT_END_OF_INPUT
        val n = try {
            r.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: ChunkedCipher.CorruptChunkException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * Vidéo du dossier masqué en grand, sur fond noir : elle se lance d'elle-même ; une tape montre ou
 * cache les commandes (lecture, position, fermeture). L'affiche reste visible tant que la première
 * image n'est pas prête.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun HiddenVideoView(vault: HiddenVault, video: HiddenPhoto, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context.applicationContext).build() }
    var playing by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var duration by remember { mutableLongStateOf(video.durationMillis) }
    var position by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var controls by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (player.duration != C.TIME_UNSET) duration = player.duration
                // Fin de la vidéo : les commandes reviennent pour la relancer.
                if (state == Player.STATE_ENDED) controls = true
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(video.id) {
        val factory = DataSource.Factory { EncryptedVideoDataSource { vault.openVideo(video) } }
        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.parse("vista-hidden://video/${video.id}")))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    // Position relevée quelques fois par seconde pendant la lecture.
    LaunchedEffect(playing) {
        while (playing) {
            if (!scrubbing) position = player.currentPosition.coerceAtLeast(0)
            delay(200)
        }
        if (!scrubbing) position = player.currentPosition.coerceAtLeast(0)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    val poster by produceState<Bitmap?>(null, video.id) { value = vault.image(video, 1024) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .plainClickable { controls = !controls },
        contentAlignment = Alignment.Center,
    ) {
        // Au format de la vidéo, en entier à l'écran : une vidéo en hauteur prend la hauteur.
        val frameModifier = ratio?.let { Modifier.aspectRatio(it, matchHeightConstraintsFirst = it < 1f) } ?: Modifier.fillMaxSize()
        poster?.let {
            if (!firstFrame) {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        AndroidView(
            factory = { TextureView(it).also(player::setVideoTextureView) },
            onRelease = { player.clearVideoTextureView(it) },
            modifier = frameModifier.graphicsLayer { alpha = if (firstFrame) 1f else 0f },
        )
        if (failed) {
            Text(
                "Lecture impossible : la vidéo est peut-être abîmée.",
                color = Color.White,
                fontFamily = Kanit,
                fontSize = 15.sp,
                modifier = Modifier.padding(32.dp),
            )
        }

        if (controls) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = Color.White)
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        video.name,
                        color = Color.White.copy(alpha = 0.8f),
                        fontFamily = Kanit,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            when {
                                player.playbackState == Player.STATE_ENDED -> {
                                    player.seekTo(0)
                                    player.play()
                                }
                                player.playWhenReady -> player.pause()
                                else -> player.play()
                            }
                        }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Lire",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        val fraction = if (scrubbing) scrubFraction
                        else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        Slider(
                            value = fraction,
                            onValueChange = {
                                scrubbing = true
                                scrubFraction = it
                                player.seekTo((duration * it).toLong())
                            },
                            onValueChangeFinished = {
                                position = (duration * scrubFraction).toLong()
                                scrubbing = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${DateLabels.duration((duration * fraction).toLong())} / ${DateLabels.duration(duration)}",
                            color = Color.White,
                            fontFamily = Kanit,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}
