package com.vista.photoeditor.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop169
import androidx.compose.material.icons.outlined.Crop32
import androidx.compose.material.icons.outlined.CropOriginal
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.editor.Adjustment
import com.vista.photoeditor.editor.AspectRatio
import com.vista.photoeditor.editor.EditPreset
import com.vista.photoeditor.editor.EditState
import com.vista.photoeditor.editor.Filters
import com.vista.photoeditor.editor.withLook
import com.vista.photoeditor.editor.MAX_STRAIGHTEN_DEGREES
import com.vista.photoeditor.ui.components.RulerDial
import com.vista.photoeditor.ui.components.bouncyClickable
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlin.math.roundToInt

@Composable
private fun PanelColumn(caption: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Text(
            caption,
            fontFamily = Kanit,
            fontSize = 12.sp,
            color = VistaColors.Primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        content()
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun OptionRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Bouton rond + libellé de la barre d'options (style « Flip / Rotate / Square »). */
@Composable
private fun OptionCircle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    image: (@Composable () -> Unit)? = null,
    badge: Boolean = false,
    enabled: Boolean = true,
) {
    val background by animateColorAsState(
        if (selected) VistaColors.Primary else VistaColors.Surface,
        tween(220),
        label = "optionBackground",
    )
    // Sur le verre clair, un gris moyen se perd : libellé foncé légèrement atténué.
    val labelColor by animateColorAsState(
        if (selected) VistaColors.Primary else VistaColors.Text.copy(alpha = 0.72f),
        tween(220),
        label = "optionLabel",
    )
    val emphasis by animateFloatAsState(
        if (selected) 1.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "optionScale",
    )
    Column(
        Modifier
            .width(62.dp)
            .then(if (enabled) Modifier.bouncyClickable(onClick = onClick) else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .graphicsLayer {
                    scaleX = emphasis
                    scaleY = emphasis
                }
                .softShadow(CircleShape, if (selected) 8.dp else 5.dp)
                .clip(CircleShape)
                .background(background)
                .then(if (selected && image != null) Modifier.border(2.5.dp, VistaColors.Primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(if (selected) 3.dp else 0.dp)
                        .clip(CircleShape)
                ) { image() }
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) VistaColors.OnPrimary else VistaColors.Text,
                    modifier = Modifier.size(19.dp),
                )
            }
            if (badge && !selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(VistaColors.Primary)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontFamily = Kanit,
            fontSize = 11.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = labelColor,
        )
    }
}

@Composable
fun FiltersPanel(
    state: EditState,
    thumbnail: Bitmap?,
    onSelect: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    val thumb: ImageBitmap? = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val matrices = remember { Filters.all.associate { it.id to ColorMatrix(it.matrix) } }
    val hasFilter = state.filterId != Filters.ORIGINAL_ID

    PanelColumn(if (hasFilter) "Intensité ${(state.filterIntensity * 100).roundToInt()} %" else "Choisissez un filtre") {
        Box(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            if (hasFilter) {
                RulerDial(
                    value = state.filterIntensity * 100f,
                    range = 0f..100f,
                    onValueChange = { onIntensityChange(it / 100f) },
                    onValueChangeFinished = onCommit,
                    unitsPerTick = 2f,
                    ticksPerLabel = 5,
                )
            }
        }
        OptionRow {
            items(Filters.all, key = { it.id }) { filter ->
                OptionCircle(
                    label = filter.name,
                    selected = filter.id == state.filterId,
                    onClick = { onSelect(filter.id) },
                    image = thumb?.let { bitmap ->
                        {
                            Image(
                                bitmap = bitmap,
                                contentDescription = filter.name,
                                contentScale = ContentScale.Crop,
                                colorFilter = ColorFilter.colorMatrix(matrices.getValue(filter.id)),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun Adjustment.icon(): ImageVector = when (this) {
    Adjustment.BRIGHTNESS -> Icons.Outlined.LightMode
    Adjustment.EXPOSURE -> Icons.Outlined.Exposure
    Adjustment.CONTRAST -> Icons.Outlined.Contrast
    Adjustment.SATURATION -> Icons.Outlined.InvertColors
    Adjustment.WARMTH -> Icons.Outlined.WbSunny
    Adjustment.TINT -> Icons.Outlined.Palette
    Adjustment.FADE -> Icons.Outlined.Gradient
    Adjustment.VIGNETTE -> Icons.Outlined.Vignette
}

@Composable
fun AdjustPanel(
    state: EditState,
    selected: Adjustment,
    onSelect: (Adjustment) -> Unit,
    onValueChange: (Adjustment, Float) -> Unit,
    onCommit: () -> Unit,
) {
    val value = state.value(selected).roundToInt()
    val formatted = if (selected.isBipolar && value > 0) "+$value" else "$value"
    PanelColumn("${selected.label} $formatted") {
        RulerDial(
            value = state.value(selected),
            range = selected.min..selected.max,
            onValueChange = { onValueChange(selected, it) },
            onValueChangeFinished = onCommit,
            unitsPerTick = 2f,
            ticksPerLabel = 5,
        )
        OptionRow {
            items(Adjustment.entries) { adjustment ->
                OptionCircle(
                    label = adjustment.label,
                    icon = adjustment.icon(),
                    selected = adjustment == selected,
                    badge = state.value(adjustment) != 0f,
                    onClick = { onSelect(adjustment) },
                )
            }
        }
    }
}

@Composable
fun PresetsPanel(
    presets: List<EditPreset>,
    thumbnail: Bitmap?,
    canSave: Boolean,
    canPaste: Boolean,
    onSaveRequest: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onApply: (EditPreset) -> Unit,
    onDelete: (EditPreset) -> Unit,
) {
    val thumb: ImageBitmap? = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val matrices = remember(presets) {
        presets.associate { it.id to ColorMatrix(EditState().withLook(it.look).colorMatrix()) }
    }

    PanelColumn(if (presets.isEmpty()) "Enregistrez une retouche pour la réutiliser" else "Mes préréglages") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OptionCircle("Nouveau", selected = false, onClick = onSaveRequest, icon = Icons.Outlined.BookmarkAdd, enabled = canSave)
            OptionCircle("Copier", selected = false, onClick = onCopy, icon = Icons.Outlined.ContentCopy, enabled = canSave)
            OptionCircle("Coller", selected = false, onClick = onPaste, icon = Icons.Outlined.ContentPaste, enabled = canPaste)
        }
        if (presets.isEmpty()) {
            Text(
                "Réglez une photo, puis touchez Enregistrer.",
                fontFamily = Kanit,
                fontSize = 12.sp,
                color = VistaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(presets, key = { it.id }) { preset ->
                    PresetItem(
                        preset = preset,
                        image = thumb,
                        matrix = matrices.getValue(preset.id),
                        onApply = { onApply(preset) },
                        onDelete = { onDelete(preset) },
                    )
                }
            }
        }
    }
}

/** Aperçu du préréglage sur la photo en cours ; l'appui long le supprime. */
@Composable
private fun PresetItem(
    preset: EditPreset,
    image: ImageBitmap?,
    matrix: ColorMatrix,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .width(66.dp)
            .pointerInput(preset.id) {
                detectTapGestures(onTap = { onApply() }, onLongPress = { onDelete() })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .softShadow(CircleShape, 5.dp)
                .clip(CircleShape)
                .background(VistaColors.Surface)
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = preset.name,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(preset.name, fontFamily = Kanit, fontSize = 11.sp, color = VistaColors.Text, maxLines = 1)
        Text(preset.look.summary(), fontFamily = Kanit, fontSize = 9.sp, color = VistaColors.Muted, maxLines = 1)
    }
}

private data class AspectOption(val ratio: AspectRatio, val label: String, val icon: ImageVector)

private val aspectOptions = listOf(
    AspectOption(AspectRatio.FREE, "Libre", Icons.Outlined.CenterFocusWeak),
    AspectOption(AspectRatio.SQUARE, "Carré", Icons.Outlined.CropSquare),
    AspectOption(AspectRatio.RATIO_4_5, "Portrait", Icons.Outlined.CropPortrait),
    AspectOption(AspectRatio.RATIO_9_16, "Story", Icons.Outlined.StayCurrentPortrait),
    AspectOption(AspectRatio.RATIO_16_9, "Large", Icons.Outlined.Crop169),
    AspectOption(AspectRatio.RATIO_3_2, "Photo", Icons.Outlined.Crop32),
    AspectOption(AspectRatio.ORIGINAL, "Original", Icons.Outlined.CropOriginal),
)

@Composable
fun CropPanel(
    straighten: Float,
    aspect: AspectRatio,
    onStraighten: (Float) -> Unit,
    onCommit: () -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onReset: () -> Unit,
    onAspect: (AspectRatio) -> Unit,
) {
    PanelColumn("Redresser ${straighten.roundToInt()}°") {
        RulerDial(
            value = straighten,
            range = -MAX_STRAIGHTEN_DEGREES..MAX_STRAIGHTEN_DEGREES,
            onValueChange = onStraighten,
            onValueChangeFinished = onCommit,
            unitsPerTick = 1f,
            ticksPerLabel = 5,
        )
        OptionRow {
            item { OptionCircle("Miroir", selected = false, onClick = onFlip, icon = Icons.Outlined.Flip) }
            item { OptionCircle("Pivoter", selected = false, onClick = onRotate, icon = Icons.Outlined.Rotate90DegreesCw) }
            items(aspectOptions) { option ->
                OptionCircle(option.label, selected = option.ratio == aspect, onClick = { onAspect(option.ratio) }, icon = option.icon)
            }
            item { OptionCircle("Réinit.", selected = false, onClick = onReset, icon = Icons.Outlined.RestartAlt) }
        }
    }
}
