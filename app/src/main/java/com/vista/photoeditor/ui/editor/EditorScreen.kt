package com.vista.photoeditor.ui.editor

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import com.vista.photoeditor.ui.components.GlassBackdrop
import com.vista.photoeditor.ui.components.GlassBarItem
import com.vista.photoeditor.ui.components.GlassSlidingBar
import com.vista.photoeditor.ui.components.liquidGlass
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.vista.photoeditor.editor.Adjustment
import com.vista.photoeditor.editor.EditPreset
import com.vista.photoeditor.editor.EditorViewModel
import com.vista.photoeditor.ui.components.CircleIconButton
import com.vista.photoeditor.ui.components.FittedImageBox
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.components.vignetteBrush
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlinx.coroutines.withTimeoutOrNull

enum class EditorTool(val label: String, val icon: ImageVector) {
    FILTERS("Filtres", Icons.Outlined.AutoAwesome),
    ADJUST("Ajuster", Icons.Outlined.Tune),
    CROP("Recadrer", Icons.Outlined.Crop),
    PRESETS("Styles", Icons.Outlined.Style),
}

@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var tool by rememberSaveable { mutableStateOf(EditorTool.CROP) }
    var selectedAdjustment by rememberSaveable { mutableStateOf(Adjustment.BRIGHTNESS) }
    var confirmExit by remember { mutableStateOf(false) }
    // Nom en cours de saisie pour un nouveau préréglage, et préréglage à supprimer.
    var presetName by remember { mutableStateOf<String?>(null) }
    var presetToDelete by remember { mutableStateOf<EditPreset?>(null) }

    val state = vm.state
    val colorMatrix = remember(state) { ColorMatrix(state.colorMatrix()) }
    val requestClose: () -> Unit = {
        if (vm.hasChanges && vm.savedUri == null) confirmExit = true else onClose()
    }
    BackHandler(onBack = requestClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
    ) {
    // Ambiance : la photo en cours, très floutée ; les surfaces de verre en prennent les couleurs.
    GlassBackdrop(Modifier.fillMaxSize()) { EditorAmbient(vm.thumbnail) }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        ScreenHeader(
            title = "Modifier la photo",
            onBack = requestClose,
            backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            titleInGlass = true,
            trailing = {
                if (vm.isSaving) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .liquidGlass(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = VistaColors.Primary, strokeWidth = 2.dp)
                    }
                } else {
                    CircleIconButton(Icons.Outlined.Save, "Enregistrer", vm::save, enabled = vm.cropped != null)
                }
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolCircle(Icons.AutoMirrored.Outlined.Undo, "Annuler", enabled = vm.canUndo, onClick = vm::undo)
            Spacer(Modifier.width(10.dp))
            ToolCircle(Icons.AutoMirrored.Outlined.Redo, "Rétablir", enabled = vm.canRedo, onClick = vm::redo)
            Spacer(Modifier.width(10.dp))
            // Sélecteur d'outils : on glisse d'un outil à l'autre, la goutte de verre suit le doigt.
            val toolItems = remember { EditorTool.entries.map { GlassBarItem(it.icon, it.label) } }
            GlassSlidingBar(
                items = toolItems,
                selectedIndex = tool.ordinal,
                onSelect = { tool = EditorTool.entries[it] },
                itemWidth = 48.dp,
                height = 48.dp,
            )
            Spacer(Modifier.weight(1f))
            Text(tool.label, fontFamily = Kanit, fontSize = 14.sp, color = VistaColors.Muted)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val original = vm.original
            val transformed = vm.transformed
            val cropped = vm.cropped
            if (original == null || transformed == null || cropped == null) {
                CircularProgressIndicator(color = VistaColors.Primary, strokeWidth = 2.dp)
            } else {
                AnimatedContent(
                    targetState = tool == EditorTool.CROP,
                    transitionSpec = {
                        (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.97f)) togetherWith
                            (fadeOut(tween(160)) + scaleOut(tween(240), targetScale = 0.97f))
                    },
                    label = "canvas",
                ) { cropping ->
                    if (cropping) {
                        CropEditor(
                            bitmap = transformed,
                            crop = state.crop,
                            straighten = state.straighten,
                            lockedRatio = vm.lockedRatio,
                            isLocked = vm.lockedRatio != null,
                            colorMatrix = colorMatrix,
                            onCropChange = vm::updateCrop,
                            onCropCommit = { vm.commit() },
                            onToggleLock = vm::toggleAspectLock,
                        )
                    } else {
                        PhotoPreview(cropped, original, colorMatrix, state.vignette)
                    }
                }
            }
        }

        // Panneau d'outils en verre.
        Box(
            Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .fillMaxWidth()
                .height(196.dp)
                .liquidGlass(RoundedCornerShape(32.dp))
        ) {
            AnimatedContent(
                targetState = tool,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                label = "panel",
            ) { current ->
            when (current) {
                EditorTool.FILTERS -> FiltersPanel(
                    state = state,
                    thumbnail = vm.thumbnail,
                    onSelect = vm::selectFilter,
                    onIntensityChange = vm::setFilterIntensity,
                    onCommit = { vm.commit() },
                )
                EditorTool.ADJUST -> AdjustPanel(
                    state = state,
                    selected = selectedAdjustment,
                    onSelect = { selectedAdjustment = it },
                    onValueChange = vm::setAdjustment,
                    onCommit = { vm.commit() },
                )
                EditorTool.PRESETS -> PresetsPanel(
                    presets = vm.presets,
                    thumbnail = vm.thumbnail,
                    canSave = vm.hasLook,
                    canPaste = vm.clipboard != null,
                    onSaveRequest = { presetName = "" },
                    onCopy = {
                        vm.copyLook()
                        Toast.makeText(context, "Retouche copiée", Toast.LENGTH_SHORT).show()
                    },
                    onPaste = vm::pasteLook,
                    onApply = vm::applyPreset,
                    onDelete = { presetToDelete = it },
                )

                EditorTool.CROP -> CropPanel(
                    straighten = state.straighten,
                    aspect = vm.aspect,
                    onStraighten = vm::setStraighten,
                    onCommit = { vm.commit() },
                    onRotate = vm::rotate,
                    onFlip = vm::flipHorizontal,
                    onReset = vm::resetGeometry,
                    onAspect = vm::selectAspect,
                )
            }
            }
        }
    }
    }

    presetName?.let { name ->
        AlertDialog(
            onDismissRequest = { presetName = null },
            containerColor = VistaColors.Surface,
            title = { Text("Enregistrer ce style", fontFamily = Kanit, color = VistaColors.Text) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { presetName = it },
                    singleLine = true,
                    placeholder = { Text("Nom du style", fontFamily = Kanit) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = Kanit),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.savePreset(name)
                    presetName = null
                }) { Text("Enregistrer", fontFamily = Kanit, color = VistaColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { presetName = null }) {
                    Text("Annuler", fontFamily = Kanit, color = VistaColors.Text)
                }
            },
        )
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            containerColor = VistaColors.Surface,
            title = { Text("Supprimer « ${preset.name} » ?", fontFamily = Kanit, color = VistaColors.Text) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePreset(preset.id)
                    presetToDelete = null
                }) { Text("Supprimer", fontFamily = Kanit, color = VistaColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("Annuler", fontFamily = Kanit, color = VistaColors.Text)
                }
            },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            containerColor = VistaColors.Surface,
            title = { Text("Abandonner les modifications ?", fontFamily = Kanit, color = VistaColors.Text) },
            text = { Text("Vos retouches non enregistrées seront perdues.", fontFamily = Kanit, color = VistaColors.Muted) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onClose() }) {
                    Text("Abandonner", fontFamily = Kanit, color = VistaColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Continuer", fontFamily = Kanit, color = VistaColors.Primary) }
            },
        )
    }

    vm.savedUri?.let { uri ->
        AlertDialog(
            onDismissRequest = vm::dismissSaved,
            containerColor = VistaColors.Surface,
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = VistaColors.Primary) },
            title = { Text("Photo enregistrée", fontFamily = Kanit, color = VistaColors.Text) },
            text = { Text("Une copie a été ajoutée à l'album « Créations Vista ».", fontFamily = Kanit, color = VistaColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(intent, "Partager la photo"))
                }) { Text("Partager", fontFamily = Kanit, color = VistaColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Terminer", fontFamily = Kanit, color = VistaColors.Text) }
            },
        )
    }
}

@Composable
private fun ToolCircle(
    icon: ImageVector,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    CircleIconButton(icon, description, onClick, size = 36.dp, iconSize = 18.dp, selected = selected, enabled = enabled)
}

/** La photo en cours, très floutée, derrière l'éditeur. */
@Composable
private fun EditorAmbient(thumbnail: Bitmap?) {
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    Crossfade(targetState = image, animationSpec = tween(500), label = "editorAmbient") { current ->
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .graphicsLayer { alpha = 0.55f },
            )
        }
    }
}

/** Aperçu retouché ; un appui long affiche l'original. */
@Composable
private fun PhotoPreview(edited: Bitmap, original: Bitmap, colorMatrix: ColorMatrix, vignette: Float) {
    var showOriginal by remember { mutableStateOf(false) }
    val editedImage = remember(edited) { edited.asImageBitmap() }
    val originalImage = remember(original) { original.asImageBitmap() }
    val image = if (showOriginal) originalImage else editedImage

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (withTimeoutOrNull(120) { tryAwaitRelease() } == null) {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        }
                    }
                )
            }
    ) {
        FittedImageBox(
            imageWidth = image.width,
            imageHeight = image.height,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Image(
                bitmap = image,
                contentDescription = "Photo",
                contentScale = ContentScale.FillBounds,
                colorFilter = if (showOriginal) null else ColorFilter.colorMatrix(colorMatrix),
                modifier = Modifier
                    .fillMaxSize()
                    .softShadow(RoundedCornerShape(16.dp), 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .drawWithContent {
                        drawContent()
                        if (!showOriginal && vignette > 0f) drawRect(vignetteBrush(size, vignette))
                    },
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Compare, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (showOriginal) "Original" else "Maintenir pour comparer",
                color = Color.White,
                fontFamily = Kanit,
                fontSize = 11.sp,
            )
        }
    }
}
