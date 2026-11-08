package com.neuralbridge.companion.input

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.neuralbridge.companion.shell.ShelldClient

/**
 * Input Engine
 *
 * Handles text input, text selection, and keyboard event injection.
 * Primary driver: xnix-shelld (UID 2000, INJECT_EVENTS permission)
 * Secondary/fallback: AccessibilityNodeInfo actions
 */
class InputEngine(
    private val context: Context,
    private val accessibilityService: AccessibilityService? = null
) {
    companion object {
        private const val TAG = "InputEngine"
        private const val MAX_TEXT_LENGTH = 50000
    }

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /**
     * Input text into element or focused field
     *
     * @param element Target element (optional)
     * @param text Text to input
     * @param append Append to existing text (default: false = replace)
     * @return Success status
     */
    fun inputText(
        element: AccessibilityNodeInfo? = null,
        text: String,
        append: Boolean = false
    ): Boolean {
        // Validate text length
        if (text.length > MAX_TEXT_LENGTH) {
            Log.e(TAG, "Text length ${text.length} exceeds maximum of $MAX_TEXT_LENGTH")
            return false
        }

        Log.d(TAG, "Inputting text: ${text.length} chars, append=$append")

        // Strategy 1: If element node is available and editable, try accessibility ACTION_SET_TEXT
        if (element != null) {
            if (!element.isFocused) {
                element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                element.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (element.isAccessibilityFocused || element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        if (append && element.text != null) {
                            "${element.text}$text"
                        } else {
                            text
                        }
                    )
                }

                if (element.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    Log.d(TAG, "Text set via ACTION_SET_TEXT")
                    return true
                }
            }
        }

        // Strategy 2: Elevated shell input injection (UID 2000)
        if (ShelldClient.isAvailable()) {
            try {
                if (text.contains("\n") || text.length > 32 || text.contains("'") || text.contains("\"")) {
                    setClipboardText(text)
                    val res = ShelldClient.execute("input keycombination 113 50 || input keyevent 279")
                    if (res.exitCode == 0) {
                        Log.d(TAG, "Text injected via shell clipboard paste")
                        return true
                    }
                } else {
                    val escaped = text.replace(" ", "%s")
                        .replace("&", "\\&")
                        .replace(";", "\\;")
                        .replace("(", "\\(")
                        .replace(")", "\\)")
                    val res = ShelldClient.execute("input text $escaped")
                    if (res.exitCode == 0) {
                        Log.d(TAG, "Text injected via shell input text")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell text input failed: ${e.message}")
            }
        }

        // Strategy 3: Clipboard paste fallback if node available
        if (element != null) {
            return inputViaClipboard(element, text, append)
        }

        return false
    }

    /**
     * Input text via clipboard paste
     */
    private fun inputViaClipboard(
        element: AccessibilityNodeInfo,
        text: String,
        append: Boolean
    ): Boolean {
        Log.d(TAG, "Inputting text via clipboard paste")

        // Clear existing text if not appending
        if (!append) {
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val existingText = element.text
            if (existingText != null) {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingText.length)
                }
                element.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
        }

        // Set clipboard
        setClipboardText(text)

        // Paste
        val pasted = element.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) {
            Log.d(TAG, "Text pasted successfully")
            return true
        }

        Log.w(TAG, "Failed to paste text")
        return false
    }

    /**
     * Select text in element
     */
    fun selectText(
        element: AccessibilityNodeInfo,
        start: Int,
        end: Int
    ): Boolean {
        Log.d(TAG, "Selecting text: $start to $end")

        if (!element.isEditable) {
            Log.w(TAG, "Element is not editable")
            return false
        }

        if (!element.isAccessibilityFocused) {
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }

        return element.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    /**
     * Clear text in element
     */
    fun clearText(element: AccessibilityNodeInfo?): Boolean {
        return if (element != null) {
            inputText(element, "", append = false)
        } else {
            pressKeyDetailed("select_all", null)
            pressKeyDetailed("delete", null).success
        }
    }

    /**
     * Get clipboard content
     */
    fun getClipboardText(): String? {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString()
        }
        return null
    }

    /**
     * Set clipboard content
     */
    fun setClipboardText(text: String): Boolean {
        val clip = ClipData.newPlainText("", text)
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { clipboardManager.setPrimaryClip(clip) } catch (e: Exception) {
                Log.w(TAG, "setPrimaryClip threw: ${e.message}")
            } finally { latch.countDown() }
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)

        val readBack = getClipboardText()
        val ok = readBack == text
        if (!ok) {
            Log.w(TAG, "Clipboard write not confirmed on read-back")
        }
        return ok
    }

    data class KeyResult(val success: Boolean, val mechanism: String, val verified: Boolean, val note: String? = null)

    fun pressKey(keyName: String, focusedNode: AccessibilityNodeInfo? = null): Boolean =
        pressKeyDetailed(keyName, focusedNode).success

    /**
     * Press a key by name or code.
     * Primary driver: xnix-shelld (UID 2000, INJECT_EVENTS)
     * Fallback: AccessibilityNodeInfo actions
     */
    fun pressKeyDetailed(keyName: String, focusedNode: AccessibilityNodeInfo? = null): KeyResult {
        Log.d(TAG, "Pressing key: $keyName")
        val key = keyName.lowercase().trim()

        // Primary: Shell injection via xnix-shelld
        if (ShelldClient.isAvailable()) {
            val shellCommand = when (key) {
                "enter", "return" -> "input keyevent KEYCODE_ENTER"
                "delete", "backspace" -> "input keyevent KEYCODE_DEL"
                "tab" -> "input keyevent KEYCODE_TAB"
                "space" -> "input keyevent KEYCODE_SPACE"
                "escape", "esc" -> "input keyevent KEYCODE_ESCAPE"
                "home" -> "input keyevent KEYCODE_HOME"
                "back" -> "input keyevent KEYCODE_BACK"
                "app_switch", "recents" -> "input keyevent KEYCODE_APP_SWITCH"
                "select_all" -> "input keycombination 113 29" // CTRL + A
                "copy" -> "input keycombination 113 31 || input keyevent KEYCODE_COPY"
                "cut" -> "input keycombination 113 52 || input keyevent KEYCODE_CUT"
                "paste" -> "input keycombination 113 50 || input keyevent KEYCODE_PASTE"
                "volume_up" -> "input keyevent KEYCODE_VOLUME_UP"
                "volume_down" -> "input keyevent KEYCODE_VOLUME_DOWN"
                "power" -> "input keyevent KEYCODE_POWER"
                else -> {
                    if (key.all { it.isDigit() }) {
                        "input keyevent $key"
                    } else if (key.startsWith("keycode_")) {
                        "input keyevent ${key.uppercase()}"
                    } else {
                        "input keyevent KEYCODE_${key.uppercase()}"
                    }
                }
            }

            val result = ShelldClient.execute(shellCommand)
            if (result.exitCode == 0) {
                return KeyResult(true, "xnix_shelld($shellCommand)", verified = true)
            }
            Log.w(TAG, "Shell key injection failed with code ${result.exitCode}: ${result.stderr}")
        }

        // Secondary / Fallback: Accessibility actions if node / service is available
        if (key == "enter" && focusedNode != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    return KeyResult(true, "ACTION_IME_ENTER", false)
                }
            }
            val currentText = focusedNode.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$currentText\n")
            }
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return KeyResult(true, "ACTION_SET_TEXT(newline)", true)
            }
        }

        if ((key == "delete" || key == "backspace") && focusedNode != null) {
            val currentText = focusedNode.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                val newText = currentText.dropLast(1)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return KeyResult(true, "ACTION_SET_TEXT(truncate)", true)
                }
            }
        }

        if (key == "escape" && accessibilityService != null) {
            val result = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return KeyResult(result, "GLOBAL_ACTION_BACK", result)
        }

        return KeyResult(false, "unsupported", false, "Key '$keyName' could not be injected")
    }
}