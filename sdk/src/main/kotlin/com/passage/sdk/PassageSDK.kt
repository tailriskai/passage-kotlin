package com.passage.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.passage.sdk.analytics.PassageAnalytics
import com.passage.sdk.logging.PassageLogger
import com.passage.sdk.remote.RemoteControlManager
import com.passage.sdk.utils.JwtDecoder
import com.passage.sdk.webview.PassageWebViewActivity
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.*

/**
 * Main Passage SDK class - Singleton implementation
 * Manages SDK lifecycle, configuration, and modal presentation
 */
object PassageSDK {

    // Configuration
    private var config: PassageConfig = PassageConfig()

    // Debug: Track instance lifecycle
    private val instanceId = UUID.randomUUID().toString()

    // Activity reference for presentation
    private var activityRef: WeakReference<Activity>? = null

    // Remote control manager
    private var remoteControl: RemoteControlManager? = null

    // State management
    private var isClosing = false
    private var isPresentingModal = false

    // Callbacks - matching Swift SDK structure
    private var onConnectionComplete: ((PassageSuccessData) -> Unit)? = null
    private var onConnectionError: ((PassageErrorData) -> Unit)? = null
    private var onDataComplete: ((PassageDataResult) -> Unit)? = null
    private var onPromptComplete: ((PassagePromptResponse) -> Unit)? = null
    private var onExit: ((String?) -> Unit)? = null
    private var onWebviewChange: ((String) -> Unit)? = null
    private var lastWebviewType: String = PassageConstants.WebViewTypes.UI

    // SDK Version
    private val sdkVersion = PassageConstants.Defaults.SDK_VERSION

    // Coroutine scope for async operations
    private val sdkScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        PassageLogger.info("[SDK] PassageSDK initialized - Instance ID: ${instanceId.take(8)}")
    }

    /**
     * Initialize the SDK with options
     * Stores callbacks for later use
     */
    fun initialize(options: PassageInitializeOptions) {
        PassageLogger.debugMethod("initialize", mapOf(
            "publishableKey" to PassageLogger.truncateData(options.publishableKey, 20),
            "prompts" to (options.prompts?.size ?: 0)
        ))

        // Store callbacks from initialization
        this.onConnectionComplete = options.onConnectionComplete
        this.onConnectionError = options.onError
        this.onDataComplete = options.onDataComplete
        this.onPromptComplete = options.onPromptComplete
        this.onExit = options.onExit

        // TODO: Implement publishable key handling and prompt setup

        PassageLogger.info("[SDK] Initialized with ${options.prompts?.size ?: 0} prompts")
    }

    /**
     * Configure the SDK with custom settings
     */
    fun configure(config: PassageConfig) {
        this.config = config

        // Configure logger with unified debug flag and SDK version
        PassageLogger.configure(debug = config.debug, sdkVersion = sdkVersion)

        // Configure analytics
        PassageAnalytics.configure(sdkVersion = sdkVersion)
        PassageAnalytics.trackConfigureStart()

        PassageLogger.debugMethod("configure", mapOf(
            "baseUrl" to config.baseUrl,
            "socketUrl" to config.socketUrl,
            "socketNamespace" to config.socketNamespace,
            "debug" to config.debug,
            "sdkVersion" to sdkVersion
        ))

        PassageAnalytics.trackConfigureSuccess(mapOf(
            "baseUrl" to config.baseUrl,
            "socketUrl" to config.socketUrl,
            "socketNamespace" to config.socketNamespace,
            "debug" to config.debug
        ))

        // Note: RemoteControlManager will be initialized in openInternal()
        // where we have access to Activity context
    }

    /**
     * Open the Passage modal with options
     */
    @JvmOverloads
    fun open(
        activity: Activity,
        options: PassageOpenOptions = PassageOpenOptions()
    ) {
        val token = options.intentToken ?: ""
        val presentationStyle = options.presentationStyle

        openInternal(
            activity = activity,
            token = token,
            presentationStyle = presentationStyle,
            onConnectionComplete = options.onConnectionComplete,
            onConnectionError = options.onConnectionError,
            onDataComplete = options.onDataComplete,
            onPromptComplete = options.onPromptComplete,
            onExit = options.onExit,
            onWebviewChange = options.onWebviewChange
        )
    }

    /**
     * Open the Passage modal with direct parameters
     */
    @JvmOverloads
    fun open(
        activity: Activity,
        token: String,
        presentationStyle: PassagePresentationStyle = PassagePresentationStyle.MODAL,
        onConnectionComplete: ((PassageSuccessData) -> Unit)? = null,
        onConnectionError: ((PassageErrorData) -> Unit)? = null,
        onDataComplete: ((PassageDataResult) -> Unit)? = null,
        onPromptComplete: ((PassagePromptResponse) -> Unit)? = null,
        onExit: ((String?) -> Unit)? = null,
        onWebviewChange: ((String) -> Unit)? = null
    ) {
        openInternal(
            activity = activity,
            token = token,
            presentationStyle = presentationStyle,
            onConnectionComplete = onConnectionComplete,
            onConnectionError = onConnectionError,
            onDataComplete = onDataComplete,
            onPromptComplete = onPromptComplete,
            onExit = onExit,
            onWebviewChange = onWebviewChange
        )
    }

    private fun openInternal(
        activity: Activity,
        token: String,
        presentationStyle: PassagePresentationStyle,
        onConnectionComplete: ((PassageSuccessData) -> Unit)?,
        onConnectionError: ((PassageErrorData) -> Unit)?,
        onDataComplete: ((PassageDataResult) -> Unit)?,
        onPromptComplete: ((PassagePromptResponse) -> Unit)?,
        onExit: ((String?) -> Unit)?,
        onWebviewChange: ((String) -> Unit)?
    ) {
        PassageLogger.info("[SDK:${instanceId.take(8)}] ========== OPEN() CALLED ==========")
        PassageLogger.debug("[SDK:${instanceId.take(8)}] Token length: ${token.length}, Style: $presentationStyle")
        PassageLogger.debug("[SDK:${instanceId.take(8)}] Current isClosing state: $isClosing")
        PassageLogger.debug("[SDK:${instanceId.take(8)}] Current onExit callback: ${this.onExit?.let { "exists" } ?: "nil"}")
        PassageAnalytics.trackOpenRequest(token)

        // Auto-configure with default values if not already configured
        if (remoteControl == null) {
            PassageLogger.info("[SDK] RemoteControl not initialized - auto-configuring with default values")
            configure(PassageConfig())

            // Now create RemoteControlManager with Activity context
            PassageLogger.info("[SDK] Creating RemoteControlManager with Activity context")
            remoteControl = RemoteControlManager(config, activity.applicationContext)
        }

        // Reset closing flag for new session
        PassageLogger.info("[SDK] Resetting isClosing flag from $isClosing to false")
        isClosing = false

        // Store callbacks
        PassageLogger.info("[SDK] Storing new callbacks...")
        PassageLogger.debug("[SDK] Previous onExit: ${this.onExit?.let { "existed" } ?: "nil"}")

        if (this.onExit != null) {
            PassageLogger.warn("[SDK] ⚠️ onExit callback already exists! This shouldn't happen after cleanup")
        }

        this.onConnectionComplete = onConnectionComplete
        this.onConnectionError = onConnectionError
        this.onDataComplete = onDataComplete
        this.onPromptComplete = onPromptComplete
        this.onExit = onExit
        this.onWebviewChange = onWebviewChange

        PassageLogger.info("[SDK] Callbacks stored - onExit: ${onExit?.let { "SET" } ?: "NIL"}")

        // Double-check the callback was stored
        if (this.onExit == null && onExit != null) {
            PassageLogger.error("[SDK] ❌ CRITICAL: onExit callback was not stored properly!")
        }

        // Prevent concurrent presentations
        if (isPresentingModal) {
            PassageLogger.warn("[SDK] open() called while a presentation is in progress. Ignoring this call to prevent double-present.")
            return
        }

        // Store activity reference
        activityRef = WeakReference(activity)

        // Build URL from token
        val url = buildUrlFromToken(token)

        // Mark as presenting
        isPresentingModal = true

        // Launch WebView Activity
        val intent = Intent(activity, PassageWebViewActivity::class.java).apply {
            putExtra(PassageConstants.IntentExtras.INTENT_TOKEN, token)
            putExtra(PassageConstants.IntentExtras.PRESENTATION_STYLE, presentationStyle.name)
            putExtra(PassageConstants.IntentExtras.CONFIG, config)
            putExtra("url", url)
        }

        activity.startActivity(intent)

        // Handle activity animation based on presentation style
        when (presentationStyle) {
            PassagePresentationStyle.MODAL -> {
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            PassagePresentationStyle.FULL_SCREEN -> {
                activity.overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            }
        }

        PassageLogger.info("[SDK] ✅ Modal activity started successfully")
        PassageAnalytics.trackOpenSuccess(url)

        // Initialize remote control connection
        initializeRemoteControl(token)

        // Mark presentation as finished
        isPresentingModal = false
    }

    /**
     * Close the modal programmatically
     */
    fun close() {
        PassageLogger.debugMethod("close")

        // Check if already closing
        if (isClosing) {
            PassageLogger.debug("[SDK] close() called but already closing, ignoring")
            return
        }

        isClosing = true

        // Call onExit before closing
        onExit?.invoke("programmatic_close")
        PassageAnalytics.trackModalClosed("programmatic_close")

        // Send broadcast to close the activity
        activityRef?.get()?.let { activity ->
            val intent = Intent(PassageConstants.BroadcastActions.NAVIGATE).apply {
                putExtra("action", "close")
            }
            LocalBroadcastManager.getInstance(activity).sendBroadcast(intent)
        }

        PassageLogger.info("[SDK] Navigation controller dismissed (programmatic)")
        cleanupAfterClose()
    }

    /**
     * Navigate to a URL in the WebView
     */
    fun navigate(url: String) {
        activityRef?.get()?.let { activity ->
            val intent = Intent(PassageConstants.BroadcastActions.NAVIGATE).apply {
                putExtra("url", url)
            }
            activity.sendBroadcast(intent)
        }
    }

    /**
     * Clear all cookies (preserves localStorage, sessionStorage)
     */
    fun clearAllCookies() {
        PassageLogger.info("[SDK] Clearing all cookies only (preserving localStorage, sessionStorage)")

        activityRef?.get()?.let { activity ->
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            PassageLogger.info("[SDK] All cookies cleared successfully")
        }
    }

    /**
     * Clear all WebView data including cookies, localStorage, sessionStorage
     */
    fun clearWebViewData() {
        PassageLogger.info("[SDK] Clearing ALL webview data including cookies, localStorage, sessionStorage")

        activityRef?.get()?.let { activity ->
            val webStorage = android.webkit.WebStorage.getInstance()
            webStorage.deleteAllData()

            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            PassageLogger.info("[SDK] ALL WebView data cleared successfully")
        }
    }

    /**
     * Complete recording session with optional data
     */
    suspend fun completeRecording(data: Map<String, Any> = emptyMap()) {
        PassageLogger.debug("[SDK] completeRecording called with data: ${data.isNotEmpty()}")

        remoteControl?.let {
            it.completeRecording(data)
            PassageLogger.info("[SDK] completeRecording completed successfully")
        } ?: run {
            PassageLogger.error("[SDK] completeRecording failed - no remote control available")
            throw PassageError.NoRemoteControl
        }
    }

    /**
     * Capture recording data without completing the session
     */
    suspend fun captureRecordingData(data: Map<String, Any> = emptyMap()) {
        PassageLogger.debug("[SDK] captureRecordingData called with data: ${data.isNotEmpty()}")

        remoteControl?.let {
            it.captureRecordingData(data)
            PassageLogger.info("[SDK] captureRecordingData completed successfully")
        } ?: run {
            PassageLogger.error("[SDK] captureRecordingData failed - no remote control available")
            throw PassageError.NoRemoteControl
        }
    }

    // Internal methods

    private fun buildUrlFromToken(token: String): String {
        val baseUrl = config.baseUrl
        val urlString = "$baseUrl${PassageConstants.Paths.CONNECT}"

        // Generate SDK session like Swift does
        val sdkSession = "sdk-session-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(9)}"

        val urlBuilder = StringBuilder(urlString)
        urlBuilder.append("?intentToken=").append(token)
        urlBuilder.append("&sdkSession=").append(sdkSession)
        urlBuilder.append("&agentName=").append(config.agentName)

        val result = urlBuilder.toString()
        PassageLogger.debug("[SDK] Built URL: ${PassageLogger.truncateUrl(result, 100)}")
        PassageLogger.debug("[SDK] Built URL with sdkSession: $sdkSession")
        return result
    }

    private fun initializeRemoteControl(token: String) {
        remoteControl?.let { remote ->
            // Extract session ID from token
            PassageLogger.updateIntentToken(token)
            PassageAnalytics.updateSessionInfo(token, null)

            // Set configuration callback
            remote.setConfigurationCallback { userAgent, integrationUrl ->
                PassageLogger.info("[SDK] ========== CONFIGURATION CALLBACK TRIGGERED ==========")
                PassageLogger.info("[SDK] 🔧 Configuration callback called!")
                PassageLogger.info("[SDK] UserAgent: '${if (userAgent.isEmpty()) "EMPTY" else userAgent}' (${userAgent.length} chars)")
                PassageLogger.info("[SDK] IntegrationUrl: '${integrationUrl ?: "NIL - CRITICAL ISSUE!"}'")

                if (integrationUrl == null) {
                    PassageLogger.error("[SDK] ❌ CRITICAL: Integration URL is NIL in callback!")
                    PassageLogger.error("[SDK] This means automation webview will NEVER load any URL")
                    PassageLogger.error("[SDK] Check backend configuration endpoint")
                } else {
                    PassageLogger.info("[SDK] ✅ Integration URL received in callback: $integrationUrl")
                }

                // Update WebView configuration via broadcast
                activityRef?.get()?.let { activity ->
                    val intent = Intent(PassageConstants.BroadcastActions.NAVIGATE).apply {
                        putExtra("action", "updateConfiguration")
                        putExtra("userAgent", userAgent)
                        putExtra("integrationUrl", integrationUrl)
                    }
                    activity.sendBroadcast(intent)
                }
            }

            // Connect remote control
            remote.connect(
                intentToken = token,
                onSuccess = { data ->
                    onConnectionComplete?.invoke(data)
                },
                onError = { error ->
                    onConnectionError?.invoke(error)
                },
                onDataComplete = { data ->
                    onDataComplete?.invoke(data)
                },
                onPromptComplete = { prompt ->
                    onPromptComplete?.invoke(prompt)
                }
            )
        }
    }

    internal fun handleMessage(message: Map<String, Any>) {
        val type = message["type"] as? String ?: return

        when (type) {
            PassageConstants.MessageTypes.CONNECTION_SUCCESS -> {
                PassageLogger.info("[SDK] 🎉 CONNECTION SUCCESS")
                handleConnectionSuccess(message)
            }
            PassageConstants.MessageTypes.CONNECTION_ERROR -> {
                PassageLogger.error("[SDK] ❌ CONNECTION ERROR")
                handleConnectionError(message)
            }
            PassageConstants.MessageTypes.CLOSE_MODAL -> {
                PassageLogger.info("[SDK] 🚪 CLOSE MODAL message received from WebView")
                close()
            }
            PassageConstants.MessageTypes.PAGE_LOADED -> {
                val webViewType = message["webViewType"] as? String
                webViewType?.let {
                    PassageLogger.debug("[SDK] 📄 Page loaded in $it")
                }
            }
            PassageConstants.MessageTypes.NAVIGATION_FINISHED -> {
                val webViewType = message["webViewType"] as? String
                webViewType?.let {
                    PassageLogger.debug("[SDK] 🏁 Navigation finished in $it")
                }
            }
            PassageConstants.MessageTypes.OPEN_EXTERNAL_URL -> {
                val urlString = message["url"] as? String
                urlString?.let { url ->
                    PassageLogger.info("[SDK] 🔗 Opening external URL: $url")
                    activityRef?.get()?.let { activity ->
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        if (intent.resolveActivity(activity.packageManager) != null) {
                            activity.startActivity(intent)
                            PassageLogger.info("[SDK] External URL open result: success")
                        } else {
                            PassageLogger.error("[SDK] Cannot open URL: $url")
                        }
                    }
                }
            }
            PassageConstants.MessageTypes.NAVIGATE -> {
                val url = message["url"] as? String ?: return
                val targetWebView = message["targetWebView"] as? String

                activityRef?.get()?.let { activity ->
                    val navigateIntent = Intent(PassageConstants.BroadcastActions.NAVIGATE).apply {
                        putExtra("url", url)
                        targetWebView?.let { putExtra("targetWebView", it) }
                    }
                    LocalBroadcastManager.getInstance(activity).sendBroadcast(navigateIntent)
                }
            }
            PassageConstants.MessageTypes.SET_TITLE -> {
                val title = message["title"] as? String ?: ""
                activityRef?.get()?.let { activity ->
                    activity.runOnUiThread {
                        activity.title = title
                    }
                }
            }
            "CLOSE_CONFIRMED", "CLOSE_CANCELLED" -> {
                PassageLogger.debug("[SDK] Close confirmation message received - handled directly by WebView activity")
            }
            else -> {
                // Forward other messages to remote control
                remoteControl?.handleWebViewMessage(message)
            }
        }
    }

    private fun handleConnectionSuccess(data: Map<String, Any>) {
        PassageLogger.info("[SDK] handleConnectionSuccess called")

        // Get stored connection data from remote control
        val storedData = remoteControl?.getStoredConnectionData()

        var history = listOf<Any?>()
        var connectionId = ""

        if (storedData?.first?.isNotEmpty() == true) {
            PassageLogger.info("[SDK] ✅ Using stored connection data with ${storedData.first?.size} items")

            history = storedData.first ?: emptyList()

            connectionId = storedData.second ?: ""
        } else {
            PassageLogger.warn("[SDK] ❌ No stored connection data found, using WebView message data")
            // Parse from WebView message as fallback
            @Suppress("UNCHECKED_CAST")
            history = parseHistory(data["history"])
            connectionId = data["connectionId"] as? String ?: ""
        }

        val successData = PassageSuccessData(
            history = history,
            connectionId = connectionId
        )

        PassageLogger.info("[SDK] Final success data - history: ${history.size} items, connectionId: $connectionId")
        PassageAnalytics.trackOnSuccess(history.size, connectionId)

        // Mark as closing
        isClosing = true
        PassageAnalytics.trackModalClosed("success")

        // Call onExit before closing
        onExit?.invoke("success")

        // Close the activity
        activityRef?.get()?.let { activity ->
            if (activity is PassageWebViewActivity) {
                activity.finish()
            }
        }

        PassageLogger.info("[SDK] Navigation controller dismissed (success)")
        cleanupAfterClose()
    }

    private fun handleConnectionError(data: Map<String, Any>) {
        val error = data["error"] as? String ?: "Unknown error"
        val errorData = PassageErrorData(error, data)

        onConnectionError?.invoke(errorData)
        PassageAnalytics.trackOnError(error, data)

        // Mark as closing
        isClosing = true
        PassageAnalytics.trackModalClosed("error")

        // Call onExit before closing
        onExit?.invoke("error")

        // Close the activity
        activityRef?.get()?.let { activity ->
            if (activity is PassageWebViewActivity) {
                activity.finish()
            }
        }

        cleanupAfterClose()
    }

    internal fun handleClose() {
        PassageLogger.info("[SDK:${instanceId.take(8)}] ========== HANDLE CLOSE CALLED ==========")
        PassageLogger.info("[SDK:${instanceId.take(8)}] Current isClosing: $isClosing")
        PassageLogger.info("[SDK:${instanceId.take(8)}] Current onExit callback: ${onExit?.let { "EXISTS" } ?: "NIL"}")

        // Always call onExit if available and not already closing
        if (onExit != null && !isClosing) {
            PassageLogger.info("[SDK] ✅ Calling onExit callback with reason: user_action")
            onExit?.invoke("user_action")
            PassageAnalytics.trackModalClosed("user_action")
        } else {
            PassageLogger.warn("[SDK] ❌ NOT calling onExit - onExit: ${onExit != null}, isClosing: $isClosing")
        }

        // Prevent duplicate cleanup
        if (!isClosing) {
            PassageLogger.info("[SDK] Setting isClosing to true")
            isClosing = true
            cleanupAfterClose()
        }
    }

    private fun parseHistory(data: Any?): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        val historyArray = data as? List<Any?> ?: return emptyList()

        // Return history items directly without modification
        return historyArray
    }

    private fun cleanupAfterClose() {
        PassageLogger.info("[SDK] ========== CLEANUP AFTER CLOSE ==========")
        PassageLogger.debug("[SDK] Current onExit before cleanup: ${onExit?.let { "exists" } ?: "nil"}")

        // Clear callbacks synchronously first
        PassageLogger.info("[SDK] Clearing callbacks SYNCHRONOUSLY...")
        onConnectionComplete = null
        onConnectionError = null
        onDataComplete = null
        onPromptComplete = null
        onExit = null
        onWebviewChange = null
        PassageLogger.info("[SDK] Callbacks cleared")

        // Emit modalExit and disconnect remote control asynchronously
        sdkScope.launch {
            PassageLogger.debug("[SDK] Async cleanup - emitting modalExit and disconnecting remote control...")
            remoteControl?.emitModalExit()
            remoteControl?.disconnect()

            // Reset closing flag after everything is complete
            PassageLogger.info("[SDK] Resetting isClosing flag from true to false")
            isClosing = false

            PassageLogger.info("[SDK] ✅ Async cleanup completed - isClosing: $isClosing")
        }
    }

    /**
     * Get device information for analytics/logging
     */
    internal fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "systemName" to "Android",
            "systemVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT.toString(),
            "platform" to "android"
        )
    }

    /**
     * Get the current RemoteControlManager instance
     * Used by PassageWebViewActivity to access remote control functionality
     */
    internal fun getRemoteControl(): RemoteControlManager? {
        return remoteControl
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        remoteControl?.disconnect()
        sdkScope.cancel()
        activityRef = null
        PassageLogger.debug("[SDK] Full cleanup completed, all resources released")
    }
}
