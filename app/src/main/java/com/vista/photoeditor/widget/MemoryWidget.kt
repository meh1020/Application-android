package com.vista.photoeditor.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vista.photoeditor.R
import com.vista.photoeditor.data.MediaPermissions
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.data.MediaRepository
import com.vista.photoeditor.data.MediaThumbnails
import com.vista.photoeditor.data.SmartAlbums
import com.vista.photoeditor.editor.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Widget « Il y a un an » : une photo d'un souvenir anniversaire, le même qu'Explorer met en avant
 * (voir [MemoryWidgetPicker] pour l'ordre de préférence). Toucher le widget ouvre la photo dans
 * son souvenir.
 *
 * Tout est calculé sur le téléphone, à partir de la galerie seule : les souvenirs anniversaires ne
 * dépendent pas de l'analyse MobileCLIP, le widget n'a donc pas à charger le modèle. Android le met à
 * jour toutes les 3 heures (voir `res/xml/memory_widget_info.xml`), et une nouvelle photo s'affiche.
 */
class MemoryWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val content = load(context)
        provideContent { WidgetContent(content) }
    }

    /** Ce que le widget affiche : une photo, ou un message qui ouvre l'app. */
    private sealed interface Content {
        class Photo(val bitmap: Bitmap, val title: String, val date: String, val details: String?, val open: Intent) : Content
        class Message(val title: String, val body: String, val open: Intent) : Content
    }

    private suspend fun load(context: Context): Content = withContext(Dispatchers.IO) {
        val res = context.resources
        if (!MediaPermissions.hasAccess(context)) {
            return@withContext Content.Message(
                res.getString(R.string.app_name),
                res.getString(R.string.memory_widget_no_access),
                MemoryWidgetLink.openApp(context),
            )
        }
        val empty = Content.Message(
            res.getString(R.string.memory_widget_empty_title),
            res.getString(R.string.memory_widget_empty_body),
            MemoryWidgetLink.openApp(context),
        )
        val choice = try {
            choose(MediaRepository.loadPhotos(context), System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withContext empty
        val bitmap = picture(context, choice.photo) ?: return@withContext empty

        val title = when (val years = choice.years) {
            null -> res.getString(R.string.memory_widget_memory)
            1 -> res.getString(R.string.memory_widget_one_year)
            else -> res.getQuantityString(R.plurals.memory_widget_years, years, years)
        }
        val details = if (choice.count > 1) res.getQuantityString(R.plurals.memory_widget_photos, choice.count, choice.count) else null
        Content.Photo(
            bitmap = bitmap,
            title = title,
            date = DATE_FORMAT.format(Instant.ofEpochMilli(choice.photo.dateMillis).atZone(ZoneId.systemDefault())),
            details = details,
            open = MemoryWidgetLink.intent(context, choice.albumKey, choice.photo.id),
        )
    }

    /** Mêmes souvenirs qu'Explorer : même galerie, même calcul ; seuls leurs noms de sujet manquent ici. */
    private fun choose(photos: List<MediaPhoto>, now: Long): MemoryWidgetPicker.Choice<MediaPhoto>? {
        val memories = SmartAlbums.memories(photos, { emptyList() }, emptyMap(), now).map { it.key to it.photos }
        return MemoryWidgetPicker.choose(
            memories = memories,
            photos = photos,
            dateOf = MediaPhoto::dateMillis,
            eligible = { !it.isVideo && !SmartAlbums.isScreenshot(it) },
            now = now,
        )
    }

    /**
     * Vignette qu'Android garde pour la photo (rapide, déjà redressée), à défaut le fichier réduit.
     * En mémoire normale : l'image est envoyée à l'écran d'accueil, qui ne lit pas la mémoire graphique.
     */
    private suspend fun picture(context: Context, photo: MediaPhoto): Bitmap? {
        val side = MediaThumbnails.cachedSide(context).coerceAtLeast(MIN_PICTURE_SIDE)
        return MediaThumbnails.load(context, photo.uri, side, software = true)
            ?: try {
                ImageIO.decode(context, photo.uri, side)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }

    @Composable
    private fun WidgetContent(content: Content) {
        val compact = LocalSize.current.width < WIDE.width
        when (content) {
            is Content.Photo -> Box(
                GlanceModifier.fillMaxSize().cornerRadius(CORNER).clickable(actionStartActivity(content.open)),
                contentAlignment = Alignment.BottomStart,
            ) {
                Image(
                    ImageProvider(content.bitmap),
                    contentDescription = "${content.title}, ${content.date}",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // Voile sombre en bas : le texte blanc reste lisible sur une photo claire.
                Image(
                    ImageProvider(R.drawable.widget_scrim),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Column(GlanceModifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        content.title,
                        style = TextStyle(color = WHITE, fontSize = if (compact) 16.sp else 19.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    val line = if (compact || content.details == null) content.date else "${content.date} · ${content.details}"
                    Text(line, style = TextStyle(color = WHITE_SOFT, fontSize = 12.sp), maxLines = 1)
                }
            }

            is Content.Message -> Box(
                GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(CORNER)
                    .background(VIOLET)
                    .clickable(actionStartActivity(content.open))
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column {
                    Text(
                        content.title,
                        style = TextStyle(color = WHITE, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(content.body, style = TextStyle(color = WHITE_SOFT, fontSize = 12.sp), maxLines = 3)
                }
            }
        }
    }

    private companion object {
        /** Carré de 2 × 2 cases : le titre et la date seuls. */
        val SMALL = DpSize(110.dp, 110.dp)

        /** 4 × 2 cases et plus : la date et le nombre de photos du souvenir. */
        val WIDE = DpSize(220.dp, 110.dp)

        val CORNER = 20.dp

        /** Assez pour un widget de 4 × 2 cases sur un écran dense, sans dépasser la limite d'Android. */
        const val MIN_PICTURE_SIDE = 480

        val WHITE = ColorProvider(Color.White)
        val WHITE_SOFT = ColorProvider(Color.White.copy(alpha = 0.85f))

        /** Violet de l'icône de Vista. */
        val VIOLET = ColorProvider(Color(0xFF4A1D6F))

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH)
    }
}

/** Point d'entrée déclaré dans le manifeste : Android y adresse les mises à jour du widget. */
class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()
}
