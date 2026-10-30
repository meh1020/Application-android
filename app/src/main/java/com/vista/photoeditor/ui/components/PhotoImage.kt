package com.vista.photoeditor.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Photo (ou vignette de vidéo) de la galerie chargée et mise en cache par Coil. */
@Composable
fun PhotoImage(
    uri: Uri?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    if (uri == null) {
        Box(modifier.background(VistaColors.SurfaceSoft))
    } else {
        AsyncImage(
            model = uri,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier.background(VistaColors.SurfaceSoft),
        )
    }
}

/**
 * Durée posée sur la vignette d'une vidéo, comme dans Photos sur iPhone : en blanc dans le coin,
 * sans pastille, avec une ombre douce qui la garde lisible sur une image claire.
 */
@Composable
fun VideoBadge(durationMillis: Long, modifier: Modifier = Modifier, padding: Dp = 8.dp) {
    Text(
        DateLabels.duration(durationMillis),
        color = Color.White,
        fontFamily = Kanit,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        maxLines = 1,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 6f)),
        modifier = modifier
            .padding(padding)
            .semantics { contentDescription = "Vidéo, " + DateLabels.duration(durationMillis) },
    )
}

object DateLabels {
    private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH)
    private val fullFormat = DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm", Locale.FRENCH)

    private fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

    fun day(millis: Long): String = date(millis).format(dayFormat)
    fun month(millis: Long): String = date(millis).format(monthFormat)
    fun full(millis: Long): String = date(millis).format(fullFormat)
    fun isToday(millis: Long) = date(millis).toLocalDate() == LocalDate.now()
    fun sameMonth(a: Long, b: Long) = date(a).let { x -> date(b).let { y -> x.year == y.year && x.month == y.month } }

    /**
     * Jours entamés avant la suppression définitive d'un élément de la corbeille (au moins 1), ou
     * null si Android n'en a pas fixé la date.
     */
    fun daysLeft(expiresMillis: Long, nowMillis: Long): Int? {
        if (expiresMillis <= 0) return null
        val day = 24 * 60 * 60 * 1000L
        return ((expiresMillis - nowMillis + day - 1) / day).toInt().coerceAtLeast(1)
    }

    /** Durée d'une vidéo : « 0:42 », « 12:05 », « 1:02:33 ». */
    fun duration(millis: Long): String {
        val total = (millis.coerceAtLeast(0) + 500) / 1000
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.FRENCH, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.FRENCH, "%d:%02d", minutes, seconds)
    }
}
