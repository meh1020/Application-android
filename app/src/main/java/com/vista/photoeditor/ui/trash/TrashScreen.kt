package com.vista.photoeditor.ui.trash

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vista.photoeditor.data.MediaPhoto
import com.vista.photoeditor.ui.components.PhotoImage
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors

@Composable
fun TrashScreen(
    photos: List<MediaPhoto>,
    onBack: () -> Unit,
    onRestore: (List<Uri>) -> Unit,
    onDeleteForever: (List<Uri>) -> Unit,
) {
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val validSelection = selection.filter { id -> photos.any { it.id == id } }.toSet()
    // Sans sélection, les actions s'appliquent à toute la corbeille.
    val targets = if (validSelection.isEmpty()) photos else photos.filter { it.id in validSelection }

    Box(
        Modifier
            .fillMaxSize()
            .background(VistaColors.ScreenGradient)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Corbeille", onBack, Icons.AutoMirrored.Filled.KeyboardArrowLeft)
            Text(
                "Les photos sont supprimées définitivement après 30 jours.",
                fontFamily = Kanit,
                fontSize = 14.sp,
                color = VistaColors.Muted,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("La corbeille est vide", fontFamily = Kanit, fontSize = 17.sp, color = VistaColors.Muted, textAlign = TextAlign.Center)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(photos, key = { it.id }) { photo ->
                        val selected = photo.id in validSelection
                        Box(
                            Modifier
                                .animateItem()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .plainClickable { selection = if (selected) selection - photo.id else selection + photo.id }
                        ) {
                            PhotoImage(photo.uri, Modifier.fillMaxSize())
                            if (selected) Box(Modifier.fillMaxSize().background(VistaColors.Primary.copy(alpha = 0.25f)))
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
            }
        }

        if (photos.isNotEmpty()) {
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
                val suffix = if (validSelection.isEmpty()) "tout" else "(${validSelection.size})"
                BarAction(Icons.Outlined.RestoreFromTrash, "Restaurer $suffix", VistaColors.Primary, Modifier.weight(1f)) {
                    onRestore(targets.map { it.uri }); selection = emptySet()
                }
                Spacer(Modifier.width(1.dp).height(32.dp).background(VistaColors.Outline))
                BarAction(Icons.Outlined.DeleteForever, "Supprimer $suffix", VistaColors.Danger, Modifier.weight(1f)) {
                    onDeleteForever(targets.map { it.uri }); selection = emptySet()
                }
            }
        }
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
