package com.vista.photoeditor.ui.search

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.data.GalleryViewModel
import com.vista.photoeditor.data.PhotoLabels
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.liquidGlass
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.sharedPhoto
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SearchScreen(
    gallery: GalleryViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenPhoto: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val index = gallery.searchIndex
    // L'analyse démarre avec l'app ; si elle n'est pas finie, la recherche la reprend là où elle en est.
    LaunchedEffect(gallery.photos) { index.ensureIndexed(gallery.photos) }

    val q = PhotoLabels.normalize(query)
    val albums = remember(q, gallery.albums) {
        if (q.isEmpty()) gallery.albums else gallery.albums.filter { PhotoLabels.normalize(it.name).contains(q) }
    }
    // Calculés hors du fil de l'interface : comparer des milliers de photos au vocabulaire ne doit
    // pas figer la saisie.
    val photos by produceState(gallery.photos.take(60), q, gallery.photos, index.version) {
        value = if (q.isEmpty()) gallery.photos.take(60) else withContext(Dispatchers.Default) {
            val concepts = index.conceptsFor(q)
            // Le contenu d'abord, du plus ressemblant au moins ressemblant ; puis nom, album ou date.
            val byContent = gallery.photos
                .mapNotNull { p -> index.relevance(p.id, concepts)?.let { p to it } }
                .sortedByDescending { it.second }
                .map { it.first }
            val seen = byContent.mapTo(HashSet()) { it.id }
            // Puis les photos de la catégorie d'Explorer que désigne la recherche (« livre » →
            // « Livres & lecture ») : la recherche retrouve au moins ce qu'Explorer montre, retraits
            // manuels compris.
            val byCategory = gallery.categories()
                .filter { PhotoLabels.isMainSubject(it.name, q) }
                .flatMap { it.photos }
                .filter { seen.add(it.id) }
            byContent + byCategory + gallery.photos.filter { p ->
                p.id !in seen && (
                    PhotoLabels.normalize(p.name).contains(q) ||
                        PhotoLabels.normalize(p.bucketName).contains(q) ||
                        PhotoLabels.normalize(DateLabels.day(p.dateMillis)).contains(q) ||
                        PhotoLabels.normalize(DateLabels.month(p.dateMillis)).contains(q)
                    )
            }
        }
    }
    val suggestions by produceState(emptyList<String>(), gallery.photos, index.version) {
        value = withContext(Dispatchers.Default) { index.suggestions(gallery.photos) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
            .systemBarsPadding()
    ) {
        ScreenHeader("Rechercher", onBack, Icons.AutoMirrored.Filled.KeyboardArrowLeft)
        val fieldShape = RoundedCornerShape(24.dp)
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Album, mois, nom de fichier…", fontFamily = Kanit) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Kanit, fontSize = 16.sp),
            shape = fieldShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = VistaColors.Primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .liquidGlass(fieldShape),
        )

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
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Albums") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.key }) { album ->
                            Box(
                                Modifier
                                    .size(width = 130.dp, height = 160.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .plainClickable { onOpenAlbum(album.key) }
                            ) {
                                PhotoImage(album.cover?.uri, Modifier.fillMaxSize())
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x99000000)))
                                )
                                Column(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(12.dp)
                                ) {
                                    Text(album.name, color = Color.White, fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
                                    Text("${album.count} photos", color = Color.White.copy(alpha = 0.85f), fontFamily = Kanit, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (q.isEmpty() && suggestions.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Dans vos photos") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { it }) { suggestion ->
                            Text(
                                suggestion,
                                fontFamily = Kanit,
                                fontSize = 13.sp,
                                color = VistaColors.Text,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(VistaColors.Surface)
                                    .plainClickable { query = suggestion }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(if (q.isEmpty()) "Récentes" else "${photos.size} résultat${if (photos.size > 1) "s" else ""}")
            }
            items(photos, key = { it.id }) { photo ->
                Box(
                    Modifier
                        .animateItem()
                        .aspectRatio(1f)
                        .sharedPhoto(photo.id, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .plainClickable { onOpenPhoto(photo.id) }
                ) {
                    PhotoImage(photo.uri, Modifier.fillMaxSize())
                }
            }
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
