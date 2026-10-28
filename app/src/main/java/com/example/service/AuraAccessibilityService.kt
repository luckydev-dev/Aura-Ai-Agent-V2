package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.agent.ScreenNode
import kotlinx.coroutines.*

import android.view.accessibility.AccessibilityWindowInfo

class AuraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AuraAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Retrieves the best root node for screen inspection and action.
     * Prioritizes foreground third-party application windows (Instagram, WhatsApp, Chrome, etc.)
     * over Aura's own overlay or assistant panels so Maya sees and acts on the real target app.
     */
    fun getEffectiveRootNode(): AccessibilityNodeInfo? {
        val myPkg = packageName
        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                val externalAppWindows = winList.filter { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.root?.packageName?.toString() != myPkg
                }
                val topExternal = externalAppWindows.maxByOrNull { it.layer }
                if (topExternal?.root != null) {
                    return topExternal.root
                }
            }
        } catch (_: Exception) {}
        return rootInActiveWindow
    }

    /**
     * Finds nodes by text across active window.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = getEffectiveRootNode() ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    fun findNodeByQuery(query: String): AccessibilityNodeInfo? {
        val root = getEffectiveRootNode() ?: return null
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName?.toString() ?: ""
            if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = search(node.getChild(i))
                if (child != null) return child
            }
            return null
        }
        return search(root)
    }

    /**
     * Traverses in-memory active window hierarchy and extracts compact ScreenNodes (sub-30ms).
     * Automatically prioritizes third-party app windows over Aura's own overlay.
     */
    fun extractScreenNodes(): List<ScreenNode> {
        val nodes = mutableListOf<ScreenNode>()
        var counter = 1

        val rootsToScan = mutableListOf<AccessibilityNodeInfo>()
        val myPkg = packageName

        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                val externalWins = winList.filter { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.root?.packageName?.toString() != myPkg
                }.sortedByDescending { it.layer }

                for (w in externalWins) {
                    w.root?.let { rootsToScan.add(it) }
                }
            }
        } catch (_: Exception) {}

        if (rootsToScan.isEmpty()) {
            rootInActiveWindow?.let { rootsToScan.add(it) }
        }

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val cls = node.className?.toString()?.substringAfterLast(".") ?: ""
            val pkg = node.packageName?.toString() ?: ""

            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isFocusable = node.isFocusable

            // Include interactive or informative nodes, skip 0-size invisible bounds
            if (rect.width() > 0 && rect.height() > 0) {
                if (text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank() || isClickable || isEditable || isScrollable) {
                    nodes.add(
                        ScreenNode(
                            text = text.trim(),
                            contentDescription = desc.trim(),
                            resourceId = viewId,
                            className = cls,
                            bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                            centerX = rect.centerX(),
                            centerY = rect.centerY(),
                            isClickable = isClickable,
                            isFocusable = isFocusable,
                            index = counter++,
                            isEditable = isEditable,
                            isScrollable = isScrollable,
                            packageName = pkg
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        for (r in rootsToScan) {
            traverse(r)
        }
        return nodes
    }

    /**
     * Finds the first editable node or chat message field in the active window hierarchy.
     */
    fun findFirstEditableNode(): AccessibilityNodeInfo? {
        val root = getEffectiveRootNode() ?: return null
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val cls = node.className?.toString() ?: ""
            val id = node.viewIdResourceName?.toString() ?: ""
            if (node.isEditable || cls.contains("EditText", ignoreCase = true) || id.contains("entry", ignoreCase = true) || id.contains("input", ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return search(root)
    }

    /**
     * Sets text on an editable node directly via accessibility arguments.
     * If no node is currently focused (e.g. keyboard closed), searches the hierarchy for an editable field.
     */
    fun setTextOnActiveNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null || !targetNode.isEditable) {
            targetNode = findFirstEditableNode()
        }
        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Types text into the chat message field (e.g., WhatsApp, Telegram, Messages)
     * even when the keyboard is closed, waits for the mic button to turn into the Send button,
     * and performs the Send action.
     */
    suspend fun typeAndSendInCurrentChat(text: String): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return Pair(false, "No active window found on screen")

        var inputNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputNode == null || !inputNode.isEditable) {
            inputNode = findFirstEditableNode()
        }
        if (inputNode == null) {
            return Pair(false, "Could not find any message text field on screen")
        }

        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) {
            return Pair(false, "Failed to enter text into message field")
        }

        // WhatsApp dynamically swaps the voice_note_btn to send button when text is present
        delay(250)

        val updatedRoot = rootInActiveWindow ?: root
        val sendNode = findSendButton(updatedRoot)
        if (sendNode != null) {
            val clicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                return Pair(true, "Typed '$text' and clicked Send")
            }
            val rect = Rect()
            sendNode.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                return Pair(true, "Typed '$text' and tapped Send at (${rect.centerX()}, ${rect.centerY()})")
            }
        }

        return Pair(true, "Typed '$text' into chat input field")
    }

    fun findSendButton(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val id = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val t = node.text?.toString()?.lowercase() ?: ""

            val isMic = id.contains("voice_note") || id.contains("mic") || desc.contains("voice message") || desc.contains("mic")
            if (!isMic) {
                if (id.endsWith("/send") || id.contains("send_button") || id.contains("btn_send") ||
                    desc == "send" || desc == "भेजें" || desc.contains("send message") || t == "send" || t == "भेजें"
                ) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return search(rootNode)
    }

    /**
     * Finds the deepest clickable node containing (x, y) or climbs up to a clickable ancestor.
     */
    fun findClickableNodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val ix = x.toInt()
        val iy = y.toInt()
        var bestNode: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(ix, iy)) {
                if (node.isClickable) {
                    bestNode = node
                }
                for (i in 0 until node.childCount) {
                    search(node.getChild(i))
                }
            }
        }
        search(root)

        if (bestNode != null) return bestNode

        fun findDeepest(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(ix, iy)) {
                for (i in 0 until node.childCount) {
                    val childHit = findDeepest(node.getChild(i))
                    if (childHit != null) return childHit
                }
                return node
            }
            return null
        }

        var curr = findDeepest(root)
        while (curr != null) {
            if (curr.isClickable) return curr
            curr = curr.parent
        }
        return null
    }

    /**
     * Directly clicks the clickable node at (x, y) via Accessibility IPC,
     * completely bypassing InputDispatcher and any screen overlays.
     */
    fun performClickAt(x: Float, y: Float): Boolean {
        val node = findClickableNodeAt(x, y) ?: return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return clicked
    }

    /**
     * Performs a click gesture via Accessibility API as fallback to root.
     */
    fun clickAt(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Locks the device screen via system Accessibility API.
     */
    fun lockDevice(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        return false
    }
}
