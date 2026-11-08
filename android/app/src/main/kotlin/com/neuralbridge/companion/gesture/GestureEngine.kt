package com.neuralbridge.companion.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.neuralbridge.companion.service.GestureResultCallback
import com.neuralbridge.companion.shell.ShelldClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Gesture Engine — System-optimized for X-NIX OS.
 *
 * Primary execution driver: privileged input injection via `xnix-shelld` (UID 2000 / AID_SHELL).
 * Works across all apps, system windows, lockscreen, and navigation bar with NO AccessibilityService required.
 * Secondary driver: AccessibilityService.dispatchGesture() (if available).
 */
class GestureEngine(
    private val accessibilityService: AccessibilityService? = null
) {
    companion object {
        private const val TAG = "GestureEngine"
        private const val LONG_PRESS_DURATION_MS = 1000L
        private const val SWIPE_DURATION_MS = 300L
    }

    /**
     * Execute a tap gesture at (x, y)
     */
    fun executeTap(
        x: Float,
        y: Float,
        callback: GestureResultCallback? = null
    ) {
        val xi = x.toInt()
        val yi = y.toInt()
        Log.d(TAG, "Executing tap at ($xi, $yi)")

        if (ShelldClient.isAvailable()) {
            GlobalScope.launch(Dispatchers.IO) {
                val res = ShelldClient.execute("input tap $xi $yi", 5000L)
                if (res.exitCode == 0) {
                    callback?.onCompleted(null)
                } else {
                    fallbackA11yTap(x, y, callback)
                }
            }
            return
        }

        fallbackA11yTap(x, y, callback)
    }

    private fun fallbackA11yTap(x: Float, y: Float, callback: GestureResultCallback?) {
        val service = accessibilityService
        if (service == null) {
            callback?.onCancelled(null)
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled(gestureDescription)
            }
        }, null)
    }

    /**
     * Execute double tap at (x, y)
     */
    fun executeDoubleTap(
        x: Float,
        y: Float,
        callback: GestureResultCallback? = null
    ) {
        val xi = x.toInt()
        val yi = y.toInt()
        Log.d(TAG, "Executing double tap at ($xi, $yi)")

        if (ShelldClient.isAvailable()) {
            GlobalScope.launch(Dispatchers.IO) {
                val res = ShelldClient.execute("input tap $xi $yi && sleep 0.08 && input tap $xi $yi", 5000L)
                if (res.exitCode == 0) {
                    callback?.onCompleted(null)
                } else {
                    callback?.onCancelled(null)
                }
            }
            return
        }

        executeTap(x, y, object : GestureResultCallback {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    executeTap(x, y, callback)
                }, 100L)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled(gestureDescription)
            }
        })
    }

    /**
     * Execute long press at (x, y)
     */
    fun executeLongPress(
        x: Float,
        y: Float,
        durationMs: Long = LONG_PRESS_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        val xi = x.toInt()
        val yi = y.toInt()
        Log.d(TAG, "Executing long press at ($xi, $yi) for ${durationMs}ms")

        if (ShelldClient.isAvailable()) {
            GlobalScope.launch(Dispatchers.IO) {
                // In Android's input tool, swipe with same start/end coordinates behaves as a press and hold
                val res = ShelldClient.execute("input swipe $xi $yi $xi $yi $durationMs", durationMs + 2000L)
                if (res.exitCode == 0) {
                    callback?.onCompleted(null)
                } else {
                    callback?.onCancelled(null)
                }
            }
            return
        }

        val service = accessibilityService
        if (service == null) {
            callback?.onCancelled(null)
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled(gestureDescription)
            }
        }, null)
    }

    /**
     * Execute swipe gesture from (startX, startY) to (endX, endY)
     */
    fun executeSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        val sx = startX.toInt()
        val sy = startY.toInt()
        val ex = endX.toInt()
        val ey = endY.toInt()
        Log.d(TAG, "Executing swipe from ($sx, $sy) to ($ex, $ey) duration=${durationMs}ms")

        if (ShelldClient.isAvailable()) {
            GlobalScope.launch(Dispatchers.IO) {
                val res = ShelldClient.execute("input swipe $sx $sy $ex $ey $durationMs", durationMs + 2000L)
                if (res.exitCode == 0) {
                    callback?.onCompleted(null)
                } else {
                    callback?.onCancelled(null)
                }
            }
            return
        }

        val service = accessibilityService
        if (service == null) {
            callback?.onCancelled(null)
            return
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled(gestureDescription)
            }
        }, null)
    }

    /**
     * Execute drag gesture
     */
    fun executeDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        val sx = startX.toInt()
        val sy = startY.toInt()
        val ex = endX.toInt()
        val ey = endY.toInt()
        Log.d(TAG, "Executing drag from ($sx, $sy) to ($ex, $ey)")

        if (ShelldClient.isAvailable()) {
            GlobalScope.launch(Dispatchers.IO) {
                val res = ShelldClient.execute("input draganddrop $sx $sy $ex $ey $durationMs", durationMs + 3000L)
                if (res.exitCode == 0) {
                    callback?.onCompleted(null)
                } else {
                    // Fallback to swipe
                    val res2 = ShelldClient.execute("input swipe $sx $sy $ex $ey $durationMs", durationMs + 2000L)
                    if (res2.exitCode == 0) callback?.onCompleted(null) else callback?.onCancelled(null)
                }
            }
            return
        }

        executeSwipe(startX, startY, endX, endY, durationMs, callback)
    }

    /**
     * Execute pinch gesture
     */
    fun executePinch(
        centerX: Float,
        centerY: Float,
        startRadius: Float,
        endRadius: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing pinch at ($centerX, $centerY)")
        val service = accessibilityService
        if (service == null) {
            callback?.onCancelled(null)
            return
        }

        val path1 = Path().apply {
            moveTo(centerX - startRadius, centerY)
            lineTo(centerX - endRadius, centerY)
        }
        val path2 = Path().apply {
            moveTo(centerX + startRadius, centerY)
            lineTo(centerX + endRadius, centerY)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, durationMs)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, durationMs)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled(gestureDescription)
            }
        }, null)
    }
}
