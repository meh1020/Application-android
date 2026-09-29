package com.vista.photoeditor.ui.explore

import androidx.compose.material.icons.outlined.Lock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.data.Album
import com.vista.photoeditor.data.GalleryViewModel
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlin.math.roundToInt

/**
 * Écran « Explorer » : la galerie se range toute seule. Les photos analysées par la
 * reconnaissance d'images embarquée alimentent des souvenirs (les moments passés ensemble) et
 * des rayons par sujet (Animaux, Nature, Repas…). Rien ne sort du téléphone.
 */
@Composable
fun ExploreScreen(
    gallery: GalleryViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenHidden: () -> Unit,
) {
    val index = gallery.searchIndex
    // L'analyse démarre à l'ouverture et reprend là où elle s'était arrêtée.
    LaunchedEffect(gallery.photos) { index.ensureIndexed(gallery.photos) }

    // Recalculés à chaque lot analysé : les rayons se remplissent sous les yeux.
    val memories = remember(gallery.photos, index.version) { gallery.memories() }
    val categories = remember(gallery.photos, index.version, gallery.corrections.version) { gallery.categories() }
    val duplicates by produceState(emptyList<List<MediaPhoto>>(), gallery.photos, index.version) {
        value = gallery.duplicates()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
            .systemBarsPadding()
    ) {
        ScreenHeader("Explorer", onBack, Icons.AutoMirrored.Filled.KeyboardArrowLeft)

        if (index.isIndexing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), color = VistaColors.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Analyse des photos… ${(index.progress * 100).roundToInt()} %",
                    fontFamily = Kanit,
                    fontSize = 12.sp,
                    color = VistaColors.Muted,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (duplicates.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { DuplicatesCard(duplicates, onOpenDuplicates) }
            }

            if (memories.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Souvenirs") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(memories, key = { it.key }) { memory ->
                            MemoryCard(memory) { onOpenAlbum(memory.key) }
                        }
                    }
                }
            }

            if (categories.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Catégories") }
                items(categories, key = { it.key }) { category ->
                    CategoryCard(category) { onOpenAlbum(category.key) }
                }
            }

            // Le dossier masqué, en bas, sans rien dire de son contenu.
            item(span = { GridItemSpan(maxLineSpan) }) { HiddenFolderCard(onOpenHidden) }

            if (memories.isEmpty() && categories.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (index.isIndexing) {
                            "Vos photos sont en cours d'analyse. Les souvenirs et les catégories " +
                                "apparaîtront au fur et à mesure."
                        } else {
                            "Rien à ranger pour l'instant : ajoutez des photos et elles seront " +
                                "classées automatiquement."
                        },
                        fontFamily = Kanit,
                        fontSize = 14.sp,
                        color = VistaColors.Muted,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }
}

/** Invitation à faire de la place : séries de photos presque identiques à trier. */
@Composable
private fun DuplicatesCard(series: List<List<MediaPhoto>>, onClick: () -> Unit) {
    val extra = series.sumOf { it.size - 1 }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(VistaColors.Surface)
            .plainClickable(onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp)) {
            // Deux vignettes décalées : l'idée d'une série.
            series.first().getOrNull(1)?.let {
                PhotoImage(it.uri, Modifier.size(52.dp).align(Alignment.BottomEnd).clip(RoundedCornerShape(12.dp)))
            }
            PhotoImage(series.first().first().uri, Modifier.size(52.dp).align(Alignment.TopStart).clip(RoundedCornerShape(12.dp)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Doublons et rafales", fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = VistaColors.Text)
            Text(
                "${series.size} série${if (series.size > 1) "s" else ""} · $extra photo${if (extra > 1) "s" else ""} en trop",
                fontFamily = Kanit,
                fontSize = 13.sp,
                color = VistaColors.Muted,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = VistaColors.Muted)
    }
}

@Composable
private fun HiddenFolderCard(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(VistaColors.Surface)
            .plainClickable(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = VistaColors.Primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Dossier masqué", fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = VistaColors.Text)
            Text("Protégé par empreinte ou code", fontFamily = Kanit, fontSize = 13.sp, color = VistaColors.Muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = VistaColors.Muted)
    }
}

/** Grande carte d'un moment : photo de couverture, titre trouvé par l'IA, nombre de photos. */
@Composable
private fun MemoryCard(memory: Album, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 160.dp, height = 210.dp)
            .clip(RoundedCornerShape(22.dp))
            .plainClickable(onClick)
    ) {
        PhotoImage(memory.cover?.uri, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xAA000000)))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                memory.name,
                color = Color.White,
                fontFamily = Kanit,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 2,
            )
            Text(
                "${memory.count} photos",
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = Kanit,
                fontSize = 12.sp,
            )
        }
    }
}

/** Tuile d'un rayon : couverture, nom du sujet et nombre de photos reconnues. */
@Composable
private fun CategoryCard(category: Album, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1.15f)
            .clip(RoundedCornerShape(20.dp))
            .plainClickable(onClick)
    ) {
        PhotoImage(category.cover?.uri, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xAA000000)))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        ) {
            Text(
                category.name,
                color = Color.White,
                fontFamily = Kanit,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
            )
            Text(
                "${category.count} photos",
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = Kanit,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontFamily = Kanit,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        color = VistaColors.Text,
        modifier = Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .width(300.dp)
            .height(28.dp),
    )
}
