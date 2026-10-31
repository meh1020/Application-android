package com.vista.photoeditor.data

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vista.photoeditor.editor.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    var hasPermission by mutableStateOf(MediaPermissions.hasAccess(application))
        private set
    var isPartialAccess by mutableStateOf(MediaPermissions.isPartial(application))
        private set
    var isLoading by mutableStateOf(false)
        private set
    var photos by mutableStateOf<List<MediaPhoto>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var trashed by mutableStateOf<List<MediaPhoto>>(emptyList())
        private set

    /** Vrai une fois la galerie lue au moins une fois : avant, des listes vides ne veulent rien dire. */
    var isLoaded by mutableStateOf(false)
        private set

    /**
     * Rayons remplis tout seuls d'après ce que l'IA a compris des photos (Personnes, Plages…).
     * Calculés en arrière-plan à chaque changement de la galerie, de l'analyse ou des corrections
     * (voir [watchSmartAlbums]) : les écrans n'ont plus qu'à les lire.
     */
    var categories by mutableStateOf<List<Album>>(emptyList())
        private set

    /** Moments regroupés tout seuls, nommés d'après ce qu'on y voit ; calculés comme [categories]. */
    var memories by mutableStateOf<List<Album>>(emptyList())
        private set

    /**
     * Galerie lue et rayons automatiques calculés d'après elle : un album retrouvé par sa clé
     * (« cat:… », « mem:… ») existe bel et bien. Une fois vrai, le reste.
     */
    var isReady by mutableStateOf(false)
        private set

    /** Index de la recherche par contenu, alimenté à la demande depuis l'écran Recherche. */
    val searchIndex = SearchIndex.get(application)

    /** Dossier masqué : photos chiffrées, retirées de la galerie. */
    val hiddenVault = HiddenVault(application)

    /** Photos retirées à la main d'une catégorie. */
    val corrections = CategoryCorrections(application)

    private var refreshJob: Job? = null
    private var indexJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounce = true)
    }

    init {
        application.contentResolver.registerContentObserver(MediaRepository.collection, true, observer)
        application.contentResolver.registerContentObserver(MediaRepository.videoCollection, true, observer)
        IndexWorker.schedule(application)
        watchSmartAlbums()
        refresh()
    }

    /**
     * Recalcule catégories et souvenirs hors du fil de l'interface : parcourir toute la galerie à
     * chaque lot analysé (50 photos) figerait sinon l'écran pendant l'analyse. Un calcul dépassé par
     * un changement plus récent est abandonné au profit du suivant.
     */
    private fun watchSmartAlbums() {
        viewModelScope.launch {
            snapshotFlow { SmartAlbumsInput(photos, isLoaded, searchIndex.version, corrections.version) }
                .collectLatest { input ->
                    val (nextCategories, nextMemories) = withContext(Dispatchers.Default) {
                        val all = input.photos
                        SmartAlbums.categories(all, searchIndex::categoriesOf, searchIndex.categoryTitles, corrections::isRemoved) to
                            SmartAlbums.memories(all, searchIndex::categoriesOf, searchIndex.memoryNames, System.currentTimeMillis())
                    }
                    categories = nextCategories
                    memories = nextMemories
                    if (input.loaded) isReady = true
                }
        }
    }

    /** Ce dont dépendent catégories et souvenirs : un changement de l'un d'eux les recalcule. */
    private data class SmartAlbumsInput(
        val photos: List<MediaPhoto>,
        val loaded: Boolean,
        val indexVersion: Int,
        val correctionsVersion: Int,
    )

    fun onPermissionsChanged() {
        hasPermission = MediaPermissions.hasAccess(getApplication())
        isPartialAccess = MediaPermissions.isPartial(getApplication())
        refresh()
    }

    fun refresh(debounce: Boolean = false) {
        if (!hasPermission) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (debounce) delay(300)
            isLoading = photos.isEmpty()
            val app = getApplication<Application>()
            val (all, bin) = withContext(Dispatchers.IO) {
                MediaRepository.loadPhotos(app) to MediaRepository.loadPhotos(app, trashed = true)
            }
            photos = all
            albums = MediaRepository.buildAlbums(all)
            trashed = bin
            isLoaded = true
            isLoading = false
            startIndexing(all)
        }
    }

    /**
     * L'analyse des photos démarre d'elle-même, en arrière-plan, peu après l'ouverture de l'app :
     * quand on ouvre Explorer ou Recherche, elle est déjà faite ou bien avancée. Le délai laisse
     * l'accueil s'afficher et s'animer sans partage du processeur.
     */
    private fun startIndexing(all: List<MediaPhoto>) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            delay(INDEX_START_DELAY_MILLIS)
            searchIndex.ensureIndexed(all)
        }
    }

    /** Retire des photos d'une catégorie (clé d'album « cat:… ») ; le classement ne les y remet plus. */
    fun removeFromCategory(albumKey: String, photoIds: List<Long>) {
        if (!albumKey.startsWith(SmartAlbums.CATEGORY_PREFIX)) return
        corrections.remove(albumKey.removePrefix(SmartAlbums.CATEGORY_PREFIX), photoIds)
    }

    private var duplicatesKey: Pair<List<MediaPhoto>, Int>? = null
    private var duplicatesCache: List<List<MediaPhoto>> = emptyList()
    private var duplicatesAt = 0L
    private val sharpness = ConcurrentHashMap<Long, Float>()

    /**
     * Doublons et rafales parmi les photos analysées (voir [DuplicateFinder]), chaque série dans
     * l'ordre des prises, les plus récentes d'abord. Recalculé quand la galerie ou l'analyse avance ;
     * pendant l'analyse, au plus toutes les [DUPLICATES_INTERVAL_MILLIS] : comparer 2 000 photos deux
     * à deux prend 0,5 s sur ordinateur (bien plus sur un téléphone), et l'analyse avance par lots
     * de 50 photos.
     */
    suspend fun duplicates(): List<List<MediaPhoto>> {
        val snapshot = photos
        val key = snapshot to searchIndex.version
        if (key == duplicatesKey) return duplicatesCache
        val now = System.currentTimeMillis()
        if (searchIndex.isIndexing && duplicatesKey?.first == snapshot && now - duplicatesAt < DUPLICATES_INTERVAL_MILLIS) {
            return duplicatesCache
        }
        val result = withContext(Dispatchers.Default) {
            val byId = snapshot.associateBy { it.id }
            // Une vidéo n'est jamais proposée comme doublon : son vecteur ne résume qu'une image.
            val candidates = snapshot.filterNot { it.isVideo }.mapNotNull { photo ->
                searchIndex.vectorOf(photo.id)?.let { vector ->
                    DuplicateFinder.Candidate(
                        photo.id, photo.dateMillis, vector,
                        canBurst = !SmartAlbums.isScreenshot(photo) && DOCUMENTS !in searchIndex.categoriesOf(photo.id),
                    )
                }
            }
            DuplicateFinder.groups(candidates).map { ids -> ids.mapNotNull(byId::get) }
        }
        duplicatesKey = key
        duplicatesCache = result
        duplicatesAt = now
        return result
    }

    /** Photos qui ressemblent à [photo] (voir [SimilarPhotos]) ; vide tant qu'elle n'est pas analysée. */
    suspend fun similarTo(photo: MediaPhoto): List<MediaPhoto> = withContext(Dispatchers.Default) {
        val target = searchIndex.vectorOf(photo.id) ?: return@withContext emptyList()
        val snapshot = photos
        val byId = snapshot.associateBy { it.id }
        val candidates = snapshot.filterNot { it.isVideo }.mapNotNull { p -> searchIndex.vectorOf(p.id)?.let { p.id to it } }
        SimilarPhotos.rank(photo.id, target, candidates).mapNotNull(byId::get)
    }

    /** Netteté de la photo (voir [Sharpness]), mesurée une fois sur une image réduite. */
    suspend fun sharpnessOf(photo: MediaPhoto): Float = sharpness[photo.id] ?: withContext(Dispatchers.Default) {
        runCatching {
            val bitmap = ImageIO.decode(getApplication(), photo.uri, SHARPNESS_SIDE)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Sharpness.score(pixels, bitmap.width)
        }.getOrDefault(0f)
    }.also { sharpness[photo.id] = it }

    fun album(key: String): Album? = when {
        key == MediaRepository.ALL_KEY -> Album(MediaRepository.ALL_KEY, "Récents", photos)
        key.startsWith(SmartAlbums.CATEGORY_PREFIX) -> categories.find { it.key == key }
        key.startsWith(SmartAlbums.MEMORY_PREFIX) -> memories.find { it.key == key }
        else -> albums.find { it.key == key }
    }

    private companion object {
        const val INDEX_START_DELAY_MILLIS = 3_000L
        const val DOCUMENTS = "documents"
        const val DUPLICATES_INTERVAL_MILLIS = 15_000L

        /** Côté de l'image qui sert à mesurer la netteté : assez pour voir un flou de bougé. */
        const val SHARPNESS_SIDE = 512
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
