package com.vista.photoeditor.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vista.photoeditor.editor.Grade
import com.vista.photoeditor.editor.NormRect
import com.vista.photoeditor.editor.straightenScale
import com.vista.photoeditor.ui.components.CircleIconButton
import com.vista.photoeditor.ui.components.FittedImageBox
import com.vista.photoeditor.ui.theme.VistaColors

private enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Image entière (redressée en direct) avec un cadre de recadrage déplaçable et redimensionnable
 * par les coins. [lockedRatio] impose un rapport largeur/hauteur en pixels.
 */
@Composable
fun CropEditor(
    bitmap: Bitmap,
    crop: NormRect,
    straighten: Float,
    lockedRatio: Float?,
    isLocked: Boolean,
    colorMatrix: ColorMatrix,
    grade: Grade,
    onCropChange: (NormRect) -> Unit,
    onCropCommit: () -> Unit,
    onToggleLock: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val latestCrop by rememberUpdatedState(crop)
    val latestRatio by rememberUpdatedState(lockedRatio)
    val onChange by rememberUpdatedState(onCropChange)
    val onCommit by rememberUpdatedState(onCropCommit)
    val zoom = straightenScale(image.width.toFloat(), image.height.toFloat(), straighten)
    val veil = VistaColors.CropVeil
    val grid = VistaColors.CropGrid
    val ink = VistaColors.Text

    FittedImageBox(
        imageWidth = image.width,
        imageHeight = image.height,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Image(
            bitmap = image,
            contentDescription = "Photo à recadrer",
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.colorMatrix(colorMatrix),
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    rotationZ = straighten
                    scaleX = zoom
                    scaleY = zoom
                }
                // Avant Android 13, le recadrage montre la photo sans son étalonnage.
                .grade(grade),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val start = latestCrop
                        val p = down.position

                        val corners = mapOf(
                            Handle.TOP_LEFT to Offset(start.left * w, start.top * h),
                            Handle.TOP_RIGHT to Offset(start.right * w, start.top * h),
                            Handle.BOTTOM_LEFT to Offset(start.left * w, start.bottom * h),
                            Handle.BOTTOM_RIGHT to Offset(start.right * w, start.bottom * h),
                        )
                        val nearest = corners.minBy { (it.value - p).getDistance() }
                        val inside = p.x in start.left * w..start.right * w && p.y in start.top * h..start.bottom * h
                        val handle = when {
                            (nearest.value - p).getDistance() <= 36.dp.toPx() -> nearest.key
                            inside -> Handle.MOVE
                            else -> return@awaitEachGesture
                        }
                        down.consume()

                        // Rapport exprimé en unités normalisées : largeurN / hauteurN.
                        val ratio = latestRatio?.let { it * h / w }
                        val minW = 56.dp.toPx() / w
                        val minH = 56.dp.toPx() / h
                        drag(down.id) { change ->
                            change.consume()
                            val dx = (change.position.x - p.x) / w
                            val dy = (change.position.y - p.y) / h
                            onChange(
                                if (handle == Handle.MOVE) start.movedBy(dx, dy)
                                else resizeFromCorner(start, handle, dx, dy, ratio, minW, minH)
                            )
                        }
                        onCommit()
                    }
                }
        ) {
            val l = crop.left * size.width
            val t = crop.top * size.height
            val r = crop.right * size.width
            val b = crop.bottom * size.height

            drawRect(veil, Offset.Zero, Size(size.width, t))
            drawRect(veil, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(veil, Offset(0f, t), Size(l, b - t))
            drawRect(veil, Offset(r, t), Size(size.width - r, b - t))

            for (i in 1..2) {
                val x = l + (r - l) * i / 3f
                val y = t + (b - t) * i / 3f
                drawLine(grid, Offset(x, t), Offset(x, b), 1.dp.toPx())
                drawLine(grid, Offset(l, y), Offset(r, y), 1.dp.toPx())
            }
            drawRect(grid, Offset(l, t), Size(r - l, b - t), style = Stroke(1.dp.toPx()))

            // Équerres sombres posées juste à l'extérieur des coins.
            val len = 26.dp.toPx()
            val thick = 3.dp.toPx()
            val o = thick / 2f
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                val cx = x - sx * o
                val cy = y - sy * o
                drawLine(ink, Offset(cx, cy), Offset(cx + sx * len, cy), thick, StrokeCap.Square)
                drawLine(ink, Offset(cx, cy), Offset(cx, cy + sy * len), thick, StrokeCap.Square)
            }
            corner(l, t, 1f, 1f)
            corner(r, t, -1f, 1f)
            corner(l, b, 1f, -1f)
            corner(r, b, -1f, -1f)
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val button = 44.dp
            val margin = 12.dp
            CircleIconButton(
                icon = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription = if (isLocked) "Déverrouiller le format" else "Verrouiller le format",
                onClick = onToggleLock,
                size = button,
                iconSize = 20.dp,
                modifier = Modifier.offset(
                    x = (maxWidth * crop.right - button - margin).coerceAtLeast(maxWidth * crop.left),
                    y = maxHeight * crop.top + margin,
                ),
            )
        }
    }
}

/** Redimensionne depuis un coin, le coin opposé restant fixe. */
private fun resizeFromCorner(
    start: NormRect,
    handle: Handle,
    dx: Float,
    dy: Float,
    ratio: Float?,
    minW: Float,
    minH: Float,
): NormRect {
    val leftSide = handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT
    val topSide = handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT

    val anchorX = if (leftSide) start.right else start.left
    val anchorY = if (topSide) start.bottom else start.top
    val cornerX = (if (leftSide) start.left else start.right) + dx
    val cornerY = (if (topSide) start.top else start.bottom) + dy

    val maxW = if (leftSide) anchorX else 1f - anchorX
    val maxH = if (topSide) anchorY else 1f - anchorY

    var width = (if (leftSide) anchorX - cornerX else cornerX - anchorX).coerceIn(minOf(minW, maxW), maxW)
    var height = (if (topSide) anchorY - cornerY else cornerY - anchorY).coerceIn(minOf(minH, maxH), maxH)

    if (ratio != null) {
        if (width / ratio >= height) height = width / ratio else width = height * ratio
        if (height > maxH) {
            height = maxH
            width = height * ratio
        }
        if (width > maxW) {
            width = maxW
            height = width / ratio
        }
    }

    val left = if (leftSide) anchorX - width else anchorX
    val top = if (topSide) anchorY - height else anchorY
    return NormRect(left, top, left + width, top + height)
}
