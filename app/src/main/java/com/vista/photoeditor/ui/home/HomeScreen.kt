package com.vista.photoeditor.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.vista.photoeditor.data.Album
import com.vista.photoeditor.data.GalleryViewModel
import com.vista.photoeditor.ui.components.GlassLabel
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.GlassBackdrop
import com.vista.photoeditor.ui.components.GlassBarItem
import com.vista.photoeditor.ui.components.GlassSlidingBar
import com.vista.photoeditor.ui.components.PrimaryFab
import com.vista.photoeditor.ui.components.bouncyClickable
import com.vista.photoeditor.ui.components.liquidGlass
import android.net.Uri
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Brush
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors

private const val MAX_STACK = 6
private const val VISIBLE_BACK_CARDS = 3

private enum class HomeContent { PERMISSION, LOADING, EMPTY, ALBUMS }

@Composable
fun HomeScreen(
    gallery: GalleryViewModel,
    onOpenAlbum: (String) -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    onExplore: () -> Unit,
    onCamera: () -> Unit,
    onTrash: () -> Unit,
    onCreations: () -> Unit,
    onRequestPermission: () -> Unit,
    /** Onglet de la barre du bas, gardé par l'application : il survit à l'aller-retour vers un autre écran. */
    selectedTab: Int = 0,
    onTabChange: (Int) -> Unit = {},
) {
    var frontIndex by rememberSaveable { mutableIntStateOf(0) }
    val frontCover = gallery.albums.take(MAX_STACK).getOrNull(frontIndex)?.cover?.uri

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
    ) {
        // Ambiance : la couverture de l'album au premier plan, très floutée, colore tout l'écran.
        AmbientBackdrop(frontCover)
        GlassBackdrop(Modifier.fillMaxSize()) {
            HomeContent(gallery, frontIndex, { frontIndex = it }, onOpenAlbum, onSearch, onImport, onRequestPermission)
        }
        // Barre d'onglets en verre, flottante.
        BottomNav(
            selectedTab = selectedTab,
            onTabChange = onTabChange,
            onExplore = onExplore,
            onTrash = onTrash,
            onCreations = onCreations,
            onCamera = onCamera,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun HomeContent(
    gallery: GalleryViewModel,
    frontIndex: Int,
    onFrontChange: (Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 20.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CAPTUREZ\nLE MEILLEUR",
                fontFamily = Kanit,
                fontWeight = FontWeight.SemiBold,
                fontSize = 31.sp,
                lineHeight = 35.sp,
                color = VistaColors.Text,
                modifier = Modifier.weight(1f),
            )
            val searchShape = RoundedCornerShape(28.dp)
            Box(
                Modifier
                    .size(width = 56.dp, height = 74.dp)
                    .liquidGlass(searchShape)
                    .bouncyClickable(pressedScale = 0.92f, onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = "Rechercher", tint = VistaColors.Text)
            }
        }

        if (gallery.isPartialAccess) {
            Text(
                "Accès limité à une sélection de photos · Gérer",
                color = VistaColors.Primary,
                fontFamily = Kanit,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRequestPermission)
                    .background(VistaColors.PrimarySoft)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val content = when {
                !gallery.hasPermission -> HomeContent.PERMISSION
                gallery.albums.isEmpty() && gallery.isLoading -> HomeContent.LOADING
                gallery.albums.isEmpty() -> HomeContent.EMPTY
                else -> HomeContent.ALBUMS
            }
            Crossfade(targetState = content, animationSpec = tween(320), label = "homeContent") { state ->
                when (state) {
                    HomeContent.PERMISSION -> MessageCard(
                        title = "Accédez à vos souvenirs",
                        body = "Vista a besoin d'accéder à vos photos pour afficher vos albums.",
                        action = "Autoriser l'accès",
                        onAction = onRequestPermission,
                    )
                    HomeContent.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = VistaColors.Primary)
                    }
                    HomeContent.EMPTY -> MessageCard(
                        title = "Aucune photo",
                        body = "Prenez une photo ou importez-en une pour commencer.",
                        action = "Importer une photo",
                        onAction = onImport,
                    )
                    HomeContent.ALBUMS -> AlbumStack(gallery.albums, frontIndex, onFrontChange, onOpenAlbum, onImport)
                }
            }
        }

        // Place laissée à la barre d'onglets flottante.
        Spacer(
            Modifier
                .navigationBarsPadding()
                .height(112.dp)
        )
    }
}

/** Photo floutée à l'extrême derrière l'accueil : le verre en prend les couleurs. */
@Composable
private fun AmbientBackdrop(uri: Uri?) {
    val veil = VistaColors.Background
    val context = LocalContext.current
    Crossfade(targetState = uri, animationSpec = tween(700), label = "ambient") { current ->
        if (current != null) {
            // Version réduite : inutile de décoder puis flouter une photo pleine résolution.
            AsyncImage(
                model = ImageRequest.Builder(context).data(current).size(240, 240).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp)
                    .graphicsLayer { alpha = 0.6f },
            )
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to veil.copy(alpha = 0.55f),
                    0.5f to veil.copy(alpha = 0.25f),
                    1f to veil.copy(alpha = 0.5f),
                )
            )
    )
}

/** Pile d'albums : la carte du dessus s'ouvre, les cartes derrière passent au premier plan. */
@Composable
private fun AlbumStack(
    albums: List<Album>,
    frontIndex: Int,
    onFrontChange: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val shown = albums.take(MAX_STACK)
    val front = frontIndex.coerceIn(0, shown.lastIndex)
    // Le geste est installé une fois : il lit toujours la carte de devant à jour.
    val currentFront by rememberUpdatedState(front)
    val changeFront by rememberUpdatedState(onFrontChange)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(bottom = 30.dp)
            .pointerInput(shown.size) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -60f) changeFront((currentFront + 1) % shown.size)
                        if (total > 60f) changeFront((currentFront - 1 + shown.size) % shown.size)
                    },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
    ) {
        val peek = 66.dp
        val backCount = minOf(VISIBLE_BACK_CARDS, shown.size - 1)
        val cardHeight = maxHeight - peek * backCount
        val frontTop = maxHeight - cardHeight

        shown.forEachIndexed { index, album ->
            val position = (index - front + shown.size) % shown.size
            key(album.key) {
                val animated by animateFloatAsState(
                    position.toFloat(),
                    spring(dampingRatio = 0.82f, stiffness = 320f),
                    label = "stackPosition",
                )
                val depth = animated.coerceAtMost(VISIBLE_BACK_CARDS + 0.6f)
                val hidden = position > VISIBLE_BACK_CARDS
                val alpha by animateFloatAsState(if (hidden) 0f else 1f, label = "stackAlpha")
                StackCard(
                    album = album,
                    isFront = position == 0,
                    enabled = !hidden,
                    onClick = { if (position == 0) onOpen(album.key) else onFrontChange(index) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(100f - animated)
                        .offset(y = frontTop - peek * depth)
                        .fillMaxWidth(1f - depth * 0.09f)
                        .height(cardHeight)
                        .graphicsLayer { this.alpha = alpha },
                )
            }
        }
        PrimaryFab(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 29.dp)
                .zIndex(200f),
        )
    }
}

@Composable
private fun StackCard(album: Album, isFront: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .softShadow(shape, if (isFront) 18.dp else 6.dp)
            .clip(shape)
            .background(VistaColors.SurfaceSoft)
            .then(if (enabled) Modifier.bouncyClickable(pressedScale = 0.975f, onClick = onClick) else Modifier)
    ) {
        PhotoImage(
            album.cover?.uri,
            Modifier
                .fillMaxSize()
                .then(if (isFront) Modifier else Modifier.blur(3.dp)),
        )
        if (isFront) {
            GlassLabel(
                title = album.name,
                subtitle = "( ${album.count} )",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 46.dp, start = 24.dp, end = 24.dp),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f))
            )
            Text(
                album.name,
                color = Color.White,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontFamily = Kanit,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    shadow = Shadow(Color(0x66000000), Offset(0f, 3f), 10f),
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun MessageCard(title: String, body: String, action: String, onAction: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(shape, 12.dp)
            .clip(shape)
            .background(VistaColors.Surface)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(VistaColors.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = VistaColors.Primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontFamily = Kanit, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = VistaColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(body, fontFamily = Kanit, fontSize = 15.sp, color = VistaColors.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Text(
            action,
            color = VistaColors.OnPrimary,
            fontFamily = Kanit,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(CircleShape)
                .background(VistaColors.Primary)
                .clickable(onClick = onAction)
                .padding(horizontal = 26.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun BottomNav(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onExplore: () -> Unit,
    onTrash: () -> Unit,
    onCreations: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember {
        listOf(
            GlassBarItem(Icons.Outlined.Home, "Accueil"),
            GlassBarItem(Icons.Outlined.AutoAwesome, "Explorer"),
            GlassBarItem(Icons.Outlined.Delete, "Corbeille"),
            GlassBarItem(Icons.Outlined.IosShare, "Créations"),
        )
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Barre d'onglets : on glisse le doigt, la goutte suit, et l'onglet est ouvert au relâchement.
        GlassSlidingBar(
            items = items,
            selectedIndex = selectedTab,
            onSelect = { index ->
                onTabChange(index)
                when (index) {
                    1 -> onExplore()
                    2 -> onTrash()
                    3 -> onCreations()
                }
            },
            itemWidth = 64.dp,
            height = 66.dp,
            showLabels = true,
        )
        Box(
            Modifier
                .size(66.dp)
                .liquidGlass(CircleShape, tint = VistaColors.Primary.copy(alpha = 0.88f), elevation = 14.dp)
                .bouncyClickable(pressedScale = 0.92f, onClick = onCamera),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AddAPhoto, contentDescription = "Appareil photo", tint = Color.White)
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val indicatorWidth by animateDpAsState(if (selected) 20.dp else 0.dp, tween(260), label = "navIndicator")
    val tint by animateColorAsState(
        if (selected) VistaColors.Primary else VistaColors.Muted,
        tween(220),
        label = "navTint",
    )
    Column(
        Modifier
            .size(width = 52.dp, height = 60.dp)
            .bouncyClickable(pressedScale = 0.88f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = indicatorWidth, height = 4.dp)
                .clip(CircleShape)
                .background(VistaColors.Primary)
        )
        Spacer(Modifier.height(10.dp))
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.width(1.dp).height(8.dp))
    }
}
