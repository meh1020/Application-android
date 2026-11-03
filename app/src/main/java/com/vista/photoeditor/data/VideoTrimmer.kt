package com.vista.photoeditor.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Découpage d'une vidéo (début et fin) **sans réencodage** : les images et le son sont recopiés
 * tels quels dans un nouveau fichier MP4, ce qui est rapide (quelques secondes pour une minute) et
 * ne perd aucune qualité. L'original n'est pas touché ; la copie rejoint l'album « Créations Vista ».
 *
 * Revers de la copie sans réencodage : une vidéo ne peut commencer que sur une image clé (une
 * image complète, toutes les secondes environ sur un téléphone). Le début est donc avancé à
 * l'image clé qui précède le point choisi ; la fin, elle, tombe où on l'a choisie.
 *
 * Seuls les formats que l'enregistreur MP4 d'Android sait écrire passent (H.264, H.265, AAC… :
 * ceux des appareils photo de téléphone). Pour les autres, [UnsupportedVideoException].
 */
object VideoTrimmer {

    /** Vidéo dans un format que l'enregistreur MP4 d'Android ne sait pas recopier. */
    class UnsupportedVideoException(cause: Throwable? = null) : Exception("format non pris en charge", cause)

    /**
     * Copie de [source] entre [startMillis] et [endMillis], enregistrée dans la galerie à la date
     * [dateMillis] (celle de l'original : la copie se range à côté de lui). [onProgress] reçoit
     * l'avancée, de 0 à 1. Renvoie l'adresse de la copie.
     */
    @SuppressLint("WrongConstant")
    suspend fun trim(
        context: Context,
        source: Uri,
        startMillis: Long,
        endMillis: Long,
        dateMillis: Long,
        onProgress: (Float) -> Unit = {},
    ): Uri = withContext(Dispatchers.IO) {
        require(endMillis > startMillis) { "fin avant le début" }
        val resolver = context.contentResolver
        val extractor = MediaExtractor()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VISTA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // Même dossier que les photos de l'éditeur : un seul album « Créations Vista ».
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            if (dateMillis > 0) put(MediaStore.Video.Media.DATE_TAKEN, dateMillis)
        }
        var target: Uri? = null
        try {
            extractor.setDataSource(context, source, null)

            // Pistes recopiées : l'image et le son. Les autres (sous-titres, données de capteurs…) restent.
            val tracks = (0 until extractor.trackCount).mapNotNull { i ->
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) Track(i, format, mime.startsWith("video/")) else null
            }
            val video = tracks.firstOrNull { it.isVideo } ?: throw UnsupportedVideoException()

            // Début : l'image clé qui précède le point choisi (seule la piste vidéo compte ici).
            extractor.selectTrack(video.index)
            extractor.seekTo(startMillis * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseUs = extractor.sampleTime.takeIf { it >= 0 } ?: throw UnsupportedVideoException()
            tracks.filter { !it.isVideo }.forEach { extractor.selectTrack(it.index) }
            extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = endMillis * 1000

            target = resolver.insert(MediaRepository.videoCollection, values) ?: error("Impossible de créer le fichier")
            resolver.openFileDescriptor(target, "rw")!!.use { descriptor ->
                val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var started = false
                var complete = false
                try {
                    val outputs = HashMap<Int, Int>()
                    try {
                        tracks.forEach { outputs[it.index] = muxer.addTrack(it.format) }
                    } catch (e: Exception) {
                        throw UnsupportedVideoException(e)
                    }
                    muxer.setOrientationHint(rotationOf(context, source))
                    muxer.start()
                    started = true

                    val capacity = tracks.maxOf { t ->
                        if (t.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) t.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                    }.coerceAtLeast(MIN_BUFFER)
                    val buffer = ByteBuffer.allocateDirect(capacity)
                    val info = MediaCodec.BufferInfo()
                    val finished = HashSet<Int>()
                    var written = 0
                    while (finished.size < tracks.size) {
                        ensureActive()
                        val track = extractor.sampleTrackIndex
                        if (track < 0) break
                        val time = extractor.sampleTime
                        when {
                            // Fin atteinte pour cette piste : ses échantillons suivants sont ignorés.
                            track in finished -> Unit
                            time > endUs -> finished += track
                            // Son d'avant la première image : il n'aurait rien à accompagner.
                            time < baseUs -> Unit
                            else -> {
                                buffer.clear()
                                val size = extractor.readSampleData(buffer, 0)
                                if (size >= 0) {
                                    info.set(0, size, time - baseUs, flagsOf(extractor.sampleFlags))
                                    muxer.writeSampleData(outputs.getValue(track), buffer, info)
                                    written++
                                    if (track == video.index) onProgress(((time - baseUs).toFloat() / (endUs - baseUs)).coerceIn(0f, 1f))
                                }
                            }
                        }
                        if (!extractor.advance()) break
                    }
                    if (written == 0) throw UnsupportedVideoException()
                    complete = true
                } finally {
                    try {
                        if (started) muxer.stop()
                    } catch (e: Exception) {
                        // Après une erreur, l'échec de stop() ne dit rien de plus : l'erreur d'origine
                        // remonte et la copie est effacée plus bas. Sinon, la copie est inutilisable.
                        if (complete) throw e
                    } finally {
                        muxer.release()
                    }
                }
            }
            resolver.update(
                target,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    if (dateMillis > 0) put(MediaStore.Video.Media.DATE_TAKEN, dateMillis)
                },
                null,
                null,
            )
            onProgress(1f)
            target
        } catch (e: Throwable) {
            target?.let { runCatching { resolver.delete(it, null, null) } }
            throw e
        } finally {
            extractor.release()
        }
    }

    private class Track(val index: Int, val format: MediaFormat, val isVideo: Boolean)

    /** Rotation enregistrée dans la vidéo (portrait filmé en paysage), à reporter sur la copie. */
    private fun rotationOf(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    /** Drapeaux d'un échantillon lu, traduits pour l'enregistreur. */
    private fun flagsOf(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        return flags
    }

    private const val ALBUM = "Vista"

    /** Tampon d'un échantillon : une image clé en 4K tient largement dans 8 Mo. */
    private const val MIN_BUFFER = 8 * 1024 * 1024
}
