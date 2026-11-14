package app.operit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.StringWriter

/**
 * Owns the device accessibility surface and publishes it to the application.
 *
 * The service reads the active window and injects gestures. Its snapshot uses
 * the `node` element format the consumer already parses, carrying the `class`,
 * `text`, `content-desc`, `resource-id`, `bounds`, `clickable` and `package`
 * attributes.
 */
class OperitAccessibilityService : AccessibilityService() {

    companion object {
        private const val WAIT_SECONDS = 5L

        @Volatile
        private var instance: OperitAccessibilityService? = null

        /** Reports whether Android granted this service accessibility access. */
        fun isAuthorized(context: Context): Boolean {
            val expected = "${context.packageName}/${OperitAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val entry = splitter.next()
                if (entry.equals(expected, ignoreCase = true) ||
                    entry.equals("${context.packageName}/.OperitAccessibilityService", ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        /** Returns the connected service, waiting briefly for Android to bind it. */
        fun await(context: Context): OperitAccessibilityService? {
            instance?.let { return it }
            if (!isAuthorized(context)) return null
            val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
            while (System.currentTimeMillis() < deadline) {
                instance?.let { return it }
                Thread.sleep(50)
            }
            return instance
        }
    }

    private val worker = Handler(Looper.getMainLooper())
    private val lastWindowName = java.util.concurrent.atomic.AtomicReference<String?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            event.className?.toString()?.takeIf { it.isNotEmpty() }?.let { lastWindowName.set(it) }
        }
    }

    override fun onInterrupt() {
        // No continuous gesture is owned by this service.
    }

    /** Serializes the active window as the consumer expects. */
    fun uiHierarchy(): String {
        val root = rootInActiveWindow ?: throw IllegalStateException("No active window")
        val writer = StringWriter()
        writer.write("<hierarchy rotation=\"0\">")
        appendNode(writer, root)
        writer.write("</hierarchy>")
        return writer.toString()
    }

    /** Writes one node and its descendants with the shared attribute names. */
    private fun appendNode(writer: StringWriter, node: AccessibilityNodeInfo) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        writer.write("<node")
        writer.write(" class=\"${escape(node.className)}\"")
        writer.write(" text=\"${escape(node.text)}\"")
        writer.write(" content-desc=\"${escape(node.contentDescription)}\"")
        writer.write(" resource-id=\"${escape(node.viewIdResourceName)}\"")
        writer.write(" package=\"${escape(node.packageName)}\"")
        writer.write(" clickable=\"${node.isClickable}\"")
        writer.write(" bounds=\"[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\"")
        writer.write(">")
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            appendNode(writer, child)
        }
        writer.write("</node>")
    }

    /** Escapes one attribute value for the snapshot parser. */
    private fun escape(value: CharSequence?): String {
        val text = value?.toString() ?: return ""
        val builder = StringBuilder(text.length)
        for (character in text) {
            when (character) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    /** Dispatches one tap at screen coordinates. */
    fun performClick(x: Int, y: Int): Boolean = dispatchGesture(x, y, x, y, 50L)

    /** Dispatches one long press at screen coordinates. */
    fun performLongPress(x: Int, y: Int): Boolean = dispatchGesture(x, y, x, y, 600L)

    /** Dispatches one swipe between two screen points. */
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
        dispatchGesture(startX, startY, endX, endY, duration.coerceAtLeast(1L))

    /** Sends one pointer gesture and waits for the system to accept it. */
    private fun dispatchGesture(
        startX: Int, startY: Int, endX: Int, endY: Int, duration: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val outcome = AtomicResult()
        // The gesture must start on the main thread; the caller waits for the
        // system callback so a rejected gesture is reported instead of ignored.
        val posted = worker.post {
            try {
                val accepted = dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        outcome.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        outcome.complete(false)
                    }
                }, null)
                if (!accepted) outcome.complete(false)
            } catch (failure: Throwable) {
                outcome.complete(false)
            }
        }
        if (!posted) return false
        return outcome.await(WAIT_SECONDS * 1000)
    }

    /** Carries one gesture outcome out of the callback. */
    private class AtomicResult {
        private val lock = java.lang.Object()
        private var done = false
        private var value = false

        /** Records the first outcome. */
        fun complete(result: Boolean) {
            synchronized(lock) {
                if (done) return
                done = true
                value = result
                lock.notifyAll()
            }
        }

        /** Waits for the outcome. */
        fun await(timeoutMillis: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMillis
            synchronized(lock) {
                while (!done) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) return false
                    lock.wait(remaining)
                }
                return value
            }
        }
    }

    /** Performs one Android global action. */
    fun runGlobalAction(actionId: Int): Boolean = performGlobalAction(actionId)

    /** Returns the identifier of the focused input node as its bounds token. */
    fun findFocusedNodeId(): String? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val bounds = Rect().also { focused.getBoundsInScreen(it) }
        return "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
    }

    /** Replaces the text of the node addressed by its bounds token. */
    fun setTextOnNode(nodeId: String, text: String): Boolean {
        val node = findNodeByBounds(nodeId) ?: return false
        val argument = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argument)
    }

    /** Finds the node whose screen bounds equal the given token. */
    private fun findNodeByBounds(nodeId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val expected = parseBounds(nodeId) ?: return null
        return findNodeByBounds(root, expected)
    }

    /** Walks the tree for a node with matching bounds. */
    private fun findNodeByBounds(node: AccessibilityNodeInfo, expected: Rect): AccessibilityNodeInfo? {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds == expected) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNodeByBounds(child, expected)?.let { return it }
        }
        return null
    }

    /** Parses the `[left,top][right,bottom]` bounds token. */
    private fun parseBounds(token: String): Rect? {
        val numbers = Regex("-?\\d+").findAll(token).map { it.value.toInt() }.toList()
        if (numbers.size < 4) return null
        return Rect(numbers[0], numbers[1], numbers[2], numbers[3])
    }

    /** Reports whether this service still holds accessibility access. */
    fun isAccessibilityServiceEnabled(): Boolean = instance === this

    /** Returns the focused window's activity name when it is known. */
    fun currentActivityName(): String? = lastWindowName.get()?.takeIf { it.isNotEmpty() }

    /**
     * Reports whether this package can capture the screen.
     *
     * The platform hands back a hardware buffer whose pixels are only readable
     * through a hidden API, so the provider does not encode images itself.
     */
    fun takeScreenshot(path: String, format: String): Boolean = false
}
