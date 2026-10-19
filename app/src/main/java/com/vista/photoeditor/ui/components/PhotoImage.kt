package com.vista.photoeditor.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
 * Durée posée sur la vignette d'une vidéo, comme dans Photos sur iOS : c'est ce qui la distingue
 * d'une photo dans la grille.
 */
@Composable
fun VideoBadge(durationMillis: Long, modifier: Modifier = Modifier, padding: Dp = 8.dp) {
    Row(
        modifier
            .padding(padding)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(start = 5.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Vidéo", tint = Color.White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(2.dp))
        Text(
            DateLabels.duration(durationMillis),
            color = Color.White,
            fontFamily = Kanit,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
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
