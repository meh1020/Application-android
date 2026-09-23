package com.vista.photoeditor.data

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Index de la recherche par contenu, alimenté à la demande depuis l'écran Recherche. */
    val searchIndex = SearchIndex(application)

    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounce = true)
    }

    init {
        application.contentResolver.registerContentObserver(MediaRepository.collection, true, observer)
        refresh()
    }

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
            isLoading = false
        }
    }

    fun album(key: String): Album? = when (key) {
        MediaRepository.ALL_KEY -> Album(MediaRepository.ALL_KEY, "Toutes les photos", photos)
        else -> albums.find { it.key == key }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
