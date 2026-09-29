package com.vista.photoeditor

import com.vista.photoeditor.ui.hidden.unlock
import com.vista.photoeditor.ui.hidden.canProtect
import com.vista.photoeditor.ui.hidden.HiddenScreen
import com.vista.photoeditor.data.MediaPhoto
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.provider.Settings
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vista.photoeditor.data.GalleryViewModel
import com.vista.photoeditor.data.MediaPermissions
import com.vista.photoeditor.data.MediaRepository
import com.vista.photoeditor.data.SmartAlbums
import com.vista.photoeditor.editor.EditorViewModel
import com.vista.photoeditor.editor.ImageIO
import com.vista.photoeditor.ui.Navigator
import com.vista.photoeditor.ui.Screen
import com.vista.photoeditor.ui.album.AlbumMemory
import com.vista.photoeditor.ui.album.AlbumScreen
import com.vista.photoeditor.ui.components.AppleMotion
import com.vista.photoeditor.ui.components.GlassScope
import com.vista.photoeditor.ui.components.LocalNavAnimatedVisibilityScope
import com.vista.photoeditor.ui.components.LocalSharedTransitionScope
import com.vista.photoeditor.ui.duplicates.DuplicatesScreen
import com.vista.photoeditor.ui.editor.EditorScreen
import com.vista.photoeditor.ui.explore.ExploreScreen
import com.vista.photoeditor.ui.home.HomeScreen
import com.vista.photoeditor.ui.onboarding.OnboardingScreen
import com.vista.photoeditor.ui.search.SearchScreen
import com.vista.photoeditor.ui.theme.VistaTheme
import com.vista.photoeditor.ui.trash.TrashScreen
import com.vista.photoeditor.ui.viewer.ViewerScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val navigator: Navigator by viewModels()
    private val gallery: GalleryViewModel by viewModels()
    private val editor: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // « auto » : icônes système sombres en thème clair, claires en thème sombre.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (!navigator.isInitialized) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val incoming = if (savedInstanceState == null) intent.incomingImage() else null
            when {
                incoming != null -> {
                    navigator.reset(Screen.Home)
                    editor.open(incoming)
                    navigator.push(Screen.Editor)
                }
                prefs.getBoolean(KEY_ONBOARDED, false) -> navigator.reset(Screen.Home)
                else -> navigator.reset(Screen.Onboarding)
            }
        }

        setContent {
            VistaTheme {
                VistaApp(navigator, gallery, editor)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        preferHighestRefreshRate()
    }

    /**
     * Demande le mode d'affichage le plus rapide à résolution identique : sur un écran
     * 120 Hz, l'application s'anime à 120 images par seconde au lieu de 60.
     */
    private fun preferHighestRefreshRate() {
        runCatching {
            val current = display?.mode ?: return
            val fastest = display?.supportedModes
                ?.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                ?.maxByOrNull { it.refreshRate }
                ?: return
            if (fastest.refreshRate > current.refreshRate + 1f) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
            }
        }
    }

    private fun Intent.incomingImage(): Uri? = when (action) {
        Intent.ACTION_EDIT -> data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    companion object {
        const val PREFS = "vista"
        const val KEY_ONBOARDED = "onboarded"
    }
}

/** Onglets de la barre de l'accueil. */
private const val HOME_TAB = 0
private const val EXPLORE_TAB = 1

/** Le temps que l'accueil revienne à l'écran : la goutte glisse ensuite sous les yeux. */
private const val RETURN_SLIDE_DELAY_MILLIS = 350L

@Composable
private fun VistaApp(navigator: Navigator, gallery: GalleryViewModel, editor: EditorViewModel) {
    val context = LocalContext.current
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    val openEditor: (Uri) -> Unit = { uri ->
        editor.open(uri)
        navigator.push(Screen.Editor)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        gallery.onPermissionsChanged()
    }
    val requestPermission = { permissionLauncher.launch(MediaPermissions.request) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openEditor(uri)
    }
    val importPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var pendingCapture by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCapture
        pendingCapture = null
        if (uri != null) {
            if (success) openEditor(uri) else ImageIO.delete(context, uri)
        }
    }
    val takePhoto: () -> Unit = {
        val uri = ImageIO.createCaptureUri(context)
        if (uri == null) {
            toast("Stockage indisponible")
        } else {
            try {
                pendingCapture = uri
                camera.launch(uri)
            } catch (e: ActivityNotFoundException) {
                pendingCapture = null
                ImageIO.delete(context, uri)
                toast("Aucune application appareil photo")
            }
        }
    }

    // Confirmations système de MediaStore (corbeille, suppression, favoris).
    val mediaRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        gallery.refresh()
    }
    val launchRequest: (PendingIntent) -> Unit = { pending ->
        mediaRequest.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    // Dossier masqué : les photos sont d'abord chiffrées, puis leurs originaux supprimés de la
    // galerie (confirmation du système). Le coffre constate lui-même, d'après la galerie, ce qui a
    // été supprimé : une copie n'entre dans le dossier que si son original est bien parti.
    val scope = rememberCoroutineScope()
    val vault = gallery.hiddenVault
    val hideRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch {
            val moved = vault.settle()
            if (moved > 0) toast(if (moved > 1) "$moved photos masquées" else "Photo masquée")
            gallery.refresh()
        }
    }
    val hidePhotos: (List<MediaPhoto>) -> Unit = { photos ->
        if (!canProtect(context)) {
            toast("Définissez d'abord un verrouillage de l'écran (code ou empreinte)")
        } else {
            scope.launch {
                val originals = vault.prepare(photos)
                if (originals.isEmpty()) {
                    toast("Impossible de masquer ces photos")
                } else {
                    val request = MediaRepository.deleteRequest(context, originals)
                    hideRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }
    val openHidden: () -> Unit = {
        if (!canProtect(context)) {
            toast("Définissez d'abord un verrouillage de l'écran (code ou empreinte)")
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } else {
            unlock(context, onSuccess = { navigator.push(Screen.Hidden) }, onFailure = { toast(it) })
        }
    }

    val share: (List<Uri>) -> Unit = { uris ->
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Partager"))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gallery.onPermissionsChanged() }

    LaunchedEffect(editor.error) {
        editor.error?.let {
            toast(it)
            editor.clearError()
        }
    }

    // Mémoire des albums (défilement, tri) et dernière photo vue dans la visionneuse, par album :
    // au retour, l'album se replace sur la photo qu'on regardait.
    val albumMemories = remember { mutableMapOf<String, AlbumMemory>() }
    // Onglet choisi dans la barre de l'accueil : la goutte y reste au retour d'un autre écran.
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    val lastViewedPhoto = remember { mutableStateMapOf<String, Long>() }

    // Explorer n'est pas un écran dont on revient « dedans » : de retour sur l'accueil, la goutte
    // glisse d'« Explorer » vers « Accueil », une fois l'accueil revenu à l'écran.
    var previousScreen by remember { mutableStateOf(navigator.current) }
    LaunchedEffect(navigator.current) {
        val from = previousScreen
        previousScreen = navigator.current
        if (navigator.current == Screen.Home && from == Screen.Explore && homeTab == EXPLORE_TAB) {
            delay(RETURN_SLIDE_DELAY_MILLIS)
            homeTab = HOME_TAB
        }
    }

    BackHandler(enabled = navigator.current != Screen.Home && navigator.current != Screen.Onboarding) {
        navigator.pop()
    }

    // Les éléments partagés (photo grille ⇄ visionneuse) ont besoin d'une portée commune
    // autour de la navigation, et de la portée d'animation de chaque écran.
    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    AnimatedContent(
        targetState = navigator.current,
        transitionSpec = {
            val photoTransition = targetState is Screen.Viewer || initialState is Screen.Viewer
            when {
                // Vers ou depuis la visionneuse : simple fondu, la photo zoome depuis ou vers sa vignette.
                photoTransition -> ContentTransform(fadeIn(tween(260)), fadeOut(tween(260)), sizeTransform = null)
                // Empilement façon iOS : le nouvel écran arrive de la droite, l'ancien recule d'un tiers en s'assombrissant.
                navigator.isForward -> ContentTransform(
                    targetContentEnter = slideInHorizontally(AppleMotion.push) { it },
                    initialContentExit = slideOutHorizontally(AppleMotion.push) { -it / 3 } +
                        fadeOut(tween(350), targetAlpha = 0.5f),
                    targetContentZIndex = 1f,
                    sizeTransform = null,
                )
                else -> ContentTransform(
                    targetContentEnter = slideInHorizontally(AppleMotion.push) { -it / 3 } +
                        fadeIn(tween(350), initialAlpha = 0.5f),
                    initialContentExit = slideOutHorizontally(AppleMotion.push) { it },
                    targetContentZIndex = -1f,
                    sizeTransform = null,
                )
            }
        },
        label = "screen",
    ) { screen ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) { GlassScope {
        when (screen) {
            null, Screen.Home -> HomeScreen(
                gallery = gallery,
                selectedTab = homeTab,
                onTabChange = { homeTab = it },
                onOpenAlbum = { navigator.push(Screen.AlbumDetail(it)) },
                onSearch = { navigator.push(Screen.Search) },
                onExplore = { navigator.push(Screen.Explore) },
                onImport = importPhoto,
                onCamera = takePhoto,
                onTrash = { navigator.push(Screen.Trash) },
                onCreations = {
                    val creations = gallery.albums.find { it.name == MediaRepository.CREATIONS_NAME }
                    if (creations != null) navigator.push(Screen.AlbumDetail(creations.key))
                    else toast("Vos photos exportées apparaîtront ici")
                },
                onRequestPermission = requestPermission,
            )

            Screen.Onboarding -> OnboardingScreen(
                onStart = {
                    context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(MainActivity.KEY_ONBOARDED, true).apply()
                    navigator.reset(Screen.Home)
                    if (!gallery.hasPermission) requestPermission()
                }
            )

            is Screen.AlbumDetail -> AlbumScreen(
                album = gallery.album(screen.albumKey),
                onBack = { navigator.pop() },
                onSearch = { navigator.push(Screen.Search) },
                onOpenPhoto = { navigator.push(Screen.Viewer(screen.albumKey, it)) },
                onAdd = importPhoto,
                onShare = share,
                onDelete = { launchRequest(MediaRepository.trashRequest(context, it, trash = true)) },
                onHide = hidePhotos,
                memory = albumMemories.getOrPut(screen.albumKey) { AlbumMemory() },
                onRemoveFromAlbum = if (screen.albumKey.startsWith(SmartAlbums.CATEGORY_PREFIX)) {
                    { ids -> gallery.removeFromCategory(screen.albumKey, ids) }
                } else null,
                returnToPhotoId = lastViewedPhoto[screen.albumKey],
                onReturnHandled = { lastViewedPhoto.remove(screen.albumKey) },
            )

            is Screen.Viewer -> ViewerScreen(
                photos = gallery.album(screen.albumKey)?.photos.orEmpty(),
                initialPhotoId = screen.photoId,
                onPhotoShown = { lastViewedPhoto[screen.albumKey] = it },
                onBack = { navigator.pop() },
                onEdit = openEditor,
                onShare = { share(listOf(it)) },
                onDelete = { launchRequest(MediaRepository.trashRequest(context, listOf(it), trash = true)) },
                onToggleFavorite = { launchRequest(MediaRepository.favoriteRequest(context, listOf(it.uri), !it.isFavorite)) },
                onHide = { hidePhotos(listOf(it)) },
                onOpenWith = { uri ->
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(intent, "Ouvrir avec"))
                },
            )

            Screen.Search -> SearchScreen(
                gallery = gallery,
                onBack = { navigator.pop() },
                onOpenAlbum = { navigator.push(Screen.AlbumDetail(it)) },
                onOpenPhoto = { navigator.push(Screen.Viewer(MediaRepository.ALL_KEY, it)) },
            )

            Screen.Explore -> ExploreScreen(
                gallery = gallery,
                onBack = { navigator.pop() },
                onOpenAlbum = { navigator.push(Screen.AlbumDetail(it)) },
                onOpenDuplicates = { navigator.push(Screen.Duplicates) },
                onOpenHidden = openHidden,
            )

            Screen.Hidden -> HiddenScreen(
                vault = vault,
                onBack = { vault.lock(); navigator.pop() },
                // App en arrière-plan : le dossier se referme, il faudra se reconnaître à nouveau.
                onLock = {
                    vault.lock()
                    if (navigator.current == Screen.Hidden) navigator.pop()
                },
                onMessage = { message -> toast(message); gallery.refresh() },
            )

            Screen.Duplicates -> DuplicatesScreen(
                gallery = gallery,
                onBack = { navigator.pop() },
                onOpenPhoto = { navigator.push(Screen.Viewer(MediaRepository.ALL_KEY, it)) },
                onTrash = { launchRequest(MediaRepository.trashRequest(context, it, trash = true)) },
            )

            Screen.Trash -> TrashScreen(
                photos = gallery.trashed,
                onBack = { navigator.pop() },
                onRestore = { launchRequest(MediaRepository.trashRequest(context, it, trash = false)) },
                onDeleteForever = { launchRequest(MediaRepository.deleteRequest(context, it)) },
            )

            Screen.Editor -> EditorScreen(
                vm = editor,
                onClose = {
                    editor.close()
                    navigator.pop()
                },
            )
        }
        } }
    }
    }
    }
}
