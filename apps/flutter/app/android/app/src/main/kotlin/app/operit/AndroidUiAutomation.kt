package app.operit

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Drives the accessibility service that lives inside this application.
 *
 * The service used to answer over AIDL from a separate package. It now ships in
 * this APK, so the same operations run against the local service instance
 * without a binder hop or a second installation.
 */
class AndroidUiAutomation(private val context: Context) {
    private val lock = Any()

    /** Returns the connected service, waiting briefly for Android to bind it. */
    private fun requireService(): OperitAccessibilityService =
        OperitAccessibilityService.await(context)
            ?: error("Enable the Operit accessibility service in Android settings")

    /** Dispatches UI operations against the local accessibility service. */
    fun execute(request: JSONObject): JSONObject = synchronized(lock) {
        val service = requireService()
        check(service.isAccessibilityServiceEnabled()) {
            "Enable the Operit accessibility service in Android settings"
        }
        val operation = request.getString("operation")
        val params = request.getJSONObject("parameters")
        if (operation == "get_page_info") return@synchronized readPage(service)
        val success = when (operation) {
            "tap" -> service.performClick(params.getInt("x"), params.getInt("y"))
            "long_press" -> service.performLongPress(params.getInt("x"), params.getInt("y"))
            "swipe" -> service.performSwipe(params.getInt("start_x"), params.getInt("start_y"), params.getInt("end_x"), params.getInt("end_y"), params.getLong("duration"))
            "set_input_text" -> {
                val nodeId = if (params.has("resource_id")) findBounds(service.uiHierarchy(), params.getString("resource_id")) else service.findFocusedNodeId()
                check(!nodeId.isNullOrEmpty()) { "No accessible input element is focused" }
                service.setTextOnNode(nodeId, params.getString("text"))
            }
            "press_key" -> {
                val action = when (params.getString("key_code").uppercase()) {
                    "BACK", "KEYCODE_BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "HOME", "KEYCODE_HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    else -> error("The accessibility service supports BACK, HOME, RECENTS, NOTIFICATIONS and QUICK_SETTINGS global keys")
                }
                service.runGlobalAction(action)
            }
            else -> error("Unknown UI operation: $operation")
        }
        check(success) { "Accessibility service rejected $operation" }
        val gestureDuration = when (operation) {
            "tap" -> 50L
            "long_press" -> 600L
            "swipe" -> params.getLong("duration")
            else -> 0L
        }
        if (gestureDuration > 0) SystemClock.sleep(gestureDuration)
        JSONObject()
    }

    /** Translates a resource ID to the bounds token required by the service. */
    private fun findBounds(xml: String, resourceId: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val matches = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node" && parser.getAttributeValue(null, "resource-id") == resourceId) {
                matches.add(parser.getAttributeValue(null, "bounds") ?: error("Selected input has no bounds"))
            }
            parser.next()
        }
        check(matches.size == 1) { "Select one unique input resource ID" }
        return matches.single()
    }

    /** Parses one XML snapshot into the common node format with traversal limits. */
    private fun readPage(service: OperitAccessibilityService): JSONObject {
        val xml = service.uiHierarchy()
        check(xml.isNotBlank()) { "Accessibility service returned no hierarchy" }
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val roots = JSONArray()
        val stack = ArrayDeque<JSONObject?>()
        var packageName = ""
        var count = 0
        var truncated = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                if (count >= 4000 || stack.size >= 40 || (stack.isNotEmpty() && stack.last() == null)) {
                    truncated = true
                    stack.addLast(null)
                } else {
                    count++
                    val node = JSONObject()
                        .put("className", parser.getAttributeValue(null, "class") ?: JSONObject.NULL)
                        .put("text", parser.getAttributeValue(null, "text") ?: JSONObject.NULL)
                        .put("contentDesc", parser.getAttributeValue(null, "content-desc") ?: JSONObject.NULL)
                        .put("resourceId", parser.getAttributeValue(null, "resource-id") ?: JSONObject.NULL)
                        .put("bounds", parser.getAttributeValue(null, "bounds") ?: JSONObject.NULL)
                        .put("isClickable", parser.getAttributeValue(null, "clickable") == "true")
                        .put("children", JSONArray())
                    if (stack.isEmpty()) roots.put(node) else stack.last()!!.getJSONArray("children").put(node)
                    if (packageName.isEmpty()) packageName = parser.getAttributeValue(null, "package") ?: ""
                    stack.addLast(node)
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "node") {
                check(stack.isNotEmpty()) { "Invalid accessibility hierarchy nesting" }
                stack.removeLast()
            }
            parser.next()
        }
        check(roots.length() > 0) { "Accessibility hierarchy contains no nodes" }
        val root = JSONObject().put("className", "surface").put("text", JSONObject.NULL)
            .put("contentDesc", JSONObject.NULL).put("resourceId", JSONObject.NULL).put("bounds", JSONObject.NULL)
            .put("isClickable", false).put("children", roots)
        return JSONObject().put("packageName", packageName).put("activityName", service.currentActivityName())
            .put("uiElements", root).put("scope", "desktop").put("truncated", truncated)
    }

    /** Keeps the historical owner contract; the service lifecycle drives the state. */
    fun release() = Unit
}
