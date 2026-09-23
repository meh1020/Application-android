package com.vista.photoeditor.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.vista.photoeditor.ui.theme.VistaColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Photo de la galerie chargée (et mise en cache) par Coil. */
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
}
