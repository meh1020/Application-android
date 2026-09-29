package com.vista.photoeditor.ui.duplicates

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.data.DuplicateFinder
import com.vista.photoeditor.data.GalleryViewModel
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.data.MediaRepository
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import java.util.Locale

/**
 * Doublons et rafales : chaque série garde sa photo la plus nette ; les autres sont proposées à la
 * corbeille. Un toucher change le choix, un appui long ouvre la photo. Rien n'est supprimé sans
 * la confirmation du système, et la corbeille garde les photos 30 jours.
 */
@Composable
fun DuplicatesScreen(
    gallery: GalleryViewModel,
    onBack: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onTrash: (List<Uri>) -> Unit,
) {
    val index = gallery.searchIndex
    LaunchedEffect(gallery.photos) { index.ensureIndexed(gallery.photos) }
    val series by produceState<List<List<MediaPhoto>>?>(null, gallery.photos, index.version) {
        value = gallery.duplicates()
    }
    // Netteté mesurée au fil de l'eau, série après série.
    val sharpness = remember { mutableStateMapOf<Long, Float>() }
    LaunchedEffect(series) {
        series.orEmpty().flatten().forEach { photo ->
            if (photo.id !in sharpness) sharpness[photo.id] = gallery.sharpnessOf(photo)
        }
    }
    // Choix de l'utilisateur, prioritaire sur la proposition : vrai = à la corbeille.
    val choices = remember { mutableStateMapOf<Long, Boolean>() }

    val all = series.orEmpty()
    val keepers = all.associate { members ->
        val measured = members.all { it.id in sharpness }
        members.first().id to if (measured) {
            DuplicateFinder.keeperOf(members, { sharpness.getValue(it.id) }, { it.width.toLong() * it.height })
        } else null
    }
    fun toTrash(members: List<MediaPhoto>, photo: MediaPhoto): Boolean {
        choices[photo.id]?.let { return it }
        val keeper = keepers[members.first().id] ?: return false
        // Favoris et créations de l'éditeur ont été gardés exprès : jamais proposés d'office.
        return photo.id != keeper.id && !photo.isFavorite && !photo.isCreation
    }
    val selected = all.flatMap { members -> members.filter { toTrash(members, it) } }
    val ready = all.isNotEmpty() && keepers.values.all { it != null }

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Doublons et rafales", onBack, Icons.AutoMirrored.Filled.KeyboardArrowLeft)
            when {
                series == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VistaColors.Primary, strokeWidth = 2.dp)
                }
                all.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (index.isIndexing) "Vos photos sont en cours d'analyse : les doublons apparaîtront au fur et à mesure."
                        else "Aucun doublon ni rafale : votre galerie est déjà bien rangée.",
                        fontFamily = Kanit,
                        fontSize = 16.sp,
                        color = VistaColors.Muted,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item {
                        Text(
                            "La photo la plus nette de chaque série est gardée. Touchez une photo pour " +
                                "changer, appuyez longuement pour la voir en grand.",
                            fontFamily = Kanit,
                            fontSize = 14.sp,
                            color = VistaColors.Muted,
                        )
                    }
                    items(all, key = { it.first().id }) { members ->
                        SeriesCard(
                            members = members,
                            keeper = keepers[members.first().id],
                            isTrashed = { toTrash(members, it) },
                            onToggle = { photo -> choices[photo.id] = !toTrash(members, photo) },
                            onOpen = onOpenPhoto,
                        )
                    }
                }
            }
        }

        if (all.isNotEmpty()) {
            val shape = RoundedCornerShape(32.dp)
            val enabled = ready && selected.isNotEmpty()
            val color = if (enabled) VistaColors.Danger else VistaColors.Muted
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .softShadow(shape, 14.dp)
                    .clip(shape)
                    .background(VistaColors.Surface)
                    .plainClickable {
                        // Les choix restent : si la confirmation du système est refusée, rien n'est perdu.
                        if (enabled) onTrash(selected.map { it.uri })
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!ready) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = VistaColors.Primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Recherche de la plus nette…", fontFamily = Kanit, fontSize = 14.sp, color = VistaColors.Muted)
                } else {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    val megabytes = selected.sumOf { it.sizeBytes } / 1_048_576f
                    Text(
                        if (selected.isEmpty()) "Aucune photo à mettre à la corbeille"
                        else "Mettre ${selected.size} photo${if (selected.size > 1) "s" else ""} à la corbeille · " +
                            String.format(Locale.FRENCH, "%.1f Mo", megabytes),
                        fontFamily = Kanit,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = color,
                    )
                }
            }
        }
    }
}

private val MediaPhoto.isCreation get() = bucketName == MediaRepository.CREATIONS_NAME

@Composable
private fun SeriesCard(
    members: List<MediaPhoto>,
    keeper: MediaPhoto?,
    isTrashed: (MediaPhoto) -> Boolean,
    onToggle: (MediaPhoto) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val span = members.last().dateMillis - members.first().dateMillis
    // Prises en quelques minutes : une rafale ; sinon, la même photo enregistrée plusieurs fois.
    val kind = if (span <= DuplicateFinder.BURST_GAP_MILLIS * 5) "Rafale" else "Doublons"
    Column {
        Text(
            "$kind · ${members.size} photos · ${DateLabels.day(members.last().dateMillis)}",
            fontFamily = Kanit,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = VistaColors.Text,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(members, key = { it.id }) { photo ->
                SeriesPhoto(
                    photo = photo,
                    isKeeper = photo.id == keeper?.id,
                    trashed = isTrashed(photo),
                    onToggle = { onToggle(photo) },
                    onOpen = { onOpen(photo.id) },
                )
            }
        }
    }
}

@Composable
private fun SeriesPhoto(photo: MediaPhoto, isKeeper: Boolean, trashed: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val dim by animateFloatAsState(if (trashed) 0.45f else 0f, tween(200), label = "trashDim")
    Box(
        Modifier
            .size(118.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(photo.id) {
                detectTapGestures(onTap = { onToggle() }, onLongPress = { onOpen() })
            }
    ) {
        PhotoImage(photo.uri, Modifier.fillMaxSize(), contentDescription = photo.name)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        if (isKeeper) {
            Text(
                "La plus nette",
                color = Color.White,
                fontFamily = Kanit,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(VistaColors.Primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (photo.isFavorite || photo.isCreation) {
            Icon(
                if (photo.isFavorite) Icons.Filled.Favorite else Icons.Filled.AutoFixHigh,
                contentDescription = if (photo.isFavorite) "Favori" else "Création Vista",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(16.dp),
            )
        }
        // Coche : photo gardée ; corbeille : photo proposée à la suppression.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (trashed) VistaColors.Danger else Color.White.copy(alpha = 0.85f))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (trashed) Icons.Outlined.Delete else Icons.Filled.Check,
                contentDescription = if (trashed) "À mettre à la corbeille" else "Gardée",
                tint = if (trashed) Color.White else VistaColors.Primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
