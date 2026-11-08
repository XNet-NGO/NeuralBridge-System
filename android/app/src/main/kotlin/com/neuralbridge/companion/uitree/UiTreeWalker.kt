package com.neuralbridge.companion.uitree

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import com.neuralbridge.companion.service.Bounds
import com.neuralbridge.companion.service.UiElement
import com.neuralbridge.companion.service.UiTree
import com.neuralbridge.companion.shell.ShelldClient
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.security.MessageDigest
import java.util.Stack

/**
 * UI Tree Walker
 *
 * Walks the UI tree and extracts semantic information optimized for AI agents.
 * Primary driver: xnix-shelld (uiautomator dump via UID 2000)
 * Secondary driver: AccessibilityNodeInfo tree walk
 */
class UiTreeWalker(
    private val accessibilityService: AccessibilityService? = null
) {
    companion object {
        private const val TAG = "UiTreeWalker"
        private const val MAX_RECURSION_DEPTH = 50

        // Semantic type classifications
        private val BUTTON_CLASSES = setOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "androidx.appcompat.widget.AppCompatButton",
            "com.google.android.material.button.MaterialButton"
        )

        private val INPUT_CLASSES = setOf(
            "android.widget.EditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "com.google.android.material.textfield.TextInputEditText"
        )

        private val TEXT_CLASSES = setOf(
            "android.widget.TextView",
            "android.widget.TextClock",
            "androidx.appcompat.widget.AppCompatTextView"
        )

        private val IMAGE_CLASSES = setOf(
            "android.widget.ImageView",
            "androidx.appcompat.widget.AppCompatImageView"
        )

        private val LIST_CLASSES = setOf(
            "android.widget.ListView",
            "android.widget.RecyclerView",
            "androidx.recyclerview.widget.RecyclerView"
        )

        private val SCROLL_CLASSES = setOf(
            "android.widget.ScrollView",
            "android.widget.HorizontalScrollView",
            "androidx.core.widget.NestedScrollView"
        )

        private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    }

    /**
     * Walk the UI tree and extract all elements.
     * If rootNode is provided, uses AccessibilityNodeInfo.
     * Otherwise, uses privileged shell (uiautomator dump).
     */
    suspend fun walkTree(
        rootNode: AccessibilityNodeInfo? = null,
        includeInvisible: Boolean = false,
        maxDepth: Int = 0
    ): UiTree {
        val startTime = System.currentTimeMillis()

        // Path 1: AccessibilityNodeInfo if provided
        if (rootNode != null) {
            val elements = mutableListOf<UiElement>()
            var totalNodes = 0
            val foregroundApp = rootNode.packageName?.toString() ?: "unknown"

            walkNode(
                node = rootNode,
                parentId = null,
                depth = 0,
                maxDepth = maxDepth,
                includeInvisible = includeInvisible,
                elements = elements,
                totalNodes = { totalNodes++ }
            )

            val elapsedMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "UI tree walk (Accessibility) complete: ${elements.size} elements, ${elapsedMs}ms")
            return UiTree(
                elements = elements,
                foregroundApp = foregroundApp,
                totalNodes = totalNodes,
                captureTimestamp = startTime
            )
        }

        // Path 2: Privileged shell uiautomator dump (UID 2000)
        return dumpTreeViaShell(startTime, includeInvisible, maxDepth)
    }

    /**
     * Dump and parse UI tree via xnix-shelld
     */
    private fun dumpTreeViaShell(
        startTime: Long,
        includeInvisible: Boolean,
        maxDepth: Int
    ): UiTree {
        if (!ShelldClient.isAvailable()) {
            Log.w(TAG, "xnix-shelld not available and rootNode is null")
            return UiTree(emptyList(), "unknown", 0, startTime)
        }

        val tmpDumpPath = "/data/local/tmp/nb_dump_${System.currentTimeMillis()}.xml"
        val cmd = "uiautomator dump $tmpDumpPath > /dev/null 2>&1 && cat $tmpDumpPath && rm -f $tmpDumpPath"
        val result = ShelldClient.execute(cmd, timeoutMs = 10_000L)

        if (result.stdout.isEmpty()) {
            Log.w(TAG, "uiautomator dump returned empty output")
            return UiTree(emptyList(), "unknown", 0, startTime)
        }

        val xml = result.stdout
        val elements = mutableListOf<UiElement>()
        var foregroundApp = "unknown"
        var totalNodes = 0

        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            val parentStack = Stack<Pair<String, Int>>() // (elementId, depth)
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            totalNodes++
                            val depth = parentStack.size

                            val pkg = parser.getAttributeValue(null, "package") ?: ""
                            if (foregroundApp == "unknown" && pkg.isNotEmpty()) {
                                foregroundApp = pkg
                            }

                            val className = parser.getAttributeValue(null, "class") ?: ""
                            val resourceId = parser.getAttributeValue(null, "resource-id") ?: ""
                            val text = parser.getAttributeValue(null, "text") ?: ""
                            val contentDesc = parser.getAttributeValue(null, "content-desc") ?: ""
                            val boundsStr = parser.getAttributeValue(null, "bounds") ?: ""
                            val bounds = parseBounds(boundsStr)

                            val clickable = parser.getAttributeValue(null, "clickable") == "true"
                            val checkable = parser.getAttributeValue(null, "checkable") == "true"
                            val checked = parser.getAttributeValue(null, "checked") == "true"
                            val enabled = parser.getAttributeValue(null, "enabled") != "false"
                            val focusable = parser.getAttributeValue(null, "focusable") == "true"
                            val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                            val longClickable = parser.getAttributeValue(null, "long-clickable") == "true"

                            // Determine visibility based on bounds
                            val visible = (bounds.right > bounds.left && bounds.bottom > bounds.top)

                            val parentId = if (parentStack.isNotEmpty()) parentStack.peek().first else null

                            val elementId = generateElementId(
                                resourceId = resourceId,
                                className = className,
                                text = text,
                                bounds = bounds,
                                parentId = parentId
                            )

                            val semanticType = classifySemanticType(
                                className = className,
                                isClickable = clickable,
                                isEditable = className in INPUT_CLASSES,
                                isCheckable = checkable,
                                isScrollable = scrollable
                            )

                            val aiDesc = generateAiDescription(
                                semanticType = semanticType,
                                text = text,
                                contentDesc = contentDesc,
                                isClickable = clickable,
                                isEditable = className in INPUT_CLASSES,
                                isCheckable = checkable,
                                isChecked = checked,
                                isScrollable = scrollable,
                                isEnabled = enabled,
                                isVisible = visible
                            )

                            val element = UiElement(
                                elementId = elementId,
                                resourceId = resourceId.ifEmpty { null },
                                className = className.ifEmpty { null },
                                text = text.ifEmpty { null },
                                contentDescription = contentDesc.ifEmpty { null },
                                bounds = bounds,
                                visible = visible,
                                enabled = enabled,
                                clickable = clickable,
                                scrollable = scrollable,
                                focusable = focusable,
                                longClickable = longClickable,
                                checkable = checkable,
                                checked = checked,
                                semanticType = semanticType,
                                aiDescription = aiDesc
                            )

                            val shouldAdd = (includeInvisible || visible) && (maxDepth <= 0 || depth <= maxDepth)
                            if (shouldAdd) {
                                elements.add(element)
                            }

                            parentStack.push(Pair(elementId, depth))
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "node" && parentStack.isNotEmpty()) {
                            parentStack.pop()
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse uiautomator XML: ${e.message}", e)
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.i(TAG, "UI tree walk (Shell uiautomator) complete: ${elements.size} elements, ${elapsedMs}ms")

        return UiTree(
            elements = elements,
            foregroundApp = foregroundApp,
            totalNodes = totalNodes,
            captureTimestamp = startTime
        )
    }

    private fun parseBounds(boundsStr: String): Bounds {
        val match = BOUNDS_REGEX.find(boundsStr) ?: return Bounds(0, 0, 0, 0)
        val (l, t, r, b) = match.destructured
        return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    /**
     * Recursively walk node tree (AccessibilityNodeInfo path)
     */
    private fun walkNode(
        node: AccessibilityNodeInfo,
        parentId: String?,
        depth: Int,
        maxDepth: Int,
        includeInvisible: Boolean,
        elements: MutableList<UiElement>,
        totalNodes: () -> Unit
    ) {
        totalNodes()

        if (depth >= MAX_RECURSION_DEPTH) {
            Log.w(TAG, "Maximum recursion depth ($MAX_RECURSION_DEPTH) reached")
            return
        }

        if (maxDepth > 0 && depth >= maxDepth) {
            return
        }

        if (!includeInvisible && !node.isVisibleToUser) {
            return
        }

        val element = extractElement(node, parentId, depth)
        elements.add(element)

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walkNode(
                    node = child,
                    parentId = element.elementId,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    includeInvisible = includeInvisible,
                    elements = elements,
                    totalNodes = totalNodes
                )
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun extractElement(
        node: AccessibilityNodeInfo,
        parentId: String?,
        depth: Int
    ): UiElement {
        val className = node.className?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val elementBounds = Bounds(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )

        val elementId = generateElementId(
            resourceId = resourceId,
            className = className,
            text = text,
            bounds = elementBounds,
            parentId = parentId
        )

        val semanticType = classifySemanticType(
            className = className,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isScrollable = node.isScrollable
        )

        val aiDescription = generateAiDescription(
            semanticType = semanticType,
            text = text,
            contentDesc = contentDesc,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isVisible = node.isVisibleToUser
        )

        return UiElement(
            elementId = elementId,
            resourceId = resourceId.ifEmpty { null },
            className = className.ifEmpty { null },
            text = text.ifEmpty { null },
            contentDescription = contentDesc.ifEmpty { null },
            bounds = elementBounds,
            visible = node.isVisibleToUser,
            enabled = node.isEnabled,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            focusable = node.isFocusable,
            longClickable = node.isLongClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            semanticType = semanticType,
            aiDescription = aiDescription
        )
    }

    private fun generateElementId(
        resourceId: String,
        className: String,
        text: String,
        bounds: Bounds,
        parentId: String?
    ): String {
        val roundedBounds = Bounds(
            left = (bounds.left / 10) * 10,
            top = (bounds.top / 10) * 10,
            right = (bounds.right / 10) * 10,
            bottom = (bounds.bottom / 10) * 10
        )
        val combined = "$resourceId|$className|$text|$roundedBounds|$parentId"
        return hashString(combined)
    }

    private fun hashString(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun classifySemanticType(
        className: String,
        isClickable: Boolean,
        isEditable: Boolean,
        isCheckable: Boolean,
        isScrollable: Boolean
    ): String {
        return when {
            className in BUTTON_CLASSES || isClickable -> "button"
            className in INPUT_CLASSES || isEditable -> "input"
            className in TEXT_CLASSES -> "text"
            className in IMAGE_CLASSES -> "image"
            className in LIST_CLASSES -> "list"
            className in SCROLL_CLASSES || isScrollable -> "scroll"
            isCheckable -> "checkbox"
            else -> "container"
        }
    }

    private fun generateAiDescription(
        semanticType: String,
        text: String,
        contentDesc: String,
        isClickable: Boolean,
        isEditable: Boolean,
        isCheckable: Boolean,
        isChecked: Boolean,
        isScrollable: Boolean,
        isEnabled: Boolean,
        isVisible: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts.add(semanticType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })

        when {
            text.isNotEmpty() -> parts.add("\"$text\"")
            contentDesc.isNotEmpty() -> parts.add("\"$contentDesc\"")
        }

        when {
            isClickable -> parts.add("(clickable)")
            isEditable -> parts.add("(editable)")
            isCheckable -> parts.add(if (isChecked) "(checked)" else "(unchecked)")
            isScrollable -> parts.add("(scrollable)")
        }

        if (!isEnabled) parts.add("[disabled]")
        if (!isVisible) parts.add("[hidden]")

        return parts.joinToString(" ")
    }

    fun findElements(
        tree: UiTree,
        text: String? = null,
        resourceId: String? = null,
        contentDesc: String? = null,
        className: String? = null,
        visibleOnly: Boolean = true
    ): List<UiElement> {
        return tree.elements.filter { element ->
            if (visibleOnly && !element.visible) return@filter false

            if (text != null && !(element.text?.contains(text, ignoreCase = true) == true)) {
                return@filter false
            }

            if (resourceId != null && !(element.resourceId?.endsWith(resourceId) == true)) {
                return@filter false
            }

            if (contentDesc != null && !(element.contentDescription?.contains(contentDesc, ignoreCase = true) == true)) {
                return@filter false
            }

            if (className != null && !(element.className?.endsWith(className) == true)) {
                return@filter false
            }

            true
        }
    }
}
