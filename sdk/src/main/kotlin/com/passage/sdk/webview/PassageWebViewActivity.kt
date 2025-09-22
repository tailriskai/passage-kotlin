package com.passage.sdk.webview

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.passage.sdk.PassageConstants
import com.passage.sdk.PassageConfig
import com.passage.sdk.PassageSDK
import com.passage.sdk.logging.PassageLogger
import com.passage.sdk.remote.RemoteControlManager
import com.passage.sdk.utils.ScreenshotCapture
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.util.*

/**
 * Activity that manages dual WebViews for Passage SDK
 * Implements the same dual-WebView architecture as the Swift SDK
 */
class PassageWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PassageWebViewActivity"
        private const val JS_INTERFACE_NAME = "PassageAndroid"
    }

    // WebView instances
    private lateinit var uiWebView: WebView
    private lateinit var automationWebView: WebView
    private lateinit var containerView: FrameLayout

    // Current active WebView
    private var currentWebView: WebView? = null
    private var currentWebViewType: String = PassageConstants.WebViewTypes.UI

    // Configuration
    private lateinit var config: PassageConfig
    private var intentToken: String = ""
    private var initialUrl: String = ""

    // Remote control
    private var remoteControl: RemoteControlManager? = null

    // State management
    private var isClosing = false
    private var hasLoadedAutomationUrl = false
    private var globalJavascript: String = ""
    private var automationUserAgent: String? = null
    private var integrationUrl: String? = null
    private var closeButtonPressCount = 0
    private var wasShowingAutomationBeforeClose = false

    // WebView clients
    private lateinit var uiWebViewClient: PassageWebViewClient
    private lateinit var automationWebViewClient: PassageWebViewClient

    // Broadcast receiver for internal communication
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            PassageLogger.info(TAG, "========== BROADCAST RECEIVED ==========")
            PassageLogger.info(TAG, "Action: $action")

            when (action) {
                PassageConstants.BroadcastActions.NAVIGATE -> {
                    PassageLogger.info(TAG, "Handling NAVIGATE broadcast")
                    handleNavigateBroadcast(intent)
                }
                PassageConstants.BroadcastActions.NAVIGATE_IN_AUTOMATION -> {
                    PassageLogger.info(TAG, "Handling NAVIGATE_IN_AUTOMATION broadcast")
                    handleNavigateInAutomationBroadcast(intent)
                }
                PassageConstants.BroadcastActions.INJECT_SCRIPT -> {
                    PassageLogger.info(TAG, "Handling INJECT_SCRIPT broadcast")
                    handleInjectScriptBroadcast(intent)
                }
                PassageConstants.BroadcastActions.SHOW_UI_WEBVIEW -> {
                    PassageLogger.info(TAG, "Handling SHOW_UI_WEBVIEW broadcast")
                    showUIWebView()
                }
                PassageConstants.BroadcastActions.SHOW_AUTOMATION_WEBVIEW -> {
                    PassageLogger.info(TAG, "Handling SHOW_AUTOMATION_WEBVIEW broadcast")
                    showAutomationWebView()
                }
                PassageConstants.BroadcastActions.COLLECT_PAGE_DATA -> {
                    PassageLogger.info(TAG, "Handling COLLECT_PAGE_DATA broadcast")
                    handleCollectPageDataBroadcast(intent)
                }
                PassageConstants.BroadcastActions.GET_CURRENT_URL -> {
                    PassageLogger.info(TAG, "Handling GET_CURRENT_URL broadcast")
                    handleGetCurrentUrlBroadcast(intent)
                }
                else -> {
                    PassageLogger.warn(TAG, "Unknown broadcast action: $action")
                }
            }
        }
    }

    // Coroutine scope for async operations
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val passageReadyCheckScript = "(function(){return !!(window.passage && window.passage.postMessage);})()"
    private val passageReadyMaxRetries = 5
    private val passageReadyRetryDelayMs = 200L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
            PassageLogger.info(TAG, "Global WebView remote debugging enabled")
        }

        // Extract configuration from intent
        config = intent.getSerializableExtra(PassageConstants.IntentExtras.CONFIG) as? PassageConfig
            ?: PassageConfig()
        intentToken = intent.getStringExtra(PassageConstants.IntentExtras.INTENT_TOKEN) ?: ""
        initialUrl = intent.getStringExtra("url") ?: ""

        // Get RemoteControlManager from PassageSDK
        remoteControl = PassageSDK.getRemoteControl()

        PassageLogger.info(TAG, "========== WEBVIEW ACTIVITY CREATED ==========")
        PassageLogger.info(TAG, "Token length: ${intentToken.length}")
        PassageLogger.info(TAG, "Initial URL: ${PassageLogger.truncateUrl(initialUrl, 100)}")

        // Set up layout
        setupLayout()

        // Create WebViews
        createWebViews()

        // Register broadcast receiver
        registerBroadcastReceivers()

        // Make activity non-dismissible (like Swift's isModalInPresentation)
        setFinishOnTouchOutside(false)

        // Load initial URL in UI WebView
        uiWebView.loadUrl(initialUrl)
        PassageLogger.info(TAG, "UI WebView loading: $initialUrl")

        // Set current WebView to UI
        showUIWebView()
    }

    private fun setupLayout() {
        containerView = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#f5f5f5")) // Match Swift SDK background
        }
        setContentView(containerView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViews() {
        PassageLogger.info(TAG, "Creating dual WebViews")

        // Create UI WebView
        uiWebView = createWebView("ui").apply {
            uiWebViewClient = PassageWebViewClient(PassageConstants.WebViewTypes.UI) { url ->
                handleNavigationComplete(PassageConstants.WebViewTypes.UI, url)
            }
            webViewClient = uiWebViewClient
            webChromeClient = PassageWebChromeClient(PassageConstants.WebViewTypes.UI)
            addJavascriptInterface(PassageJavaScriptInterface(PassageConstants.WebViewTypes.UI), JS_INTERFACE_NAME)
        }

        // Create Automation WebView
        automationWebView = createWebView("automation").apply {
            automationWebViewClient = PassageWebViewClient(PassageConstants.WebViewTypes.AUTOMATION) { url ->
                handleNavigationComplete(PassageConstants.WebViewTypes.AUTOMATION, url)

                // Check for success URLs
                remoteControl?.checkNavigationEnd(url)

                // Inject global JavaScript on every navigation
                if (globalJavascript.isNotEmpty()) {
                    PassageLogger.info(TAG, "Injecting global JavaScript (${globalJavascript.length} chars)")
                    evaluateJavascript(globalJavascript, null)
                }
            }
            webViewClient = automationWebViewClient
            webChromeClient = PassageWebChromeClient(PassageConstants.WebViewTypes.AUTOMATION)
            addJavascriptInterface(PassageJavaScriptInterface(PassageConstants.WebViewTypes.AUTOMATION), JS_INTERFACE_NAME)
        }

        // Add WebViews to container (initially hidden)
        containerView.addView(uiWebView)
        containerView.addView(automationWebView)
        automationWebView.visibility = View.GONE

        // Wire screenshot capture function to RemoteControlManager
        remoteControl?.setCaptureImageFunction {
            ScreenshotCapture.captureWithOptimization(automationWebView, remoteControl?.getImageOptimizationParameters())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(type: String): WebView {
        return WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    WebView.setWebContentsDebuggingEnabled(true)
                    PassageLogger.info(TAG, "WebView remote debugging enabled for $type webview")
                }

                // Set user agent if needed
                if (type == "automation" && automationUserAgent != null) {
                    userAgentString = automationUserAgent
                }

                // Cache settings
                cacheMode = WebSettings.LOAD_DEFAULT

                // Modern settings
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }

            // Set background color
            setBackgroundColor(Color.parseColor("#f5f5f5"))
        }
    }

    private fun registerBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction(PassageConstants.BroadcastActions.NAVIGATE)
            addAction(PassageConstants.BroadcastActions.NAVIGATE_IN_AUTOMATION)
            addAction(PassageConstants.BroadcastActions.INJECT_SCRIPT)
            addAction(PassageConstants.BroadcastActions.SHOW_UI_WEBVIEW)
            addAction(PassageConstants.BroadcastActions.SHOW_AUTOMATION_WEBVIEW)
            addAction(PassageConstants.BroadcastActions.GET_CURRENT_URL)
            addAction(PassageConstants.BroadcastActions.COLLECT_PAGE_DATA)
        }

        // Use LocalBroadcastManager to match RemoteControlManager's broadcast sending
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter)
        PassageLogger.info(TAG, "Registered LocalBroadcastManager receiver for WebView switching")
    }

    // WebView switching methods

    private fun showUIWebView() {
        PassageLogger.info(TAG, "========== SWITCHING TO UI WEBVIEW ==========")
        PassageLogger.info(TAG, "UI WebView visibility: ${uiWebView.visibility}")
        PassageLogger.info(TAG, "Automation WebView visibility: ${automationWebView.visibility}")

        runOnUiThread {
            uiWebView.visibility = View.VISIBLE
            automationWebView.visibility = View.GONE
            currentWebView = uiWebView
            currentWebViewType = PassageConstants.WebViewTypes.UI

            PassageLogger.info(TAG, "UI WebView switch complete - UI visible: ${uiWebView.visibility == View.VISIBLE}, Automation hidden: ${automationWebView.visibility == View.GONE}")

            // Notify SDK of WebView change
            PassageSDK.handleMessage(mapOf(
                "type" to "webViewChanged",
                "webViewType" to PassageConstants.WebViewTypes.UI
            ))
        }
    }

    private fun showAutomationWebView() {
        PassageLogger.info(TAG, "========== SWITCHING TO AUTOMATION WEBVIEW ==========")
        PassageLogger.info(TAG, "UI WebView visibility: ${uiWebView.visibility}")
        PassageLogger.info(TAG, "Automation WebView visibility: ${automationWebView.visibility}")
        PassageLogger.info(TAG, "Integration URL: $integrationUrl")
        PassageLogger.info(TAG, "Has loaded automation URL: $hasLoadedAutomationUrl")

        // Load integration URL if not loaded yet
        if (!hasLoadedAutomationUrl && integrationUrl != null) {
            PassageLogger.info(TAG, "Loading automation URL: $integrationUrl")
            automationWebView.loadUrl(integrationUrl!!)
            hasLoadedAutomationUrl = true
        }

        runOnUiThread {
            automationWebView.visibility = View.VISIBLE
            uiWebView.visibility = View.GONE
            currentWebView = automationWebView
            currentWebViewType = PassageConstants.WebViewTypes.AUTOMATION

            PassageLogger.info(TAG, "Automation WebView switch complete - Automation visible: ${automationWebView.visibility == View.VISIBLE}, UI hidden: ${uiWebView.visibility == View.GONE}")

            // Notify SDK of WebView change
            PassageSDK.handleMessage(mapOf(
                "type" to "webViewChanged",
                "webViewType" to PassageConstants.WebViewTypes.AUTOMATION
            ))
        }
    }

    // Broadcast handlers

    private fun handleNavigateBroadcast(intent: Intent) {
        val action = intent.getStringExtra("action")

        when (action) {
            "close" -> {
                PassageLogger.info(TAG, "Close action received")
                finish()
            }
            "updateConfiguration" -> {
                val userAgent = intent.getStringExtra("userAgent")
                integrationUrl = intent.getStringExtra("integrationUrl")

                PassageLogger.info(TAG, "Configuration update received")
                PassageLogger.info(TAG, "User Agent: ${userAgent ?: "default"}")
                PassageLogger.info(TAG, "Integration URL: ${integrationUrl ?: "nil"}")

                // Update automation WebView user agent
                if (userAgent != null) {
                    automationUserAgent = userAgent
                    automationWebView.settings.userAgentString = userAgent
                }

                // Load integration URL if available
                if (integrationUrl != null && !hasLoadedAutomationUrl) {
                    PassageLogger.info(TAG, "Loading integration URL in automation WebView")
                    automationWebView.loadUrl(integrationUrl!!)
                    hasLoadedAutomationUrl = true
                }
            }
            else -> {
                val url = intent.getStringExtra("url")
                val targetWebView = intent.getStringExtra("targetWebView")
                if (url != null) {
                    PassageLogger.info(
                        TAG,
                        "Navigate to URL: ${PassageLogger.truncateUrl(url, 100)} (target=${targetWebView ?: "current"})"
                    )
                    when (targetWebView) {
                        PassageConstants.WebViewTypes.UI -> uiWebView.loadUrl(url)
                        PassageConstants.WebViewTypes.AUTOMATION -> automationWebView.loadUrl(url)
                        else -> currentWebView?.loadUrl(url)
                    }
                }
            }
        }
    }

    private fun handleNavigateInAutomationBroadcast(intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val commandId = intent.getStringExtra("commandId") ?: return

        PassageLogger.info(TAG, "Navigate in automation: $url (command: $commandId)")

        // Store current command ID for result tracking
        automationWebViewClient.currentCommandId = commandId

        // Navigate automation WebView
        automationWebView.loadUrl(url)
    }

    private fun handleInjectScriptBroadcast(intent: Intent) {
        val script = intent.getStringExtra("script") ?: return
        val commandId = intent.getStringExtra("commandId") ?: return
        val commandType = intent.getStringExtra("commandType") ?: return

        PassageLogger.info(TAG, "Inject script for command: $commandId (type: $commandType)")

        // Check if this is an async script that uses window.passage.postMessage
        val usesWindowPassage = script.contains("window.passage.postMessage")
        val isAsyncScript = script.contains("async function") ||
                           commandType.equals("Wait", ignoreCase = true) ||
                           commandType.equals("wait", ignoreCase = true)

        PassageLogger.debug(TAG, "Script detection for $commandId:")
        PassageLogger.debug(TAG, "  usesWindowPassage: $usesWindowPassage")
        PassageLogger.debug(TAG, "  isAsyncScript: $isAsyncScript")
        PassageLogger.debug(TAG, "  commandType: '$commandType'")
        PassageLogger.debug(TAG, "  script contains async function: ${script.contains("async function")}")
        PassageLogger.debug(TAG, "  script contains window.passage.postMessage: ${script.contains("window.passage.postMessage")}")
        val scriptPreview = if (script.length > 200) script.substring(0, 200) + "..." else script
        PassageLogger.debug(TAG, "  script preview: $scriptPreview")

        ensurePassageBridgeReady(commandId) {
            if (isAsyncScript && usesWindowPassage) {
                PassageLogger.info(TAG, "Detected async script for command: $commandId - waiting for postMessage result")

                // For async scripts, inject with "; undefined;" suffix to prevent return value
                val scriptWithUndefined = "$script; undefined;"

                automationWebView.evaluateJavascript(scriptWithUndefined) { result ->
                    PassageLogger.debug(TAG, "Async script injection completed for command: $commandId (result ignored)")
                    PassageLogger.debug(TAG, "Waiting for postMessage from script...")
                    // Don't process the evaluateJavaScript result for async scripts
                    // Wait for the script to call window.passage.postMessage()
                }
            } else {
                PassageLogger.info(TAG, "Detected sync script for command: $commandId - using evaluateJavaScript result")

                automationWebView.evaluateJavascript(script) { result ->
                    PassageLogger.info(TAG, "Sync script execution completed for command: $commandId")
                    val truncatedResult = PassageLogger.truncateData(result ?: "null", PassageConstants.Logging.MAX_HTML_LENGTH)
                    PassageLogger.debug(TAG, "Raw result (truncated): '$truncatedResult'")

                    // Unquote the JavaScript result
                    val unquotedResult = unquoteJavaScriptResult(result)
                    val truncatedUnquoted = PassageLogger.truncateData(unquotedResult ?: "null", PassageConstants.Logging.MAX_HTML_LENGTH)
                    PassageLogger.debug(TAG, "Unquoted result (truncated): '$truncatedUnquoted'")

                    // Determine success based on the unquoted result for sync scripts
                    val success = when (unquotedResult) {
                        null -> false
                        "null" -> false
                        "true" -> true
                        "false" -> false
                        else -> true // Non-null result indicates success for sync scripts
                    }

                    PassageLogger.info(TAG, "Sync command $commandId success: $success")

                    // Send result via LocalBroadcastManager
                    val resultIntent = Intent(PassageConstants.BroadcastActions.SCRIPT_EXECUTION_RESULT).apply {
                        putExtra("commandId", commandId)
                        putExtra("success", success)
                        putExtra("result", unquotedResult)
                    }
                    LocalBroadcastManager.getInstance(this@PassageWebViewActivity).sendBroadcast(resultIntent)
                }
            }
        }
    }

    private fun ensurePassageBridgeReady(commandId: String, retryCount: Int = 0, onReady: () -> Unit) {
        automationWebView.post {
            automationWebView.evaluateJavascript(passageReadyCheckScript) { result ->
                val normalized = result?.replace("\"", "")?.trim()?.lowercase(Locale.US)
                val isReady = normalized == "true"

                if (isReady) {
                    PassageLogger.debug(TAG, "window.passage ready for command: $commandId (retry=$retryCount)")
                    automationWebView.post { onReady() }
                } else {
                    if (retryCount >= passageReadyMaxRetries) {
                        PassageLogger.warn(
                            TAG,
                            "window.passage not ready after $passageReadyMaxRetries retries for command: $commandId. Proceeding after reinjecting bridge"
                        )
                        automationWebView.evaluateJavascript(
                            getJavaScriptBridge(PassageConstants.WebViewTypes.AUTOMATION),
                            null
                        )
                        automationWebView.post { onReady() }
                    } else {
                        PassageLogger.warn(
                            TAG,
                            "window.passage not ready (attempt ${retryCount + 1}/$passageReadyMaxRetries) for command: $commandId. Reinforcing bridge injection"
                        )
                        automationWebView.evaluateJavascript(
                            getJavaScriptBridge(PassageConstants.WebViewTypes.AUTOMATION),
                            null
                        )
                        automationWebView.postDelayed(
                            { ensurePassageBridgeReady(commandId, retryCount + 1, onReady) },
                            passageReadyRetryDelayMs
                        )
                    }
                }
            }
        }
    }

    private fun handleCollectPageDataBroadcast(intent: Intent) {
        val script = intent.getStringExtra("script") ?: return

        PassageLogger.info(TAG, "Collecting page data from automation webview")

        runOnUiThread {
            automationWebView.evaluateJavascript(script) { result ->
                PassageLogger.debug(TAG, "Page data collection script completed")
                // The JavaScript will handle posting the message back via window.passage.postMessage
                // No need to process the result here as it will come through the postMessage interface
            }
        }
    }

    private fun handleGetCurrentUrlBroadcast(intent: Intent) {
        val currentUrl = automationWebView.url ?: currentWebView?.url ?: initialUrl
        val screenshot = intent.getStringExtra("screenshot")

        val reply = Intent(PassageConstants.BroadcastActions.SEND_BROWSER_STATE).apply {
            putExtra("url", currentUrl)
            screenshot?.let { putExtra("screenshot", it) }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(reply)
        PassageLogger.info(TAG, "SEND_BROWSER_STATE broadcast dispatched with URL: ${PassageLogger.truncateUrl(currentUrl ?: "", 100)}")
    }

    // Navigation handling

    private fun handleNavigationComplete(webViewType: String, url: String) {
        PassageLogger.info(TAG, "Navigation complete in $webViewType: ${PassageLogger.truncateUrl(url, 100)}")

        // Send navigation completed broadcast via LocalBroadcastManager
        val intent = Intent(PassageConstants.BroadcastActions.NAVIGATION_COMPLETED).apply {
            putExtra("url", url)
            putExtra("webViewType", webViewType)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // JavaScript Interface for WebView communication

    inner class PassageJavaScriptInterface(private val webViewType: String) {

        @JavascriptInterface
        fun postMessage(message: String) {
            PassageLogger.debug(TAG, "JavaScript message received from $webViewType: $message")

            try {
                // The message might be a double-encoded JSON string, try to handle both cases
                val jsonMessage = try {
                    JSONObject(message)
                } catch (e: JSONException) {
                    // If direct parsing fails, the message might be a JSON string that needs to be parsed differently
                    PassageLogger.debug(TAG, "Direct JSON parsing failed, trying alternative parsing for: $message")

                    // Check if message is a string representation of JSON
                    if (message.startsWith("{") && message.endsWith("}")) {
                        JSONObject(message)
                    } else {
                        // If it's a quoted JSON string, try to parse the quoted content
                        if (message.startsWith("\"") && message.endsWith("\"")) {
                            val unquoted = message.substring(1, message.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                            JSONObject(unquoted)
                        } else {
                            throw e // Re-throw original exception if we can't handle it
                        }
                    }
                }

                val type = jsonMessage.optString("type")
                PassageLogger.debug(TAG, "Parsed message type: $type from $webViewType")

                // Convert to Map for SDK handling
                val messageMap = mutableMapOf<String, Any>()
                jsonMessage.keys().forEach { key ->
                    val keyStr = key as? String ?: return@forEach
                    messageMap[keyStr] = jsonMessage.get(keyStr)
                }
                messageMap["webViewType"] = webViewType

                val handledLocally = handlePassageBridgeMessage(type, messageMap)

                if (!handledLocally) {
                    // Handle message in SDK
                    PassageSDK.handleMessage(messageMap)
                }

            } catch (e: Exception) {
                PassageLogger.error(TAG, "Error parsing JavaScript message: '$message'", e)
            }
        }

        @JavascriptInterface
        fun log(level: String, message: String) {
            when (level.lowercase()) {
                "debug" -> PassageLogger.debug(TAG, "[WebView-$webViewType] $message")
                "info" -> PassageLogger.info(TAG, "[WebView-$webViewType] $message")
                "warn" -> PassageLogger.warn(TAG, "[WebView-$webViewType] $message")
                "error" -> PassageLogger.error(TAG, "[WebView-$webViewType] $message")
            }
        }
    }

    private fun handlePassageBridgeMessage(type: String?, message: Map<String, Any>): Boolean {
        val messageType = type ?: return false

        return when (messageType) {
            "CLOSE_CONFIRMED" -> {
                handleCloseConfirmed()
                true
            }
            "CLOSE_CANCELLED" -> {
                handleCloseCancelled()
                true
            }
            "PASSAGE_MODAL_CLOSE" -> {
                handleJsRequestedClose()
                true
            }
            PassageConstants.MessageTypes.MESSAGE -> {
                val innerType = extractInnerMessageType(message["data"])
                when (innerType) {
                    "CLOSE_CONFIRMED" -> {
                        handleCloseConfirmed()
                        true
                    }
                    "CLOSE_CANCELLED" -> {
                        handleCloseCancelled()
                        true
                    }
                    "PASSAGE_MODAL_CLOSE" -> {
                        handleJsRequestedClose()
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun extractInnerMessageType(dataNode: Any?): String? {
        return when (dataNode) {
            is Map<*, *> -> dataNode["type"] as? String
            is JSONObject -> dataNode.optString("type").takeIf { it.isNotBlank() }
            is String -> {
                val trimmed = dataNode.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        JSONObject(trimmed).optString("type").takeIf { it.isNotBlank() }
                    } catch (error: Exception) {
                        PassageLogger.warn(TAG, "Failed to parse message data JSON: ${error.message}")
                        null
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun handleJsRequestedClose() {
        PassageLogger.info(TAG, "[Bridge] PASSAGE_MODAL_CLOSE event received - closing modal immediately")
        runOnUiThread { closeModal("js_post_message") }
    }

    private fun handleCloseConfirmed() {
        PassageLogger.info(TAG, "[Bridge] Close confirmation received from WebView - closing modal")
        runOnUiThread { closeModal("close_confirmed") }
    }

    private fun handleCloseCancelled() {
        PassageLogger.info(TAG, "[Bridge] Close cancelled by user inside WebView")
        val shouldRestoreAutomation = wasShowingAutomationBeforeClose

        runOnUiThread {
            resetCloseRequestState()
            if (shouldRestoreAutomation) {
                PassageLogger.info(TAG, "[Bridge] Restoring automation WebView after close cancellation")
                showAutomationWebView()
            }
        }
    }

    private inner class PassageWebChromeClient(
        private val webViewType: String
    ) : WebChromeClient() {

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val message = consoleMessage.message()
            val line = consoleMessage.lineNumber()
            val sourceId = consoleMessage.sourceId() ?: "unknown"
            when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> PassageLogger.error(TAG, "[Console:$webViewType] $message (line=$line source=$sourceId)")
                ConsoleMessage.MessageLevel.WARNING -> PassageLogger.warn(TAG, "[Console:$webViewType] $message (line=$line source=$sourceId)")
                ConsoleMessage.MessageLevel.TIP, ConsoleMessage.MessageLevel.LOG -> PassageLogger.info(TAG, "[Console:$webViewType] $message (line=$line source=$sourceId)")
                ConsoleMessage.MessageLevel.DEBUG -> PassageLogger.debug(TAG, "[Console:$webViewType] $message (line=$line source=$sourceId)")
                else -> PassageLogger.info(TAG, "[Console:$webViewType] $message (line=$line source=$sourceId)")
            }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            PassageLogger.warn(TAG, "[$webViewType] JS alert from ${url ?: "unknown"}: ${message ?: ""}")
            return super.onJsAlert(view, url, message, result)
        }
    }

    // WebView client for handling navigation

    inner class PassageWebViewClient(
        private val webViewType: String,
        private val onPageFinished: (String) -> Unit
    ) : WebViewClient() {

        var currentCommandId: String? = null

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            url?.let {
                PassageLogger.info(TAG, "[$webViewType] Page started: ${PassageLogger.truncateUrl(it, 100)}")

                // Check for success URL on navigation start
                if (webViewType == PassageConstants.WebViewTypes.AUTOMATION) {
                    remoteControl?.checkNavigationStart(it)
                }
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            url?.let {
                PassageLogger.info(TAG, "[$webViewType] Page finished: ${PassageLogger.truncateUrl(it, 100)}")
                onPageFinished(it)

                // Inject JavaScript bridge
                view?.evaluateJavascript(getJavaScriptBridge(webViewType), null)

                // Inject global JavaScript for automation WebView
                if (webViewType == PassageConstants.WebViewTypes.AUTOMATION && view != null) {
                    val globalJs = remoteControl?.getGlobalJavascript() ?: ""
                    if (globalJs.isNotEmpty()) {
                        PassageLogger.info(TAG, "========== GLOBAL JAVASCRIPT INJECTION ==========")
                        PassageLogger.info(TAG, "Global JavaScript length: ${globalJs.length} characters")

                        // Detect important components in global JavaScript
                        if (globalJs.contains("NetworkInterceptor")) {
                            PassageLogger.info(TAG, "🌐 Detected NetworkInterceptor in global JavaScript")
                        }
                        if (globalJs.contains("WeakMap")) {
                            PassageLogger.warn(TAG, "⚠️ Detected WeakMap usage in global JavaScript (potential serialization issue)")
                        }
                        if (globalJs.contains("Sentry")) {
                            PassageLogger.info(TAG, "📊 Detected Sentry library in global JavaScript")
                        }
                        if (globalJs.contains("postMessage")) {
                            PassageLogger.warn(TAG, "📨 Detected postMessage usage in global JavaScript")
                        }

                        // Log first 500 characters for debugging
                        val preview = if (globalJs.length > 500) globalJs.substring(0, 500) + "..." else globalJs
                        PassageLogger.debug(TAG, "Global JavaScript preview: $preview")

                        // Wrap global JavaScript with error handling
                        val wrappedJs = wrapGlobalJavaScriptWithErrorHandling(globalJs)
                        view.evaluateJavascript(wrappedJs, null)

                        PassageLogger.info(TAG, "Global JavaScript injection completed")
                    }
                }

                // Send page loaded message
                PassageSDK.handleMessage(mapOf(
                    "type" to PassageConstants.MessageTypes.PAGE_LOADED,
                    "webViewType" to webViewType,
                    "url" to it
                ))
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            PassageLogger.error(TAG, "[$webViewType] WebView error: ${error?.description}")
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false

            // Check if it's an external URL that should open in browser
            if (shouldOpenExternally(url)) {
                PassageLogger.info(TAG, "Opening external URL: $url")
                PassageSDK.handleMessage(mapOf(
                    "type" to PassageConstants.MessageTypes.OPEN_EXTERNAL_URL,
                    "url" to url
                ))
                return true
            }

            return false
        }

        private fun shouldOpenExternally(url: String): Boolean {
            // URLs that should open in external browser
            return url.startsWith("mailto:") ||
                   url.startsWith("tel:") ||
                   url.startsWith("sms:") ||
                   url.startsWith("market:") ||
                   url.startsWith("play.google.com")
        }
    }

    // JavaScript bridge injection
    private fun getJavaScriptBridge(webViewType: String): String {
        return """
            (function() {
                const JS_INTERFACE = '$JS_INTERFACE_NAME';
                const WEB_VIEW_TYPE = '$webViewType';
                const DEFAULT_MESSAGE_TYPE = '${PassageConstants.MessageTypes.MESSAGE}';
                const CLOSE_MESSAGE_TYPE = '${PassageConstants.MessageTypes.CLOSE_MODAL}';
                const NAVIGATE_MESSAGE_TYPE = '${PassageConstants.MessageTypes.NAVIGATE}';
                const SET_TITLE_MESSAGE_TYPE = '${PassageConstants.MessageTypes.SET_TITLE}';
                const SWITCH_WEBVIEW_MESSAGE_TYPE = '${PassageConstants.MessageTypes.SWITCH_WEBVIEW}';

                function isObject(value) {
                    return value !== null && typeof value === 'object';
                }

                function clonePayload(payload) {
                    if (!isObject(payload)) {
                        return payload;
                    }
                    try {
                        return JSON.parse(JSON.stringify(payload));
                    } catch (error) {
                        console.warn('[Passage] Failed to clone payload, sending original:', error);
                        return payload;
                    }
                }

                function sendToNative(payload, fallbackType) {
                    try {
                        if (!window[JS_INTERFACE] || typeof window[JS_INTERFACE].postMessage !== 'function') {
                            console.warn('[Passage] Native bridge not available');
                            return;
                        }

                        let message = clonePayload(payload);
                        if (!isObject(message)) {
                            message = { value: message };
                        }

                        message.type = message.type || fallbackType || DEFAULT_MESSAGE_TYPE;
                        message.webViewType = WEB_VIEW_TYPE;
                        if (!message.timestamp) {
                            message.timestamp = Date.now();
                        }

                        window[JS_INTERFACE].postMessage(JSON.stringify(message));
                    } catch (error) {
                        console.error('[Passage] Failed to send message to native:', error);
                    }
                }

                function sendLog(level, message) {
                    if (window[JS_INTERFACE] && typeof window[JS_INTERFACE].log === 'function') {
                        try {
                            window[JS_INTERFACE].log(level, message);
                        } catch (error) {
                            console.error('[Passage] Failed to forward log message:', error);
                        }
                    }
                }

                window.passage = {
                    initialized: true,
                    webViewType: WEB_VIEW_TYPE,

                    postMessage: function(data) {
                        sendToNative({
                            type: DEFAULT_MESSAGE_TYPE,
                            data: data
                        }, DEFAULT_MESSAGE_TYPE);
                    },

                    navigate: function(url) {
                        sendToNative({
                            type: NAVIGATE_MESSAGE_TYPE,
                            url: url
                        }, NAVIGATE_MESSAGE_TYPE);
                    },

                    close: function() {
                        sendToNative({
                            type: CLOSE_MESSAGE_TYPE
                        }, CLOSE_MESSAGE_TYPE);
                    },

                    setTitle: function(title) {
                        sendToNative({
                            type: SET_TITLE_MESSAGE_TYPE,
                            title: title
                        }, SET_TITLE_MESSAGE_TYPE);
                    },

                    getWebViewType: function() {
                        return WEB_VIEW_TYPE;
                    },

                    isAutomationWebView: function() {
                        return WEB_VIEW_TYPE === '${PassageConstants.WebViewTypes.AUTOMATION}';
                    },

                    isUIWebView: function() {
                        return WEB_VIEW_TYPE === '${PassageConstants.WebViewTypes.UI}';
                    },

                    captureScreenshot: function() {
                        sendToNative({ type: 'captureScreenshot' }, 'captureScreenshot');
                    },

                    log: function(level) {
                        const args = Array.prototype.slice.call(arguments, 1);
                        const message = args.join(' ');
                        sendLog(level, message);
                    },

                    switchWebView: function(targetWebView) {
                        sendToNative({
                            type: SWITCH_WEBVIEW_MESSAGE_TYPE,
                            targetWebView: targetWebView
                        }, SWITCH_WEBVIEW_MESSAGE_TYPE);
                    }
                };

                if (typeof window.webkit !== 'object' || window.webkit === null) {
                    window.webkit = {};
                }

                if (typeof window.webkit.messageHandlers !== 'object' || window.webkit.messageHandlers === null) {
                    window.webkit.messageHandlers = {};
                }

                if (!window.webkit.messageHandlers.passageWebView) {
                    window.webkit.messageHandlers.passageWebView = {
                        postMessage: function(payload) {
                            sendToNative(payload, DEFAULT_MESSAGE_TYPE);
                        }
                    };
                }

                if (!window.__passageMessageHandlerInstalled) {
                    const PASSAGE_MODAL_CLOSE_EVENT = 'PASSAGE_MODAL_CLOSE';

                    function resolveEventType(data) {
                        if (!data) { return null; }
                        if (typeof data === 'string') {
                            try {
                                const parsed = JSON.parse(data);
                                if (parsed && typeof parsed === 'object' && parsed.type) {
                                    return parsed.type;
                                }
                            } catch (parseError) {
                                // String was not JSON; treat the string itself as the type
                            }
                            return data;
                        }
                        if (typeof data === 'object') {
                            return data.type || null;
                        }
                        return null;
                    }

                    function handleWindowMessage(event) {
                        try {
                            const eventType = resolveEventType(event && event.data);
                            if (eventType === PASSAGE_MODAL_CLOSE_EVENT) {
                                sendToNative({
                                    type: CLOSE_MESSAGE_TYPE,
                                    reason: 'js_post_message'
                                }, CLOSE_MESSAGE_TYPE);
                            }
                        } catch (error) {
                            console.error('[Passage] Failed to process window message:', error);
                        }
                    }

                    window.addEventListener('message', handleWindowMessage, false);
                    window.__passageMessageHandlerInstalled = true;
                }

                if (${config.debug}) {
                    const originalLog = console.log;
                    const originalError = console.error;
                    const originalWarn = console.warn;

                    console.log = function() {
                        originalLog.apply(console, arguments);
                        window.passage.log('info', Array.from(arguments).join(' '));
                    };

                    console.error = function() {
                        originalError.apply(console, arguments);
                        window.passage.log('error', Array.from(arguments).join(' '));
                    };

                    console.warn = function() {
                        originalWarn.apply(console, arguments);
                        window.passage.log('warn', Array.from(arguments).join(' '));
                    };
                }

                window.passage.postMessage({ type: 'ready' });
            })();
        """.trimIndent()
    }

    // Global JavaScript wrapper with error handling (similar to Swift SDK)
    private fun wrapGlobalJavaScriptWithErrorHandling(globalScript: String): String {
        return """
            (function() {
                'use strict';

                console.log('[Passage] Starting global JavaScript execution');

                // Create safe WeakMap implementation to handle serialization issues
                function createSafeExecutionContext() {
                    const originalWeakMap = window.WeakMap;

                    const weakMapWarningCounts = {};
                    function logWeakMapWarning(label, message) {
                        const count = weakMapWarningCounts[label] || 0;
                        if (count < 5) {
                            console.warn(message);
                        } else if (count === 5) {
                            console.warn('[Passage] WeakMap warning for ' + label + ' suppressed after 5 occurrences');
                        }
                        weakMapWarningCounts[label] = count + 1;
                    }

                    function SafeWeakMap() {
                        const map = new originalWeakMap();

                        const safeMap = {
                            set: function(key, value) {
                                try {
                                    if (key != null && typeof key === 'object') {
                                        return map.set(key, value);
                                    }
                                    logWeakMapWarning('invalidKey', '[Passage] Invalid WeakMap key (type: ' + typeof key + '), skipping');
                                    return safeMap;
                                } catch (error) {
                                    logWeakMapWarning('setError', '[Passage] WeakMap.set error: ' + error.message);
                                    return safeMap;
                                }
                            },
                            get: function(key) {
                                try {
                                    return map.get(key);
                                } catch (error) {
                                    logWeakMapWarning('getError', '[Passage] WeakMap.get error: ' + error.message);
                                    return undefined;
                                }
                            },
                            has: function(key) {
                                try {
                                    return map.has(key);
                                } catch (error) {
                                    logWeakMapWarning('hasError', '[Passage] WeakMap.has error: ' + error.message);
                                    return false;
                                }
                            },
                            delete: function(key) {
                                try {
                                    return map.delete(key);
                                } catch (error) {
                                    logWeakMapWarning('deleteError', '[Passage] WeakMap.delete error: ' + error.message);
                                    return false;
                                }
                            }
                        };

                        return safeMap;
                    }

                    return SafeWeakMap;
                }

                function executeGlobalScript() {
                    try {
                        console.log('[Passage] Executing global script with WeakMap protection');

                        // Temporarily replace WeakMap with safe version
                        const SafeWeakMap = createSafeExecutionContext();
                        const originalWeakMap = window.WeakMap;
                        window.WeakMap = SafeWeakMap;

                        // Override postMessage to handle serialization issues
                        const originalPostMessage = window.postMessage;
                        window.postMessage = function(message, targetOrigin, transfer) {
                            try {
                                // Ensure message is serializable
                                JSON.stringify(message);
                                return originalPostMessage.call(window, message, targetOrigin, transfer);
                            } catch (error) {
                                console.error('[Passage] PostMessage serialization error:', error.message);
                                console.error('[Passage] Failed to serialize:', typeof message);
                                // Try to send a simplified version
                                const safeMessage = { error: 'Serialization failed', type: typeof message };
                                return originalPostMessage.call(window, safeMessage, targetOrigin, transfer);
                            }
                        };

                        // Execute the actual global script
                        (function() {
                            ${globalScript}
                        }).call(window);

                        console.log('[Passage] Global script execution completed successfully');

                        // Restore original WeakMap after delay
                        setTimeout(function() {
                            console.log('[Passage] Restoring original WeakMap');
                            window.WeakMap = originalWeakMap;
                        }, 1000);

                    } catch (error) {
                        console.error('[Passage] Error executing global JavaScript:', error.message);
                        console.error('[Passage] Stack trace:', error.stack);

                        // Send error notification to native
                        if (window.passage && window.passage.postMessage) {
                            window.passage.postMessage({
                                type: 'global_js_error',
                                error: error.message,
                                stack: error.stack
                            });
                        }
                    }
                }

                // Execute the global script
                executeGlobalScript();
            })();
        """.trimIndent()
    }

    // JavaScript result processing utility
    private fun unquoteJavaScriptResult(result: String?): String? {
        if (result.isNullOrEmpty()) {
            return null
        }

        // Handle null result
        if (result == "null") {
            return null
        }

        // Handle quoted strings - WebView automatically quotes JavaScript results
        if (result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
            val unquoted = result.substring(1, result.length - 1)
            // Handle escaped quotes and backslashes
            return unquoted.replace("\\\"", "\"").replace("\\\\", "\\")
        }

        // Return as-is for unquoted results (numbers, booleans)
        return result
    }

    // Screenshot capture for recording - temporarily disabled to avoid camera permission noise
    suspend fun captureScreenshot(): String? {
        // TODO: Re-enable when camera permissions are properly handled
        PassageLogger.debug(TAG, "Screenshot capture disabled to avoid camera permission errors")
        return null

        /*
        return withContext(Dispatchers.Main) {
            try {
                val bitmap = ScreenshotCapture.captureWebView(currentWebView ?: uiWebView)
                bitmap?.let { ScreenshotCapture.bitmapToBase64(it) }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Failed to capture screenshot", e)
                null
            }
        }
        */
    }

    // Page data collection with safe error handling
    fun collectPageDataSafely(callback: (Map<String, Any>?) -> Unit) {
        val script = """
            (function() {
                try {
                    const localStorageItems = [];
                    const sessionStorageItems = [];

                    // Safe localStorage access
                    try {
                        for (let i = 0; i < localStorage.length; i++) {
                            const key = localStorage.key(i);
                            localStorageItems.push({ name: key, value: localStorage.getItem(key) });
                        }
                    } catch (e) {
                        console.warn('localStorage access failed:', e);
                    }

                    // Safe sessionStorage access
                    try {
                        for (let i = 0; i < sessionStorage.length; i++) {
                            const key = sessionStorage.key(i);
                            sessionStorageItems.push({ name: key, value: sessionStorage.getItem(key) });
                        }
                    } catch (e) {
                        console.warn('sessionStorage access failed:', e);
                    }

                    return {
                        success: true,
                        url: window.location.href,
                        html: document.documentElement.outerHTML.substring(0, 1000), // Limit HTML size
                        localStorage: localStorageItems,
                        sessionStorage: sessionStorageItems
                    };
                } catch (error) {
                    return {
                        success: false,
                        error: error.message,
                        url: window.location.href
                    };
                }
            })();
        """.trimIndent()

        currentWebView?.evaluateJavascript(script) { result ->
            try {
                // Truncate potentially large results to keep logcat readable
                val truncatedRaw = PassageLogger.truncateData(result ?: "null", PassageConstants.Logging.MAX_HTML_LENGTH)
                PassageLogger.debug(TAG, "Page data collection raw result (truncated): '$truncatedRaw'")

                // Unquote the JavaScript result
                val unquotedResult = unquoteJavaScriptResult(result)
                val truncatedUnquoted = PassageLogger.truncateData(unquotedResult ?: "null", PassageConstants.Logging.MAX_HTML_LENGTH)
                PassageLogger.debug(TAG, "Page data collection unquoted result (truncated): '$truncatedUnquoted'")

                if (unquotedResult == null) {
                    PassageLogger.warn(TAG, "Page data collection returned null")
                    callback(null)
                    return@evaluateJavascript
                }

                val jsonResult = JSONObject(unquotedResult)
                val resultMap = mutableMapOf<String, Any>()
                jsonResult.keys().forEach { key ->
                    val keyStr = key as? String ?: return@forEach
                    resultMap[keyStr] = jsonResult.get(keyStr)
                }
                PassageLogger.debug(TAG, "Page data collection success: ${resultMap.keys}")
                callback(resultMap)
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Failed to collect page data: raw='$result'", e)
                callback(null)
            }
        }
    }

    // Activity lifecycle

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        PassageLogger.info(TAG, "Back button pressed - initiating close flow")

        closeButtonPressCount += 1
        PassageLogger.info(TAG, "Close button press count: $closeButtonPressCount")

        if (closeButtonPressCount >= 2) {
            PassageLogger.info(TAG, "Second close request detected - closing modal immediately")
            closeModal("back_button_double_press")
            return
        }

        requestCloseConfirmation("back_button")
    }

    private fun requestCloseConfirmation(trigger: String) {
        PassageLogger.info(TAG, "Requesting close confirmation (trigger=$trigger)")

        wasShowingAutomationBeforeClose = currentWebViewType == PassageConstants.WebViewTypes.AUTOMATION

        if (wasShowingAutomationBeforeClose) {
            PassageLogger.info(TAG, "Switching to UI WebView before showing close confirmation")
            showUIWebView()
        }

        val confirmationScript = """
            (function() {
                try {
                    if (typeof window.showCloseConfirmation === 'function') {
                        window.showCloseConfirmation();
                        return 'function_invoked';
                    }
                    if (window.passage && typeof window.passage.postMessage === 'function') {
                        window.passage.postMessage({ type: 'CLOSE_CONFIRMATION_REQUEST' });
                        return 'post_message';
                    }
                    console.log('[Passage] No close confirmation handler available');
                    return 'no_handler';
                } catch (error) {
                    console.error('[Passage] Error calling close confirmation:', error);
                    return 'error';
                }
            })();
        """.trimIndent()

        try {
            uiWebView.evaluateJavascript(confirmationScript) { rawResult ->
                val result = unquoteJavaScriptResult(rawResult)
                PassageLogger.debug(TAG, "Close confirmation script result (trigger=$trigger): ${result ?: "null"}")

                if (result == null || result == "no_handler" || result == "error") {
                    PassageLogger.warn(TAG, "Close confirmation handler unavailable (result=${result ?: "null"}), closing immediately")
                    closeModal("close_confirmation_unavailable")
                }
            }
        } catch (error: Throwable) {
            PassageLogger.error(TAG, "Failed to request close confirmation", error)
            closeModal("close_confirmation_exception")
        }
    }

    private fun closeModal(reason: String) {
        if (isClosing) {
            PassageLogger.warn(TAG, "Close requested ($reason) but modal is already closing")
            return
        }

        PassageLogger.info(TAG, "Closing modal (reason=$reason)")

        isClosing = true
        resetCloseRequestState()

        PassageSDK.handleClose()
        finish()
    }

    private fun resetCloseRequestState() {
        closeButtonPressCount = 0
        wasShowingAutomationBeforeClose = false
    }

    override fun onDestroy() {
        super.onDestroy()

        PassageLogger.info(TAG, "WebView Activity destroying")

        // Clean up WebViews
        containerView.removeAllViews()
        uiWebView.destroy()
        automationWebView.destroy()

        // Unregister receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)

        // Cancel coroutines
        activityScope.cancel()

        // Notify SDK
        if (!isClosing) {
            PassageSDK.handleClose()
        }

        PassageLogger.info(TAG, "WebView Activity destroyed")
    }

    // Public methods for external control

    fun setRemoteControl(remoteControl: RemoteControlManager) {
        this.remoteControl = remoteControl
    }

    fun updateGlobalJavaScript(javascript: String) {
        globalJavascript = javascript
        PassageLogger.info(TAG, "Global JavaScript updated (${javascript.length} chars)")

        // If automation WebView is already loaded, inject immediately
        if (hasLoadedAutomationUrl) {
            automationWebView.evaluateJavascript(javascript, null)
        }
    }

    fun setAutomationUrl(url: String) {
        integrationUrl = url
        PassageLogger.info(TAG, "Automation URL set: $url")

        // Load immediately if we're showing automation WebView
        if (currentWebViewType == PassageConstants.WebViewTypes.AUTOMATION && !hasLoadedAutomationUrl) {
            automationWebView.loadUrl(url)
            hasLoadedAutomationUrl = true
        }
    }

    fun setAutomationUserAgent(userAgent: String) {
        automationUserAgent = userAgent
        automationWebView.settings.userAgentString = userAgent
        PassageLogger.info(TAG, "Automation user agent updated")
    }
}
