package com.gios.brightfantasy.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A camera pointed at one QR code.
 *
 * ZXing rather than ML Kit. ML Kit's barcode scanner either downloads its model through
 * Play Services — which this phone does not have — or bundles tens of megabytes into the
 * APK for a feature used exactly once per install. `zxing-core` is about half a megabyte
 * of pure Java, and because the analysis stream hands over YUV_420_888, its luminance
 * source can read the Y plane directly with no colour conversion and no bitmap.
 */
@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context.findLifecycleOwner()
    val handler by rememberUpdatedState(onScanned)

    // A QR decode is a few milliseconds but it is not free, and doing it on the frame
    // thread stutters the preview. One thread, and frames are dropped rather than queued.
    val executor = remember { Executors.newSingleThreadExecutor() }
    // One code, once. The analyzer keeps running for a beat after a hit and firing twice
    // means applying the same credentials twice and refreshing twice.
    val consumed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull()
                    ?: return@addListener
                val owner = lifecycleOwner ?: return@addListener

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = if (consumed.get()) null else image.decodeQr()
                    image.close()
                    if (text != null && consumed.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(ctx).execute { handler(text) }
                    }
                }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * Decode one frame.
 *
 * The Y plane of a YUV_420_888 image is 8-bit luminance already, which is exactly what
 * ZXing wants, so there is no bitmap and no colour conversion anywhere in this path. The
 * row stride is not always the width — the camera pads rows to an alignment — so the
 * plane is copied row by row rather than in one block, which is the difference between
 * decoding and reading a sheared image that never decodes.
 */
private fun ImageProxy.decodeQr(): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val data = ByteArray(width * height)
    if (rowStride == width) {
        buffer.get(data, 0, minOf(buffer.remaining(), data.size))
    } else {
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            if (buffer.remaining() < rowStride) break
            buffer.get(row, 0, rowStride)
            System.arraycopy(row, 0, data, y * width, width)
        }
    }

    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return runCatching {
        READER.decode(bitmap, HINTS).text
    }.getOrElse {
        // A frame with no code in it throws NotFoundException, which is the ordinary case
        // several times a second — not something to log, let alone report.
        READER.reset()
        null
    }
}

private val READER = QRCodeReader()

private val HINTS: Map<DecodeHintType, Any> =
    EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        // The payload is long — a 300-character cookie is a dense code — and this is a
        // deliberate scan of a screen rather than a hopeful sweep of a room, so the slow
        // careful pass is the right one.
        put(DecodeHintType.TRY_HARDER, true)
        put(DecodeHintType.CHARACTER_SET, "UTF-8")
    }

private fun Context.findLifecycleOwner(): LifecycleOwner? =
    generateSequence(this) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<LifecycleOwner>()
        .firstOrNull()
