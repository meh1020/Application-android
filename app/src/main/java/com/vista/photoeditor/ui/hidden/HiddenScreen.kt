package com.vista.photoeditor.ui.hidden

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vista.photoeditor.data.HiddenPhoto
import com.vista.photoeditor.data.HiddenVault
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlinx.coroutines.launch

/**
 * Dossier masqué, ouvert après l'empreinte ou le code. Il se referme dès que l'app passe en
 * arrière-plan, et ni capture d'écran ni aperçu des apps récentes ne montrent son contenu.
 */
@Composable
fun HiddenScreen(
    vault: HiddenVault,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onLock() }
    LaunchedEffect(Unit) { vault.load() }

    val scope = rememberCoroutineScope()
    val photos = vault.photos
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var viewing by remember { mutableStateOf<HiddenPhoto?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val selected = selection.filter { id -> photos.any { it.id == id } }.toSet()

    BackHandler(enabled = selected.isNotEmpty() || viewing != null) {
        if (viewing != null) viewing = null else selection = emptySet()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Dossier masqué", onBack, Icons.AutoMirrored.Filled.KeyboardArrowLeft)
            Text(
                "Chiffrées sur ce téléphone, invisibles des autres apps. Appui long pour sélectionner.",
                fontFamily = Kanit,
                fontSize = 14.sp,
                color = VistaColors.Muted,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = VistaColors.Muted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Aucune photo masquée. Dans un album ou la visionneuse, choisissez « Masquer ».",
                            fontFamily = Kanit,
                            fontSize = 16.sp,
                            color = VistaColors.Muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(photos, key = { it.id }) { photo ->
                        HiddenTile(
                            vault = vault,
                            photo = photo,
                            selecting = selected.isNotEmpty(),
                            selected = photo.id in selected,
                            onTap = {
                                if (selected.isEmpty()) viewing = photo
                                else selection = if (photo.id in selected) selection - photo.id else selection + photo.id
                            },
                            onLongPress = { selection = selection + photo.id },
                        )
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            val shape = RoundedCornerShape(32.dp)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .softShadow(shape, 14.dp)
                    .clip(shape)
                    .background(VistaColors.Surface)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction(Icons.Outlined.Visibility, "Restaurer (${selected.size})", VistaColors.Primary, Modifier.weight(1f)) {
                    if (busy) return@BarAction
                    busy = true
                    scope.launch {
                        val restored = vault.restore(selected)
                        selection = emptySet()
                        busy = false
                        onMessage(if (restored == selected.size) "Photos remises dans la galerie" else "$restored photo(s) sur ${selected.size} restaurée(s)")
                    }
                }
                Spacer(Modifier.width(1.dp).height(32.dp).background(VistaColors.Outline))
                BarAction(Icons.Outlined.DeleteForever, "Supprimer (${selected.size})", VistaColors.Danger, Modifier.weight(1f)) {
                    confirmDelete = true
                }
            }
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VistaColors.Primary, strokeWidth = 2.dp)
            }
        }

        viewing?.let { photo -> HiddenPhotoView(vault, photo) { viewing = null } }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer définitivement ?", fontFamily = Kanit) },
            text = {
                Text(
                    "${selected.size} photo(s) seront effacées sans passer par la corbeille. Impossible de les récupérer.",
                    fontFamily = Kanit,
                    color = VistaColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        vault.delete(selected)
                        selection = emptySet()
                    }
                }) { Text("Supprimer", fontFamily = Kanit, color = VistaColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annuler", fontFamily = Kanit) }
            },
        )
    }
}

@Composable
private fun HiddenTile(
    vault: HiddenVault,
    photo: HiddenPhoto,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(null, photo.id) { value = vault.thumbnail(photo) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(VistaColors.SurfaceSoft)
            .pointerInput(photo.id) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (selected) Box(Modifier.fillMaxSize().background(VistaColors.Primary.copy(alpha = 0.25f)))
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) VistaColors.Primary else Color.White.copy(alpha = 0.5f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** Photo en grand, sur fond noir ; toucher pour revenir à la grille. */
@Composable
private fun HiddenPhotoView(vault: HiddenVault, photo: HiddenPhoto, onClose: () -> Unit) {
    val image by produceState<Bitmap?>(null, photo.id) { value = vault.image(photo, VIEW_SIDE) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .plainClickable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        } else {
            Image(bitmap.asImageBitmap(), contentDescription = photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Text(
            photo.name,
            color = Color.White.copy(alpha = 0.8f),
            fontFamily = Kanit,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
        )
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxSize()
            .plainClickable(onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = Kanit, fontWeight = FontWeight.Medium, color = color, fontSize = 14.sp)
    }
}

/** Côté de la photo affichée en grand : l'écran, pas plus. */
private const val VIEW_SIDE = 2048
