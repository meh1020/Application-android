package com.vista.photoeditor.ui.hidden

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vista.photoeditor.data.HiddenPhoto
import com.vista.photoeditor.data.HiddenVault
import com.vista.photoeditor.data.VaultBackup
import com.vista.photoeditor.ui.components.CircleIconButton
import com.vista.photoeditor.ui.components.DateLabels
import com.vista.photoeditor.ui.components.ScreenHeader
import com.vista.photoeditor.ui.components.plainClickable
import com.vista.photoeditor.ui.components.softShadow
import com.vista.photoeditor.ui.theme.Kanit
import com.vista.photoeditor.ui.theme.VistaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dossier masqué, ouvert après l'empreinte ou le code. Il se referme dès que l'app passe en
 * arrière-plan, et ni capture d'écran ni aperçu des apps récentes ne montrent son contenu.
 *
 * Ses photos n'existent que sur ce téléphone, sous une clé que la désinstallation détruit : une
 * carte compte celles qui n'ont pas de sauvegarde, et le menu ⋮ écrit ou relit un fichier chiffré
 * par mot de passe (voir [VaultBackup]).
 */
@Composable
fun HiddenScreen(
    vault: HiddenVault,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val scope = rememberCoroutineScope()
    val photos = vault.photos
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var viewing by remember { mutableStateOf<HiddenPhoto?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    /** Avancée de la sauvegarde ou de la relecture en cours : (fait, total), et ce qui se passe. */
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    val selected = selection.filter { id -> photos.any { it.id == id } }.toSet()

    // Le sélecteur de fichiers d'Android passe l'app en arrière-plan : le dossier ne se referme
    // pas pour autant, il fait partie de l'opération. Passé en arrière-plan pendant une opération
    // longue, le dossier se referme à la fin de celle-ci, pas au milieu.
    var pickerOpen by remember { mutableStateOf(false) }
    var lockWhenDone by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        when {
            pickerOpen -> {}
            busy -> { lockWhenDone = true }
            else -> { onLock() }
        }
    }
    LaunchedEffect(Unit) { vault.load() }

    fun runBusy(label: String?, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        busyLabel = label
        scope.launch {
            try {
                block()
            } finally {
                busy = false
                busyLabel = null
                progress = null
                if (lockWhenDone) onLock()
            }
        }
    }

    // --- Sauvegarde : mot de passe, puis choix du fichier, puis écriture. ---
    var askExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri: Uri? ->
        pickerOpen = false
        val password = exportPassword
        exportPassword = null
        if (uri == null || password == null) {
            password?.fill(' ')
            return@rememberLauncherForActivityResult
        }
        runBusy("Sauvegarde") {
            try {
                val count = context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    vault.exportBackup(out, password) { done, total -> progress = done to total }
                }
                onMessage("${itemCount(count)} sauvegardé${if (count > 1) "s" else ""}. Gardez ce fichier hors du téléphone.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fichier à moitié écrit : inutile, et trompeur s'il restait.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                onMessage("Sauvegarde impossible : le fichier n'a pas pu être écrit")
            } finally {
                password.fill(' ')
            }
        }
    }
    val startExport = {
        if (photos.isEmpty()) {
            onMessage("Rien à sauvegarder pour l'instant")
        } else {
            askExportPassword = true
        }
    }

    // --- Relecture : choix du fichier, puis mot de passe (redemandé s'il est faux). ---
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerOpen = false
        importError = null
        importUri = uri
    }
    val startImport = {
        pickerOpen = true
        try {
            // Un fichier .vista n'a pas de type connu d'Android : tous les fichiers sont proposés.
            openBackup.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            pickerOpen = false
            onMessage("Aucun sélecteur de fichiers sur ce téléphone")
        }
    }
    fun runImport(uri: Uri, password: CharArray) {
        importUri = null
        runBusy("Lecture de la sauvegarde") {
            val result = try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    vault.importBackup(input, password) { done, total -> progress = done to total }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HiddenVault.ImportResult(added = 0, skipped = 0, failed = true)
            } finally {
                password.fill(' ')
            }
            if (result.problem == VaultBackup.Problem.WRONG_PASSWORD) {
                importError = "Mot de passe incorrect (ou début du fichier abîmé)."
                importUri = uri
            } else {
                onMessage(importMessage(result))
            }
        }
    }

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
            ScreenHeader(
                "Dossier masqué",
                onBack,
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                trailing = { BackupMenu(onExport = startExport, onImport = startImport) },
            )
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
                            "Aucune photo ni vidéo masquée. Dans un album ou la visionneuse, choisissez « Masquer ».",
                            fontFamily = Kanit,
                            fontSize = 16.sp,
                            color = VistaColors.Muted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        // Après une réinstallation ou sur un nouveau téléphone : le chemin du retour.
                        TextButton(onClick = startImport) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = VistaColors.Primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Récupérer une sauvegarde", fontFamily = Kanit, fontWeight = FontWeight.Medium, color = VistaColors.Primary)
                        }
                    }
                }
            } else {
                BackupCard(
                    unsaved = vault.unsavedCount,
                    lastBackupMillis = vault.lastBackupMillis,
                    onSave = startExport,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                )
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
                    runBusy(null) {
                        val restored = vault.restore(selected)
                        selection = emptySet()
                        onMessage(if (restored == selected.size) "Remis dans la galerie" else "$restored sur ${selected.size} remis dans la galerie")
                    }
                }
                Spacer(Modifier.width(1.dp).height(32.dp).background(VistaColors.Outline))
                BarAction(Icons.Outlined.DeleteForever, "Supprimer (${selected.size})", VistaColors.Danger, Modifier.weight(1f)) {
                    confirmDelete = true
                }
            }
        }

        if (busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    // L'écran reste bloqué pendant l'opération.
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                BusyPanel(busyLabel, progress)
            }
        }

        viewing?.let { photo ->
            if (photo.isVideo) HiddenVideoView(vault, photo) { viewing = null }
            else HiddenPhotoView(vault, photo) { viewing = null }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer définitivement ?", fontFamily = Kanit) },
            text = {
                Text(
                    "${itemCount(selected.size)} ${if (selected.size > 1) "seront effacés" else "sera effacé"} sans passer par la corbeille. Impossible de ${if (selected.size > 1) "les" else "le"} récupérer.",
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

    if (askExportPassword) {
        PasswordDialog(
            creating = true,
            error = null,
            onConfirm = { password ->
                askExportPassword = false
                exportPassword = password
                pickerOpen = true
                try {
                    createBackup.launch(backupFileName())
                } catch (e: ActivityNotFoundException) {
                    pickerOpen = false
                    exportPassword = null
                    password.fill(' ')
                    onMessage("Aucun sélecteur de fichiers sur ce téléphone")
                }
            },
            onDismiss = { askExportPassword = false },
        )
    }

    importUri?.let { uri ->
        PasswordDialog(
            creating = false,
            error = importError,
            onConfirm = { password -> runImport(uri, password) },
            onDismiss = {
                importUri = null
                importError = null
            },
        )
    }
}

/** Menu ⋮ de l'en-tête : sauvegarder le dossier dans un fichier, ou en relire un. */
@Composable
private fun BackupMenu(onExport: () -> Unit, onImport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CircleIconButton(Icons.Outlined.MoreVert, "Sauvegarde", { open = true }, iconSize = 20.dp)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Sauvegarder dans un fichier", fontFamily = Kanit) },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                onClick = { open = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text("Récupérer une sauvegarde", fontFamily = Kanit) },
                leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                onClick = { open = false; onImport() },
            )
        }
    }
}

/**
 * Rappel du risque : tant que des photos n'ont pas de sauvegarde, la carte le dit en rouge ;
 * ensuite, elle donne la date de la dernière, et un toucher en refait une.
 */
@Composable
private fun BackupCard(unsaved: Int, lastBackupMillis: Long?, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val atRisk = unsaved > 0
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (atRisk) VistaColors.Danger.copy(alpha = 0.10f) else VistaColors.SurfaceSoft)
            .plainClickable(onSave)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (atRisk) Icons.Outlined.ReportProblem else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (atRisk) VistaColors.Danger else VistaColors.Primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (atRisk) {
                Text(
                    "${itemCount(unsaved)} sans sauvegarde",
                    fontFamily = Kanit,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = VistaColors.Text,
                )
                Text(
                    "Désinstaller Vista ou effacer ses données les ferait disparaître pour de bon.",
                    fontFamily = Kanit,
                    fontSize = 13.sp,
                    color = VistaColors.Muted,
                )
            } else {
                Text(
                    "Tout est sauvegardé" + (lastBackupMillis?.let { " · ${formatDate(it)}" } ?: ""),
                    fontFamily = Kanit,
                    fontSize = 14.sp,
                    color = VistaColors.Muted,
                )
            }
        }
        if (atRisk) {
            Spacer(Modifier.width(8.dp))
            Text("Sauvegarder", fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = VistaColors.Primary)
        }
    }
}

/** Mot de passe de la sauvegarde : choisi deux fois pour en créer une, une fois pour la relire. */
@Composable
private fun PasswordDialog(
    creating: Boolean,
    error: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val tooShort = creating && password.length < VaultBackup.MIN_PASSWORD_LENGTH
    val mismatch = creating && repeat.isNotEmpty() && repeat != password
    val ready = password.isNotEmpty() && !tooShort && (!creating || repeat == password)
    val transformation = if (shown) VisualTransformation.None else PasswordVisualTransformation()
    val passwordHint: (@Composable () -> Unit)? = when {
        error != null -> { { Text(error, fontFamily = Kanit) } }
        creating -> { { Text("Au moins ${VaultBackup.MIN_PASSWORD_LENGTH} caractères", fontFamily = Kanit) } }
        else -> null
    }
    val repeatHint: (@Composable () -> Unit)? =
        if (mismatch) { { Text("Les deux mots de passe diffèrent", fontFamily = Kanit) } } else null
    val eye: @Composable () -> Unit = {
        IconButton(onClick = { shown = !shown }) {
            Icon(
                if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (shown) "Masquer le mot de passe" else "Afficher le mot de passe",
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Sauvegarder le dossier" else "Ouvrir la sauvegarde", fontFamily = Kanit) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (creating) {
                        "Toutes les photos et vidéos du dossier sont réunies dans un seul fichier, chiffré par ce mot de passe. " +
                            "Rangez-le hors du téléphone (ordinateur, clé USB, Drive) : il permet de les retrouver " +
                            "après une réinstallation ou sur un autre appareil.\n\n" +
                            "Sans ce mot de passe, personne — pas même Vista — ne pourra rouvrir le fichier."
                    } else {
                        "Mot de passe choisi lors de la sauvegarde. Les photos reviennent dans le dossier masqué."
                    },
                    fontFamily = Kanit,
                    fontSize = 14.sp,
                    color = VistaColors.Muted,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe", fontFamily = Kanit) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = eye,
                    isError = error != null,
                    supportingText = passwordHint,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (creating) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text("Le même, une seconde fois", fontFamily = Kanit) },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = repeatHint,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConfirm(password.toCharArray()) }) {
                Text(if (creating) "Choisir où l'enregistrer" else "Ouvrir", fontFamily = Kanit)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", fontFamily = Kanit) }
        },
    )
}

/** Encart d'attente : la dérivation de la clé prend une seconde ou deux avant la première photo. */
@Composable
private fun BusyPanel(label: String?, progress: Pair<Int, Int>?) {
    if (label == null) {
        CircularProgressIndicator(color = VistaColors.Primary, strokeWidth = 2.dp)
        return
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .softShadow(shape, 14.dp)
            .clip(shape)
            .background(VistaColors.Surface)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = VistaColors.Primary, strokeWidth = 2.dp)
        Spacer(Modifier.height(14.dp))
        Text(label, fontFamily = Kanit, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = VistaColors.Text)
        Text(
            progress?.let { (done, total) -> "$done / $total" } ?: "Préparation du chiffrement…",
            fontFamily = Kanit,
            fontSize = 13.sp,
            color = VistaColors.Muted,
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
        if (photo.isVideo) {
            // Durée en blanc dans le coin, comme dans la grille des albums.
            Text(
                DateLabels.duration(photo.durationMillis),
                color = Color.White,
                fontFamily = Kanit,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                style = TextStyle(shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 4f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            )
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

/** Message de fin de relecture, selon ce qui est revenu et pourquoi elle s'est arrêtée. */
internal fun importMessage(result: HiddenVault.ImportResult): String {
    val added = result.added
    val back = if (added > 0) "${itemCount(added)} récupéré${if (added > 1) "s" else ""}" else "Rien n'a été récupéré"
    return when {
        result.problem == VaultBackup.Problem.NOT_A_BACKUP -> "Ce fichier n'est pas une sauvegarde Vista"
        result.problem == VaultBackup.Problem.NEWER_VERSION -> "Sauvegarde faite par une version plus récente de Vista : mettez l'app à jour"
        result.problem == VaultBackup.Problem.WRONG_PASSWORD -> "Mot de passe incorrect"
        result.problem == VaultBackup.Problem.DAMAGED -> "Fichier incomplet ou abîmé. $back"
        result.failed -> "Lecture interrompue. $back"
        added == 0 && result.skipped > 0 -> "Tout ce que contient ce fichier est déjà dans le dossier"
        result.skipped > 0 -> "$back (${result.skipped} déjà présent${if (result.skipped > 1) "s" else ""})"
        else -> back
    }
}

/** « 1 élément », « 3 éléments » : photos et vidéos confondues. */
internal fun itemCount(n: Int): String = if (n > 1) "$n éléments" else "$n élément"

/** Nom proposé pour le fichier : « Vista-dossier-masque-2026-11-03.vista ». */
private fun backupFileName(): String = "Vista-dossier-masque-${LocalDate.now()}.${VaultBackup.EXTENSION}"

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH))

/**
 * Type annoncé au sélecteur de fichiers : neutre, pour qu'aucune app ne prétende ouvrir la
 * sauvegarde et que l'extension .vista soit gardée.
 */
private const val BACKUP_MIME = "application/octet-stream"

/** Côté de la photo affichée en grand : l'écran, pas plus. */
private const val VIEW_SIDE = 2048
