package com.neuralbridge.companion.screenshot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.neuralbridge.companion.service.ScreenshotQuality
import com.neuralbridge.companion.shell.ShelldClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Screenshot Pipeline
 *
 * Primary capture: xnix-shelld screencap (UID 2000 / AID_SHELL) - zero user prompts.
 * Fallback: MediaProjection / AccessibilityService.takeScreenshot().
 */
class ScreenshotPipeline(
    private val context: Context,
    private val accessibilityService: AccessibilityService? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        private const val TAG = "ScreenshotPipeline"
        private const val VIRTUAL_DISPLAY_NAME = "NeuralBridge-Screenshot"
        private const val IMAGE_READER_TIMEOUT_MS = 5000L
    }

    private val captureMutex = Mutex()

    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var imageReader: ImageReader? = null

    var onMediaProjectionLost: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system")
            releaseProjectionResources()
            onMediaProjectionLost?.invoke()
        }
    }

    init {
        try {
            System.loadLibrary("neuralbridge_jni")
            Log.d(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not available, will use fallback encoder")
        }
    }

    private external fun encodeJpegNative(bitmap: Bitmap, quality: Int): ByteArray

    private fun getDisplayMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return DisplayMetrics().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = context.resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(this)
            }
        }
    }

    fun hasMediaProjectionPermission(): Boolean {
        return ShelldClient.isAvailable() || mediaProjection != null
    }

    suspend fun tryConsumePendingConsent(): Boolean {
        return true
    }

    suspend fun requestMediaProjectionPermission(): Boolean {
        if (ShelldClient.isAvailable()) return true
        if (mediaProjection != null) return true
        return false
    }

    /**
     * Capture screenshot
     */
    suspend fun capture(quality: ScreenshotQuality): ByteArray = captureMutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            // Strategy 1: Privileged direct shell capture (UID 2000)
            if (ShelldClient.isAvailable()) {
                try {
                    val rawPng = ShelldClient.executeRaw("screencap -p")
                    if (rawPng.isNotEmpty()) {
                        val bitmap = BitmapFactory.decodeByteArray(rawPng, 0, rawPng.size)
                        if (bitmap != null) {
                            val jpegBytes = try {
                                encodeToJpeg(bitmap, quality)
                            } finally {
                                bitmap.recycle()
                            }
                            val elapsedMs = System.currentTimeMillis() - startTime
                            Log.i(TAG, "Screenshot captured via shell screencap: ${jpegBytes.size} bytes in ${elapsedMs}ms")
                            return@withContext jpegBytes
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Shell screencap failed, trying fallback: ${e.message}")
                }
            }

            // Strategy 2: MediaProjection / AccessibilityService fallback
            if (accessibilityService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val bitmap = captureViaAccessibilityService(accessibilityService)
                    val jpegBytes = try {
                        encodeToJpeg(bitmap, quality)
                    } finally {
                        bitmap.recycle()
                    }
                    val elapsedMs = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Screenshot captured via AccessibilityService fallback: ${jpegBytes.size} bytes in ${elapsedMs}ms")
                    return@withContext jpegBytes
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "AccessibilityService fallback failed", fallbackError)
                }
            }

            throw Exception("Screenshot capture failed: neither shell nor accessibility capture was successful")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureViaAccessibilityService(service: AccessibilityService): Bitmap = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            screenshotResult.hardwareBuffer.close()
                            if (hardwareBitmap == null) {
                                continuation.resumeWithException(IllegalStateException("wrapHardwareBuffer returned null"))
                                return
                            }
                            val softwareBitmap = try {
                                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hardwareBitmap.recycle()
                            }
                            if (softwareBitmap == null) {
                                continuation.resumeWithException(IllegalStateException("Failed to copy hardware bitmap"))
                                return
                            }
                            continuation.resume(softwareBitmap)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resumeWithException(
                            RuntimeException("AccessibilityService.takeScreenshot failed with error code: $errorCode")
                        )
                    }
                }
            )
        }
    }

    private fun encodeToJpeg(bitmap: Bitmap, quality: ScreenshotQuality): ByteArray {
        val startTime = System.currentTimeMillis()

        try {
            val jpegBytes = encodeJpegNative(bitmap, quality.jpegQuality)
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "JPEG encoded via JNI: ${jpegBytes.size} bytes in ${elapsedMs}ms")
                return jpegBytes
            }
        } catch (_: Throwable) {}

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, outputStream)
        val jpegBytes = outputStream.toByteArray()

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "JPEG encoded via Bitmap.compress(): ${jpegBytes.size} bytes in ${elapsedMs}ms")
        return jpegBytes
    }

    private fun releaseProjectionResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection = null
    }

    fun cleanup() {
        releaseProjectionResources()
    }
}
