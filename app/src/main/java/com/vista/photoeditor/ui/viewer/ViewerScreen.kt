package com.vista.photoeditor.ui.viewer

import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.items
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.rememberAsyncImagePainter
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.ui.components.ActionCircle
import com.vista.photoeditor.ui.components.AppleMotion
import com.vista.photoeditor.ui.components.CircleIconButton
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.GlassBackdrop
import com.vista.photoeditor.ui.components.GlassBarItem
import com.vista.photoeditor.ui.components.GlassSlidingBar
import com.vista.photoeditor.ui.components.GlassSurface
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.bouncyClickable
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.sharedPhoto
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val DOUBLE_TAP_DELAY_MS = 280L

/** Pendant la lecture d'une vidéo, délai sans toucher avant que les commandes s'effacent. */
private const val CONTROLS_HIDE_DELAY_MS = 3_000L

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private enum class Gesture { UNDECIDED, PAGER, DISMISS, TRANSFORM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    photos: List<MediaPhoto>,
    initialPhotoId: Long,
    onBack: () -> Unit,
    onEdit: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
    onDelete: (Uri) -> Unit,
    onToggleFavorite: (MediaPhoto) -> Unit,
    onOpenWith: (Uri) -> Unit,
    /** Déplace la photo dans le dossier masqué. */
    onHide: (MediaPhoto) -> Unit,
    /** Photos qui ressemblent à celle affichée, pour la rangée « Photos similaires ». */
    similarTo: suspend (MediaPhoto) -> List<MediaPhoto> = { emptyList() },
    onOpenPhoto: (Long) -> Unit = {},
    /** Photo affichée : l'album s'en sert pour se replacer au retour. */
    onPhotoShown: (Long) -> Unit = {},
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val initialPage = remember { photos.indexOfFirst { it.id == initialPhotoId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage) { photos.size }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var menu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // Plein écran (barres masquées, fond noir), zoom en cours, avancement de la glissade de fermeture.
    var immersive by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    // Lu seulement au dessin : glisser pour fermer ne recompose pas l'écran, seul le
    // franchissement du seuil (barres masquées, défilement bloqué) le fait.
    val dismissProgress = remember { mutableFloatStateOf(0f) }
    val dismissing by remember { derivedStateOf { dismissProgress.floatValue > 0.01f } }
    val current = photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)]

    // La vidéo affichée se lance d'elle-même ; passer à une photo l'arrête.
    val playback = rememberVideoPlayback()
    LaunchedEffect(current.id, current.isVideo) {
        if (current.isVideo) playback.open(current) else playback.stop()
    }
    // App en arrière-plan : la vidéo attend qu'on revienne.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }

    // Comme dans un lecteur vidéo : pendant la lecture, les commandes s'effacent au bout de
    // quelques secondes sans toucher l'écran ; une tape les fait revenir.
    var interactions by remember { mutableIntStateOf(0) }
    LaunchedEffect(current.id, playback.isPlaying, playback.isScrubbing, immersive, interactions) {
        if (current.isVideo && playback.isPlaying && !playback.isScrubbing && !immersive) {
            delay(CONTROLS_HIDE_DELAY_MS)
            immersive = true
        }
    }
    // Vidéo finie : les commandes reviennent pour la relancer.
    LaunchedEffect(playback.isEnded) { if (playback.isEnded) immersive = false }

    // Plein écran paysage à la demande ; l'app reprend son orientation libre en quittant la vidéo.
    val activity = LocalContext.current as? Activity
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(current.isVideo) {
        if (!current.isVideo) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Vidéo en plein écran (commandes masquées, ou paysage) : les barres du système s'effacent
    // aussi ; un glissé depuis le bord les fait réapparaître un instant.
    val hideSystemBars = current.isVideo && (immersive || isLandscape)
    val view = LocalView.current
    DisposableEffect(hideSystemBars, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (hideSystemBars) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler(enabled = immersive) { immersive = false }

    val latestPhotoShown by rememberUpdatedState(onPhotoShown)
    LaunchedEffect(pagerState.currentPage) {
        zoomed = false
        val shown = photos.getOrNull(pagerState.currentPage)
        shown?.let { latestPhotoShown(it.id) }
        // Sur une vidéo, la pellicule n'est pas à l'écran : elle se replacera au retour sur une photo.
        if (shown?.isVideo == false) rowState.animateScrollToItem((pagerState.currentPage - 2).coerceAtLeast(0))
    }

    val chromeVisible = !immersive && !dismissing
    // Une vidéo se regarde sur fond noir, comme dans un lecteur ; une photo, seulement en plein écran.
    val blackout = animateFloatAsState(if (immersive || current.isVideo) 1f else 0f, tween(280), label = "blackout")

    // Façon Liquid Glass : la photo occupe tout l'écran, les barres de verre flottent par-dessus.
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        // Fond du thème qui vire au noir en plein écran, et s'efface pendant la glissade de fermeture.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - dismissProgress.floatValue }
                .background(VistaColors.ScreenGradient)
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = blackout.value * (1f - dismissProgress.floatValue) }
                .background(Color.Black)
        )

        GlassBackdrop(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 20.dp,
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed && !dismissing,
            key = { photos[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val isCurrent = page == pagerState.currentPage
            ZoomablePhoto(
                photo = photos[page],
                isCurrent = isCurrent,
                video = if (isCurrent && photos[page].isVideo) playback else null,
                corner = 0.dp,
                onTap = { immersive = !immersive },
                onZoomChanged = { if (isCurrent) zoomed = it },
                onDismissProgress = { if (isCurrent) dismissProgress.floatValue = it },
                onDismiss = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = statusTop, bottom = navBottom),
            )
        }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                ScreenHeader(
                    title = "Visionneuse",
                    onBack = onBack,
                    backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    titleInGlass = true,
                    trailing = {
                        Box {
                            CircleIconButton(Icons.Outlined.MoreVert, "Plus d'options", { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (current.isFavorite) "Retirer des favoris" else "Ajouter aux favoris", fontFamily = Kanit) },
                                    onClick = { menu = false; onToggleFavorite(current) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Ouvrir avec…", fontFamily = Kanit) },
                                    onClick = { menu = false; onOpenWith(current.uri) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Photos similaires", fontFamily = Kanit) },
                                    onClick = { menu = false; showInfo = true },
                                )
                                // Le dossier masqué ne prend que des photos.
                                if (!current.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Masquer", fontFamily = Kanit) },
                                        onClick = { menu = false; onHide(current) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }

        // Vidéo : lecteur classique, bouton de lecture au centre et commandes en bas, sur fond noir.
        AnimatedVisibility(
            visible = chromeVisible && current.isVideo,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            VideoCenterControls(playback, onInteract = { interactions++ })
        }
        AnimatedVisibility(
            visible = chromeVisible && current.isVideo,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoControls(
                playback = playback,
                isLandscape = isLandscape,
                onToggleLandscape = {
                    interactions++
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                onShare = { onShare(current.uri) },
                onInfo = { showInfo = true },
                onDelete = { onDelete(current.uri) },
                onInteract = { interactions++ },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && !current.isVideo,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Pellicule des miniatures, dans une capsule de verre.
                GlassSurface(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                ) {
                    LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
                            val selected = index == pagerState.currentPage
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .then(if (selected) Modifier.border(2.dp, VistaColors.Primary, CircleShape) else Modifier)
                                    .padding(if (selected) 3.dp else 0.dp)
                                    .clip(CircleShape)
                                    .plainClickable { scope.launch { pagerState.animateScrollToPage(index) } }
                            ) {
                                PhotoImage(photo.uri, Modifier.fillMaxSize())
                                if (photo.isVideo) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                // Barre d'outils en verre, comme dans Photos sur iOS.
                // On peut glisser d'une action à l'autre : l'action sous le doigt part au relâchement.
                val actions = remember {
                    listOf(
                        GlassBarItem(Icons.Outlined.Edit, "Modifier"),
                        GlassBarItem(Icons.Outlined.IosShare, "Partager"),
                        GlassBarItem(Icons.Outlined.Info, "Informations"),
                        GlassBarItem(Icons.Outlined.Delete, "Supprimer"),
                    )
                }
                GlassSlidingBar(
                    items = actions,
                    selectedIndex = null,
                    highlightIndex = 0,
                    onSelect = { index ->
                        when (index) {
                            0 -> onEdit(current.uri)
                            1 -> onShare(current.uri)
                            2 -> showInfo = true
                            3 -> onDelete(current.uri)
                        }
                    },
                    itemWidth = 58.dp,
                    height = 60.dp,
                )
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = VistaColors.Surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(current.name, fontFamily = Kanit, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = VistaColors.Text)
                Spacer(Modifier.height(16.dp))
                InfoRow("Date", DateLabels.full(current.dateMillis))
                InfoRow("Album", current.bucketName)
                if (current.isVideo) InfoRow("Durée", DateLabels.duration(current.durationMillis))
                InfoRow("Dimensions", "${current.displayWidth} × ${current.displayHeight} px")
                InfoRow("Taille", String.format(Locale.FRENCH, "%.1f Mo", current.sizeBytes / 1_048_576f))
                InfoRow("Format", current.mimeType.substringAfter('/').uppercase())
                SimilarRow(current, similarTo) { id ->
                    showInfo = false
                    onOpenPhoto(id)
                }
            }
        }
    }
}

/**
 * Photo zoomable comme dans Photos sur iOS : pincer pour zoomer, double-tape pour zoomer
 * sur le point touché, glisser la photo zoomée pour la parcourir, glisser verticalement
 * (sans zoom) pour fermer, une tape pour masquer les barres.
 */
@Composable
private fun ZoomablePhoto(
    photo: MediaPhoto,
    isCurrent: Boolean,
    /** Lecteur, pour la vidéo affichée : son image vient se poser sur l'affiche. */
    video: VideoPlayback?,
    corner: Dp,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDismissProgress: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = video?.takeIf { it.uri == photo.uri }
    // Pour une vidéo, la vignette sert d'affiche jusqu'à la première image.
    val painter = rememberAsyncImagePainter(model = photo.uri)
    val decoded = painter.intrinsicSize
    val ratio = playing?.aspectRatio ?: if (decoded.isSpecified && decoded.width > 0f && decoded.height > 0f) {
        decoded.width / decoded.height
    } else {
        (photo.displayWidth.takeIf { it > 0 } ?: 3).toFloat() / (photo.displayHeight.takeIf { it > 0 } ?: 4)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val latestTap by rememberUpdatedState(onTap)
    val latestZoom by rememberUpdatedState(onZoomChanged)
    val latestProgress by rememberUpdatedState(onDismissProgress)
    val latestDismiss by rememberUpdatedState(onDismiss)

    // Une page qui n'est plus affichée repart à zéro.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            pan = Offset.Zero
            drag = Offset.Zero
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val fitW = if (ratio > containerW / containerH) containerW else containerH * ratio
        val fitH = if (ratio > containerW / containerH) containerW / ratio else containerH
        fun dismissProgressOf(dy: Float) = (abs(dy) / (containerH * 0.6f)).coerceIn(0f, 1f)

        fun clampPan(value: Offset, s: Float): Offset {
            val maxX = max(0f, (fitW * s - containerW) / 2f)
            val maxY = max(0f, (fitH * s - containerH) / 2f)
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        val shape = RoundedCornerShape(corner)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(photo.id, fitW, fitH, containerW, containerH) {
                    val slop = viewConfiguration.touchSlop
                    var lastTapTime = 0L
                    var lastTapPosition = Offset.Zero
                    var pendingTap: Job? = null

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                        var mode = Gesture.UNDECIDED
                        var travel = Offset.Zero
                        var maxPointers = 1
                        var upTime = down.uptimeMillis

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            event.changes.firstOrNull()?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                                upTime = it.uptimeMillis
                            }
                            if (pressed == 0) break
                            maxPointers = max(maxPointers, pressed)
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            when {
                                pressed >= 2 -> {
                                    mode = Gesture.TRANSFORM
                                    pendingTap?.cancel()
                                    scale = (scale * zoomChange).coerceIn(0.75f, MAX_ZOOM)
                                    pan = clampPan(pan + panChange, max(scale, 1f))
                                    event.changes.forEach { it.consume() }
                                }
                                mode == Gesture.TRANSFORM || scale > 1.01f -> {
                                    mode = Gesture.TRANSFORM
                                    pan = clampPan(pan + panChange, scale)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                else -> {
                                    travel += panChange
                                    if (mode == Gesture.UNDECIDED && travel.getDistance() > slop) {
                                        // Vertical : fermeture ; horizontal : on laisse défiler les photos.
                                        mode = if (abs(travel.y) > abs(travel.x)) Gesture.DISMISS else Gesture.PAGER
                                    }
                                    if (mode == Gesture.DISMISS) {
                                        drag += panChange
                                        latestProgress(dismissProgressOf(drag.y))
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }

                        when (mode) {
                            Gesture.DISMISS -> {
                                val velocity = tracker.calculateVelocity()
                                if (abs(drag.y) > containerH * 0.12f || abs(velocity.y) > 1500f) {
                                    latestDismiss()
                                } else {
                                    val start = drag
                                    scope.launch {
                                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) { t, _ ->
                                            drag = start * (1f - t)
                                            latestProgress(dismissProgressOf(drag.y))
                                        }
                                    }
                                }
                            }

                            Gesture.TRANSFORM -> {
                                if (scale < 1f) {
                                    val startScale = scale
                                    val startPan = pan
                                    scope.launch {
                                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = AppleMotion.STIFFNESS)) { t, _ ->
                                            scale = lerp(startScale, 1f, t)
                                            pan = startPan * (1f - t)
                                        }
                                    }
                                    latestZoom(false)
                                } else {
                                    latestZoom(scale > 1.01f)
                                }
                            }

                            Gesture.UNDECIDED -> if (maxPointers == 1 && upTime - down.uptimeMillis < 300) {
                                val position = down.position
                                val isDoubleTap = down.uptimeMillis - lastTapTime < DOUBLE_TAP_DELAY_MS &&
                                    (position - lastTapPosition).getDistance() < 120f
                                if (isDoubleTap) {
                                    pendingTap?.cancel()
                                    lastTapTime = 0L
                                    val startScale = scale
                                    val startPan = pan
                                    val targetScale = if (scale > 1.01f) 1f else DOUBLE_TAP_ZOOM
                                    // Le point touché se rapproche du centre.
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val targetPan = if (targetScale == 1f) Offset.Zero
                                    else clampPan((center - position) * (targetScale - 1f), targetScale)
                                    latestZoom(targetScale > 1f)
                                    scope.launch {
                                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.9f, stiffness = AppleMotion.STIFFNESS)) { t, _ ->
                                            scale = lerp(startScale, targetScale, t)
                                            pan = Offset(lerp(startPan.x, targetPan.x, t), lerp(startPan.y, targetPan.y, t))
                                        }
                                    }
                                } else {
                                    lastTapTime = down.uptimeMillis
                                    lastTapPosition = position
                                    // Une tape simple attend de savoir si une seconde tape suit.
                                    pendingTap = scope.launch {
                                        delay(DOUBLE_TAP_DELAY_MS)
                                        latestTap()
                                    }
                                }
                            }

                            Gesture.PAGER -> Unit
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(with(LocalDensity.current) { fitW.toDp() }, with(LocalDensity.current) { fitH.toDp() })
                    .graphicsLayer {
                        val dismissScale = lerp(1f, 0.72f, dismissProgressOf(drag.y))
                        scaleX = scale * dismissScale
                        scaleY = scale * dismissScale
                        translationX = pan.x + drag.x
                        translationY = pan.y + drag.y
                    }
                    .then(if (isCurrent) Modifier.sharedPhoto(photo.id, shape) else Modifier)
                    .clip(shape)
            ) {
                Image(
                    painter = painter,
                    contentDescription = photo.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(VistaColors.SurfaceSoft),
                )
                if (playing != null) {
                    VideoSurface(playing, Modifier.fillMaxSize())
                    if (playing.hasError) {
                        Text(
                            "Lecture impossible",
                            color = Color.White,
                            fontFamily = Kanit,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Bouton de la barre d'outils en verre ; [highlighted] le met en avant en violet. */
@Composable
private fun ToolbarButton(icon: ImageVector, description: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(if (highlighted) Modifier.background(VistaColors.Primary) else Modifier)
            .bouncyClickable(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (highlighted) VistaColors.OnPrimary else VistaColors.Text,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Rangée des photos qui ressemblent à [photo] ; rien si elle n'en a pas (ou pas encore analysée). */
@Composable
private fun SimilarRow(photo: MediaPhoto, similarTo: suspend (MediaPhoto) -> List<MediaPhoto>, onOpen: (Long) -> Unit) {
    val similar by produceState<List<MediaPhoto>?>(null, photo.id) { value = similarTo(photo) }
    val list = similar ?: return
    Spacer(Modifier.height(20.dp))
    Text("Photos similaires", fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = VistaColors.Text)
    Spacer(Modifier.height(10.dp))
    if (list.isEmpty()) {
        Text("Aucune photo ne ressemble à celle-ci.", fontFamily = Kanit, fontSize = 13.sp, color = VistaColors.Muted)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { p ->
            PhotoImage(
                p.uri,
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .plainClickable { onOpen(p.id) },
                contentDescription = p.name,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 7.dp)) {
        Text(label, fontFamily = Kanit, color = VistaColors.Muted, fontSize = 15.sp, modifier = Modifier.width(110.dp))
        Text(value, fontFamily = Kanit, color = VistaColors.Text, fontSize = 15.sp)
    }
}
