package com.vista.photoeditor.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    var sourceUri by mutableStateOf<Uri?>(null)
        private set

    /** Image source réduite pour l'édition en temps réel. */
    var original by mutableStateOf<Bitmap?>(null)
        private set

    /** Source pivotée/retournée, sans redressement ni recadrage (outil de recadrage). */
    var transformed by mutableStateOf<Bitmap?>(null)
        private set

    /** Source pivotée, retournée, redressée et recadrée (aperçu principal). */
    var cropped by mutableStateOf<Bitmap?>(null)
        private set

    var thumbnail by mutableStateOf<Bitmap?>(null)
        private set

    var state by mutableStateOf(EditState())
        private set

    var aspect by mutableStateOf(AspectRatio.FREE)
        private set

    /** En mode libre, verrouille le rapport actuel du cadre. */
    var isAspectLocked by mutableStateOf(false)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var savedUri by mutableStateOf<Uri?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private val presetStore = EditPresetStore(application)

    var presets by mutableStateOf(presetStore.presets())
        private set

    /** Retouche copiée, réutilisable sur une autre photo. */
    var clipboard by mutableStateOf(presetStore.clipboard)
        private set

    val hasLook get() = !state.look.isNeutral

    /**
     * Dernière retouche automatique : ses valeurs, et celles qu'elle a remplacées (rendues quand on
     * l'enlève). Appliquée tant que les curseurs n'ont pas bougé depuis.
     */
    private var autoValues by mutableStateOf<Map<Adjustment, Float>?>(null)
    private var beforeAuto: Map<Adjustment, Float> = emptyMap()
    private var autoJob: Job? = null

    val isAutoApplied: Boolean
        get() = autoValues?.let { values -> AutoEnhance.ADJUSTMENTS.all { state.value(it) == values.getValue(it) } } == true

    private val history = mutableStateListOf(EditState())
    private var historyIndex by mutableIntStateOf(0)

    val canUndo get() = historyIndex > 0
    val canRedo get() = historyIndex < history.lastIndex
    val hasChanges get() = state != EditState()

    /** Rapport imposé au cadre de recadrage, en pixels. */
    val lockedRatio: Float?
        get() {
            val image = transformed ?: return null
            val w = image.width.toFloat()
            val h = image.height.toFloat()
            return aspect.resolve(w / h)
                ?: if (isAspectLocked) state.crop.width * w / (state.crop.height * h) else null
        }

    private var loadJob: Job? = null
    private var geometryJob: Job? = null
    private var requestedGeometry: Geometry? = null
    private var transformedFor: Geometry? = null

    fun open(uri: Uri) {
        close()
        sourceUri = uri
        loadJob = viewModelScope.launch {
            try {
                original = ImageIO.decode(getApplication(), uri, PREVIEW_MAX_SIDE)
                refreshGeometry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Impossible d'ouvrir cette image"
                close()
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        geometryJob?.cancel()
        autoJob?.cancel()
        autoValues = null
        sourceUri = null
        original = null
        transformed = null
        cropped = null
        thumbnail = null
        requestedGeometry = null
        transformedFor = null
        state = EditState()
        history.clear()
        history.add(state)
        historyIndex = 0
        aspect = AspectRatio.FREE
        isAspectLocked = false
        savedUri = null
    }

    /** Modification en direct (glissement), sans entrée d'historique. */
    private fun update(newState: EditState) {
        state = newState
    }

    /** Valide l'état courant (ou [newState]) dans l'historique. */
    fun commit(newState: EditState = state) {
        state = newState
        if (history[historyIndex] != newState) {
            while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
            history.add(newState)
            historyIndex = history.lastIndex
        }
        refreshGeometry()
    }

    fun undo() {
        if (!canUndo) return
        historyIndex--
        state = history[historyIndex]
        refreshGeometry()
    }

    fun redo() {
        if (!canRedo) return
        historyIndex++
        state = history[historyIndex]
        refreshGeometry()
    }

    fun savePreset(name: String) {
        val label = name.trim().ifEmpty { "Préréglage ${presets.size + 1}" }
        presets = presetStore.save(label, state.look)
    }

    fun deletePreset(id: String) {
        presets = presetStore.delete(id)
    }

    fun applyPreset(preset: EditPreset) = commit(state.withLook(preset.look))

    fun copyLook() {
        val look = state.look
        presetStore.clipboard = look
        clipboard = look
    }

    fun pasteLook() {
        clipboard?.let { commit(state.withLook(it)) }
    }

    fun selectFilter(id: String) = commit(state.copy(filterId = id, filterIntensity = 1f))

    fun setFilterIntensity(intensity: Float) = update(state.copy(filterIntensity = intensity))

    fun setAdjustment(adjustment: Adjustment, value: Float) {
        val values = if (value == 0f) state.adjustments - adjustment else state.adjustments + (adjustment to value)
        update(state.copy(adjustments = values))
    }

    /**
     * Retouche automatique (exposition, contraste, balance des blancs) calculée sur la photo telle
     * qu'elle est cadrée et filtrée, ou retrait de celle-ci si elle est appliquée. [onNothingToDo]
     * si la photo n'a besoin d'aucun réglage.
     */
    fun toggleAuto(onNothingToDo: () -> Unit) {
        if (isAutoApplied) {
            commit(state.withValues(beforeAuto))
            autoValues = null
            return
        }
        val image = cropped ?: return
        val snapshot = state
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            val values = withContext(Dispatchers.Default) {
                // Toute la photo, réduite : le cadrage compte, pas la définition.
                val scale = AUTO_SAMPLE_SIZE.toFloat() / maxOf(image.width, image.height)
                val sample = if (scale >= 1f) image else Bitmap.createScaledBitmap(
                    image,
                    (image.width * scale).toInt().coerceAtLeast(1),
                    (image.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                val pixels = IntArray(sample.width * sample.height)
                sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                AutoEnhance.compute(pixels, sample.width, snapshot)
            }
            // La photo a changé pendant le calcul : le résultat ne vaut plus.
            if (state != snapshot) return@launch
            if (values.values.all { it == 0f }) {
                onNothingToDo()
                return@launch
            }
            beforeAuto = AutoEnhance.ADJUSTMENTS.associateWith { snapshot.value(it) }
            autoValues = values
            commit(snapshot.withValues(values))
        }
    }

    private fun EditState.withValues(values: Map<Adjustment, Float>): EditState {
        var map = adjustments
        values.forEach { (adjustment, value) -> map = if (value == 0f) map - adjustment else map + (adjustment to value) }
        return copy(adjustments = map)
    }

    fun updateCrop(rect: NormRect) = update(state.copy(crop = rect))

    fun setStraighten(degrees: Float) =
        update(state.copy(straighten = degrees.coerceIn(-MAX_STRAIGHTEN_DEGREES, MAX_STRAIGHTEN_DEGREES)))

    fun rotate() {
        val s = state
        // Pivoter après un miroir équivaut à pivoter puis appliquer le miroir de l'autre axe.
        commit(
            s.copy(
                quarterTurns = (s.quarterTurns + 1) % 4,
                flipH = s.flipV,
                flipV = s.flipH,
                crop = s.crop.rotatedCw(),
            )
        )
        if (!aspect.survivesRotation) aspect = AspectRatio.FREE
    }

    // Le miroir étant appliqué avant le redressement, l'angle change de signe.
    fun flipHorizontal() = commit(
        state.copy(flipH = !state.flipH, straighten = -state.straighten, crop = state.crop.flippedHorizontally())
    )

    fun flipVertical() = commit(
        state.copy(flipV = !state.flipV, straighten = -state.straighten, crop = state.crop.flippedVertically())
    )

    fun toggleAspectLock() {
        if (aspect != AspectRatio.FREE) {
            aspect = AspectRatio.FREE
            isAspectLocked = false
        } else {
            isAspectLocked = !isAspectLocked
        }
    }

    fun resetGeometry() {
        aspect = AspectRatio.FREE
        isAspectLocked = false
        commit(state.copy(quarterTurns = 0, flipH = false, flipV = false, straighten = 0f, crop = NormRect()))
    }

    fun selectAspect(ratio: AspectRatio) {
        aspect = ratio
        isAspectLocked = ratio != AspectRatio.FREE
        val image = transformed ?: return
        val imageRatio = image.width.toFloat() / image.height
        val target = ratio.resolve(imageRatio) ?: return
        commit(state.copy(crop = NormRect.centered(target, imageRatio)))
    }

    fun save() {
        val uri = sourceUri ?: return
        if (isSaving) return
        isSaving = true
        val snapshot = state
        viewModelScope.launch {
            try {
                val output = withContext(Dispatchers.Default) {
                    val full = ImageIO.decode(getApplication(), uri, EXPORT_MAX_SIDE)
                    val g = snapshot.geometry
                    val straightened = ImageIO.straighten(ImageIO.transform(full, g), g.straighten)
                    ImageIO.render(ImageIO.crop(straightened, g.crop), snapshot.colorMatrix(), snapshot.vignette)
                }
                savedUri = ImageIO.saveToGallery(getApplication(), output, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Échec de l'enregistrement"
            } finally {
                isSaving = false
            }
        }
    }

    fun dismissSaved() {
        savedUri = null
    }

    fun clearError() {
        error = null
    }

    private fun refreshGeometry() {
        val src = original ?: return
        val geometry = state.geometry
        if (geometry == requestedGeometry) return
        requestedGeometry = geometry

        geometryJob?.cancel()
        geometryJob = viewModelScope.launch {
            val reusable = transformed?.takeIf { transformedFor?.sameTransform(geometry) == true }
            val rotated = reusable ?: withContext(Dispatchers.Default) { ImageIO.transform(src, geometry) }
            val (crop, thumb) = withContext(Dispatchers.Default) {
                val c = ImageIO.crop(ImageIO.straighten(rotated, geometry.straighten), geometry.crop)
                c to ImageIO.thumbnail(c, THUMBNAIL_SIZE)
            }
            transformed = rotated
            transformedFor = geometry
            cropped = crop
            thumbnail = thumb
        }
    }

    private companion object {
        const val PREVIEW_MAX_SIDE = 1600
        const val EXPORT_MAX_SIDE = 4096
        const val THUMBNAIL_SIZE = 180
        /** Côté de l'image analysée par la retouche automatique : assez pour ses mesures. */
        const val AUTO_SAMPLE_SIZE = 256
    }
}
