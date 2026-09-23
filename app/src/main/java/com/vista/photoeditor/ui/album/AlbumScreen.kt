package com.vista.photoeditor.ui.album

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.vista.photoeditor.data.Album
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.data.MediaRepository
import com.vista.photoeditor.ui.components.CircleIconButton
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.GlassLabel
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.PrimaryFab
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.GlassBackdrop
import com.vista.photoeditor.ui.components.GlassTopEdge
import com.vista.photoeditor.ui.components.liquidGlass
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.sharedPhoto
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors

/** Éléments de la grille placés avant les photos : la carte « Le meilleur de… » et le titre. */
private const val GRID_HEADER_ITEMS = 2

/**
 * Ce qu'un album garde pendant qu'on navigue ailleurs (visionneuse, éditeur) : sa position
 * de défilement et l'ordre de tri choisi.
 */
class AlbumMemory {
    val gridState = LazyStaggeredGridState(0, 0)
    var newestFirst by mutableStateOf(true)
}

@Composable
fun AlbumScreen(
    album: Album?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onAdd: () -> Unit,
    onShare: (List<Uri>) -> Unit,
    onDelete: (List<Uri>) -> Unit,
    memory: AlbumMemory,
    /** Dernière photo regardée dans la visionneuse, au retour de celle-ci. */
    returnToPhotoId: Long? = null,
    onReturnHandled: () -> Unit = {},
) {
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var newestFirst by memory::newestFirst
    var sortMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = selection != null) { selection = null }

    val all = album?.photos.orEmpty()
    val photos = if (newestFirst) all else all.reversed()

    // Retour de la visionneuse : si la dernière photo regardée n'est pas à l'écran, la grille s'y
    // place avant le premier affichage, pour que la photo revienne se ranger dans sa vignette.
    if (returnToPhotoId != null) {
        val index = photos.indexOfFirst { it.id == returnToPhotoId }
        SideEffect {
            if (index >= 0) {
                val item = index + GRID_HEADER_ITEMS
                val visible = memory.gridState.layoutInfo.visibleItemsInfo.any { it.index == item }
                if (!visible) memory.gridState.requestScrollToItem(item)
            }
            onReturnHandled()
        }
    }
    val showAlbumName = album?.key == MediaRepository.ALL_KEY || album?.key == MediaRepository.FAVORITES_KEY

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
    ) {
        // Les photos défilent sous l'en-tête et le bouton flottants, en verre.
        GlassBackdrop(Modifier.fillMaxSize()) {
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Cet album est vide", fontFamily = Kanit, color = VistaColors.Muted, fontSize = 16.sp)
                }
                return@GlassBackdrop
            }

            val highlight = remember(all) {
                val latest = all.first()
                val month = all.filter { DateLabels.sameMonth(it.dateMillis, latest.dateMillis) }
                Triple(month.firstOrNull { it.isFavorite } ?: month.first(), DateLabels.month(latest.dateMillis), month.size)
            }
            val hasToday = remember(all) { all.any { DateLabels.isToday(it.dateMillis) } }

            LazyVerticalStaggeredGrid(
                state = memory.gridState,
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = statusTop + 80.dp, bottom = navBottom + 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "highlight", span = StaggeredGridItemSpan.FullLine) {
                    HighlightCard(
                        photo = highlight.first,
                        title = "Le meilleur de ${highlight.second}",
                        subtitle = "${highlight.third} moments forts",
                        onClick = { onOpenPhoto(highlight.first.id) },
                    )
                }
                item(key = "section", span = StaggeredGridItemSpan.FullLine) {
                    Row(
                        Modifier.padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (hasToday) "Captures du jour" else "Captures récentes",
                            fontFamily = Kanit,
                            fontWeight = FontWeight.Medium,
                            fontSize = 19.sp,
                            color = VistaColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                        SmallCircle(Icons.Filled.Check, "Sélectionner", selected = selection != null) {
                            selection = if (selection == null) emptySet() else null
                        }
                        Spacer(Modifier.width(10.dp))
                        Box {
                            SmallCircle(Icons.Outlined.MoreVert, "Trier") { sortMenu = true }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Plus récentes d'abord", fontFamily = Kanit) },
                                    onClick = { newestFirst = true; sortMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Plus anciennes d'abord", fontFamily = Kanit) },
                                    onClick = { newestFirst = false; sortMenu = false },
                                )
                            }
                        }
                    }
                }
                items(photos, key = { photo -> photo.id }) { photo ->
                    val selected = selection?.contains(photo.id) == true
                    GridPhoto(
                        modifier = Modifier.animateItem(),
                        photo = photo,
                        label = if (showAlbumName) photo.bucketName else DateLabels.day(photo.dateMillis),
                        selecting = selection != null,
                        selected = selected,
                        onClick = {
                            val current = selection
                            if (current == null) onOpenPhoto(photo.id)
                            else selection = if (selected) current - photo.id else current + photo.id
                        },
                        onLongClick = { selection = (selection ?: emptySet()) + photo.id },
                    )
                }
            }
        }

        GlassTopEdge(statusTop + 88.dp, Modifier.align(Alignment.TopCenter))
        ScreenHeader(
            title = album?.name ?: "Album",
            onBack = onBack,
            backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            titleInGlass = true,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
            trailing = { CircleIconButton(Icons.Outlined.Search, "Rechercher", onSearch) },
        )

        val current = selection
        if (current == null) {
            PrimaryFab(
                onAdd,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            )
        } else {
            val uris = all.filter { it.id in current }.map { it.uri }
            SelectionBar(
                count = current.size,
                onShare = { if (uris.isNotEmpty()) onShare(uris) },
                onDelete = { if (uris.isNotEmpty()) { onDelete(uris); selection = null } },
                onClose = { selection = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun HighlightCard(photo: MediaPhoto, title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .softShadow(shape, 10.dp)
            .clip(shape)
            .plainClickable(onClick)
    ) {
        PhotoImage(photo.uri, Modifier.fillMaxSize())
        GlassLabel(
            title = title,
            subtitle = subtitle,
            dark = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            trailing = {
                Icon(
                    if (photo.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = Color.White,
                )
            },
        )
    }
}

@Composable
private fun GridPhoto(
    photo: MediaPhoto,
    label: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "tileScale",
    )
    // La tuile garde le format de la photo, pour que la mosaïque annonce ce que montrera la
    // visionneuse. Il vient des dimensions déjà connues (rotation comprise) : la tuile ne change
    // donc pas de taille quand l'image arrive, et la grille n'est pas réagencée pendant le défilement.
    // Les formats extrêmes (panoramas) sont adoucis.
    val ratio = remember(photo.id) {
        ((photo.displayWidth.takeIf { it > 0 } ?: 3).toFloat() /
            (photo.displayHeight.takeIf { it > 0 } ?: 4)).coerceIn(0.55f, 1.85f)
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .sharedPhoto(photo.id, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(photo.id, selecting, selected) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            }
    ) {
        PhotoImage(photo.uri, Modifier.fillMaxSize(), contentDescription = photo.name)
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color.White, fontFamily = Kanit, fontSize = 11.sp, maxLines = 1)
        }
        val overlay by animateFloatAsState(if (selected) 0.22f else 0f, tween(200), label = "tileOverlay")
        if (overlay > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(VistaColors.Primary.copy(alpha = overlay))
            )
        }
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.6f),
            exit = fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        ) {
            val checkBackground by animateColorAsState(
                if (selected) VistaColors.Primary else Color.White.copy(alpha = 0.55f),
                tween(200),
                label = "checkBackground",
            )
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(checkBackground)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun SmallCircle(icon: ImageVector, description: String, selected: Boolean = false, onClick: () -> Unit) {
    CircleIconButton(icon, description, onClick, size = 30.dp, iconSize = 16.dp, selected = selected)
}

@Composable
private fun SelectionBar(count: Int, onShare: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .fillMaxWidth()
            .height(64.dp)
            .liquidGlass(shape, elevation = 14.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(Icons.Outlined.Close, "Annuler la sélection", onClose, size = 44.dp)
        Text(
            if (count <= 1) "$count sélectionnée" else "$count sélectionnées",
            fontFamily = Kanit,
            fontWeight = FontWeight.Medium,
            color = VistaColors.Text,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        CircleIconButton(Icons.Outlined.IosShare, "Partager", onShare, size = 44.dp, enabled = count > 0)
        Spacer(Modifier.width(8.dp))
        CircleIconButton(Icons.Outlined.Delete, "Supprimer", onDelete, size = 44.dp, selected = count > 0, enabled = count > 0)
    }
}
