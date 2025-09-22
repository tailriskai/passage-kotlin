package com.passage.sdk.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.passage.sdk.*
import com.passage.sdk.analytics.PassageAnalytics
import com.passage.sdk.logging.PassageLogger
import com.passage.sdk.utils.JwtDecoder
import com.passage.sdk.utils.ScreenshotCapture
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Extension function to convert JSONObject to Map
private fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val keyString = key as? String ?: return@forEach
        get(key)?.let { value ->
            map[keyString] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }
    }
    return map
}

// Extension function to convert JSONArray to List
private fun JSONArray.toList(): List<Any> {
    val list = mutableListOf<Any>()
    for (i in 0 until length()) {
        val value = get(i)
        list.add(when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        })
    }
    return list
}

/**
 * Manages Socket.IO communication and command processing
 * Matches the Swift SDK's RemoteControlManager implementation
 */
class RemoteControlManager(
    private var config: PassageConfig,
    private val context: Context
) {
    companion object {
        private const val TAG = "RemoteControlManager"
        private const val CONFIG_TIMEOUT = 10000L // 10 seconds
        private const val SCREENSHOT_DEFAULT_INTERVAL = 5000L // 5 seconds
    }

    // Socket.IO
    private var socket: Socket? = null
    private var isConnected = false

    // Session data
    private var intentToken: String? = null
    private var sessionId: String? = null
    private var connectionData: List<Map<String, Any>>? = null
    private var connectionId: String? = null

    // Callbacks
    private var onSuccess: ((PassageSuccessData) -> Unit)? = null
    private var onError: ((PassageErrorData) -> Unit)? = null
    private var onDataComplete: ((PassageDataResult) -> Unit)? = null
    private var onPromptComplete: ((PassagePromptResponse) -> Unit)? = null
    private var onConfigurationUpdated: ((String, String?) -> Unit)? = null

    // Configuration from backend
    private var cookieDomains: List<String> = emptyList()
    private var globalJavascript: String = ""
    private var automationUserAgent: String = ""
    private var integrationUrl: String? = null
    private var imageOptimization: Map<String, Any>? = null

    // Current command tracking
    private var currentCommand: RemoteCommand? = null
    private var lastUserActionCommand: RemoteCommand? = null
    private var lastWaitCommand: RemoteCommand? = null
    private var executingWaitCommand: RemoteCommand? = null  // Track currently executing wait command
    private var currentWebViewType: String = PassageConstants.WebViewTypes.UI

    // Success URLs for navigation
    private var currentSuccessUrls: List<SuccessUrl> = emptyList()

    // Screenshot capture
    private var screenshotTimer: Timer? = null
    private var screenshotInterval: Long? = null
    private var captureScreenshotFlag = false
    private var recordFlag = false
    private var captureImageFunction: (suspend () -> String?)? = null

    // Page data collection
    private var pageDataContinuation: kotlin.coroutines.Continuation<PageData?>? = null

    // Coroutine scope
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + scopeJob)

    // Broadcast registration state
    private var isReceiverRegistered = false

    // HTTP client for API calls
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    // Broadcast receiver for command results
    private val commandResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PassageConstants.BroadcastActions.SCRIPT_EXECUTION_RESULT -> handleScriptExecutionResult(intent)
                PassageConstants.BroadcastActions.NAVIGATION_COMPLETED -> handleNavigationComplete(intent)
                PassageConstants.BroadcastActions.SEND_BROWSER_STATE -> handleSendBrowserState(intent)
            }
        }
    }

    init {
        registerCommandResultReceiver()
    }

    private fun registerCommandResultReceiver() {
        if (isReceiverRegistered) {
            PassageLogger.debug(TAG, "Command result receiver already registered")
            return
        }

        val filter = IntentFilter().apply {
            addAction(PassageConstants.BroadcastActions.SCRIPT_EXECUTION_RESULT)
            addAction(PassageConstants.BroadcastActions.NAVIGATION_COMPLETED)
            addAction(PassageConstants.BroadcastActions.SEND_BROWSER_STATE)
        }

        LocalBroadcastManager.getInstance(context).registerReceiver(commandResultReceiver, filter)
        isReceiverRegistered = true
        PassageLogger.debug(TAG, "Command result receiver registered")
    }

    private fun unregisterCommandResultReceiver() {
        if (!isReceiverRegistered) {
            PassageLogger.debug(TAG, "Command result receiver already unregistered")
            return
        }

        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(commandResultReceiver)
            PassageLogger.debug(TAG, "Command result receiver unregistered")
        } catch (e: Exception) {
            PassageLogger.warn(TAG, "Error unregistering command result receiver: ${e.message}")
        } finally {
            isReceiverRegistered = false
        }
    }

    private fun ensureScopeActive() {
        if (!scopeJob.isActive) {
            PassageLogger.warn(TAG, "Coroutine scope inactive - recreating SupervisorJob for new session")
            scopeJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
    }

    private fun launchInScope(taskName: String, block: suspend CoroutineScope.() -> Unit) {
        ensureScopeActive()
        PassageLogger.debug(TAG, "Launching coroutine task: $taskName")
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                PassageLogger.warn(TAG, "Coroutine task cancelled: $taskName -> ${e.message}")
                throw e
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Coroutine task failed: $taskName", e)
            }
        }
    }

    fun updateConfig(newConfig: PassageConfig) {
        config = newConfig
    }

    fun setConfigurationCallback(callback: (String, String?) -> Unit) {
        onConfigurationUpdated = callback
    }

    fun setCaptureImageFunction(function: suspend () -> String?) {
        captureImageFunction = function
    }

    fun getGlobalJavascript(): String {
        return globalJavascript
    }

    fun getImageOptimizationParameters(): Map<String, Any>? {
        return imageOptimization
    }

    /**
     * Connect to socket and initialize session
     */
    fun connect(
        intentToken: String,
        onSuccess: ((PassageSuccessData) -> Unit)? = null,
        onError: ((PassageErrorData) -> Unit)? = null,
        onDataComplete: ((PassageDataResult) -> Unit)? = null,
        onPromptComplete: ((PassagePromptResponse) -> Unit)? = null
    ) {
        registerCommandResultReceiver()
        ensureScopeActive()

        this.intentToken = intentToken
        this.onSuccess = onSuccess
        this.onError = onError
        this.onDataComplete = onDataComplete
        this.onPromptComplete = onPromptComplete

        // Reset success URLs
        currentSuccessUrls = emptyList()

        PassageLogger.info(TAG, "========== STARTING CONNECTION ==========")
        PassageLogger.info(TAG, "Intent token length: ${intentToken.length}")
        PassageLogger.info(TAG, "Socket URL: ${config.socketUrl}")
        PassageLogger.info(TAG, "Socket Namespace: ${config.socketNamespace}")

        // Parse JWT token
        parseJwtFlags(intentToken)

        // Fetch configuration first
        PassageLogger.info(TAG, "Fetching configuration from server...")
        PassageAnalytics.trackConfigurationRequest("${config.socketUrl}${PassageConstants.Paths.AUTOMATION_CONFIG}")

        PassageLogger.info(TAG, "Starting coroutine for configuration fetch...")
        launchInScope("fetchConfiguration") {
            try {
                PassageLogger.info(TAG, "Inside coroutine, calling fetchConfiguration...")
                fetchConfiguration {
                    PassageLogger.info(TAG, "Configuration fetch completed, proceeding to socket connection")
                    connectSocket()

                    // Start screenshot capture if enabled
                    if (captureScreenshotFlag) {
                        PassageLogger.info(TAG, "Starting screenshot capture...")
                        startScreenshotCapture()
                    }
                }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "❌ Coroutine exception in connect()", e)
                onError?.invoke(PassageErrorData("Configuration fetch failed: ${e.message}", null))
            }
        }
    }

    private fun parseJwtFlags(token: String) {
        try {
            val decoded = JwtDecoder.decode(token)

            // Extract flags
            recordFlag = decoded.getClaim("record").asBoolean() ?: false
            captureScreenshotFlag = decoded.getClaim("captureScreenshot").asBoolean() ?: false
            val intervalSeconds = decoded.getClaim("captureScreenshotInterval").asDouble()

            PassageLogger.info(TAG, "========== JWT TOKEN ANALYSIS ==========")
            PassageLogger.info(TAG, "Record mode: ${if (recordFlag) "ENABLED" else "DISABLED"}")
            PassageLogger.info(TAG, "Capture screenshot: ${if (captureScreenshotFlag) "ENABLED" else "DISABLED"}")

            screenshotInterval = if (intervalSeconds != null) {
                (intervalSeconds * 1000).toLong()
            } else {
                SCREENSHOT_DEFAULT_INTERVAL
            }
            PassageLogger.info(TAG, "Screenshot interval: ${screenshotInterval}ms")

            // Extract session ID
            sessionId = decoded.getClaim("sessionId").asString()
            PassageLogger.info(TAG, "Session ID: ${sessionId ?: "nil"}")

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to parse JWT token", e)
        }
    }

    private suspend fun fetchConfiguration(onComplete: () -> Unit) {
        val url = "${config.socketUrl}${PassageConstants.Paths.AUTOMATION_CONFIG}"

        PassageLogger.info(TAG, "========== FETCHING CONFIGURATION ==========")
        PassageLogger.info(TAG, "Configuration URL: $url")
        PassageLogger.info(TAG, "Intent token: ${intentToken?.take(50)}...")

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("x-intent-token", intentToken ?: "")
                .build()

            PassageLogger.info(TAG, "Making HTTP request to configuration endpoint...")

            // Add timeout to the call
            val call = httpClient.newCall(request)
            val response = withTimeout(10000) { // 10 second timeout
                call.execute()
            }

            PassageLogger.info(TAG, "Configuration response code: ${response.code}")
            PassageLogger.info(TAG, "Configuration response message: ${response.message}")

            if (response.isSuccessful) {
                val body = response.body?.string()
                PassageLogger.info(TAG, "Configuration response body length: ${body?.length ?: 0}")

                body?.let {
                    PassageLogger.info(TAG, "Parsing configuration JSON...")
                    val configResponse = gson.fromJson(it, ConfigurationResponse::class.java)

                    PassageLogger.info(TAG, "========== CONFIGURATION RESPONSE ==========")
                    PassageLogger.info(TAG, "Integration URL: ${configResponse.integration?.url ?: "NIL"}")
                    PassageLogger.info(TAG, "Automation User Agent: ${configResponse.automationUserAgent ?: "NIL"}")
                    PassageLogger.info(TAG, "Global JavaScript length: ${configResponse.globalJavascript?.length ?: 0}")
                    PassageLogger.info(TAG, "Cookie domains count: ${configResponse.cookieDomains?.size ?: 0}")

                    // Store configuration
                    cookieDomains = configResponse.cookieDomains ?: emptyList()
                    globalJavascript = configResponse.globalJavascript ?: ""
                    automationUserAgent = configResponse.automationUserAgent ?: ""
                    integrationUrl = configResponse.integration?.url
                    imageOptimization = configResponse.imageOptimization?.let { opt ->
                        mapOf(
                            "quality" to (opt.quality ?: 0.8f),
                            "maxWidth" to (opt.maxWidth ?: 1920),
                            "maxHeight" to (opt.maxHeight ?: 1080)
                        )
                    }

                    if (integrationUrl == null) {
                        PassageLogger.error(TAG, "❌ CRITICAL: No integration URL found!")
                    } else {
                        PassageLogger.info(TAG, "✅ Configuration parsed successfully")
                    }

                    PassageAnalytics.trackConfigurationSuccess(automationUserAgent, integrationUrl)

                    // Notify configuration update
                    onConfigurationUpdated?.invoke(automationUserAgent, integrationUrl)
                } ?: run {
                    PassageLogger.error(TAG, "❌ Configuration response body is null")
                }
            } else {
                PassageLogger.error(TAG, "❌ Configuration fetch failed with status: ${response.code}")
                PassageLogger.error(TAG, "Response message: ${response.message}")
                response.body?.string()?.let { errorBody ->
                    PassageLogger.error(TAG, "Error body: $errorBody")
                }
                PassageAnalytics.trackConfigurationError("Status: ${response.code}", url)
            }
        } catch (e: Exception) {
            PassageLogger.error(TAG, "❌ Configuration fetch exception", e)
            PassageLogger.error(TAG, "Exception type: ${e::class.simpleName}")
            PassageLogger.error(TAG, "Exception message: ${e.message}")
            PassageAnalytics.trackConfigurationError(e.message ?: "Unknown error", url)
        }

        PassageLogger.info(TAG, "Calling onComplete() callback...")
        onComplete()
    }

    private fun connectSocket() {
        PassageLogger.info(TAG, "Connecting to socket...")
        PassageLogger.info(TAG, "Socket URL: ${config.socketUrl}")
        PassageLogger.info(TAG, "Socket Namespace: ${config.socketNamespace}")
        PassageLogger.info(TAG, "Full URI: ${config.socketUrl + config.socketNamespace}")
        PassageAnalytics.trackRemoteControlConnectStart(config.socketUrl, config.socketNamespace)

        try {
            val opts = IO.Options().apply {
                transports = PassageConstants.Socket.TRANSPORTS
                timeout = PassageConstants.Socket.TIMEOUT.toLong()
                forceNew = true
            }

            PassageLogger.info(TAG, "Socket options:")
            PassageLogger.info(TAG, "  - transports: ${PassageConstants.Socket.TRANSPORTS.contentToString()}")
            PassageLogger.info(TAG, "  - timeout: ${PassageConstants.Socket.TIMEOUT}")
            PassageLogger.info(TAG, "  - forceNew: true")

            // Build URI with query parameters like web version
            val baseUrl = config.socketUrl + config.socketNamespace
            val urlWithParams = "$baseUrl?intentToken=$intentToken&agentName=${config.agentName}"
            val uri = URI.create(urlWithParams)

            PassageLogger.info(TAG, "Socket URI created: $uri")
            PassageLogger.info(TAG, "Socket URI scheme: ${uri.scheme}")
            PassageLogger.info(TAG, "Socket URI host: ${uri.host}")
            PassageLogger.info(TAG, "Socket URI port: ${uri.port}")
            PassageLogger.info(TAG, "Socket URI path: ${uri.path}")
            PassageLogger.info(TAG, "Socket URI query: ${uri.query}")

            socket = IO.socket(uri, opts)

            PassageLogger.info(TAG, "Socket object created successfully")

            setupSocketListeners()

            PassageLogger.info(TAG, "Attempting socket connection...")
            socket?.connect()

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to create socket", e)
            PassageAnalytics.trackRemoteControlConnectError(e.message ?: "Unknown error")
            onError?.invoke(PassageErrorData("Socket connection failed", null))
        }
    }

    private fun setupSocketListeners() {
        val socket = this.socket ?: return

        // Connection events
        socket.on(Socket.EVENT_CONNECT) {
            PassageLogger.info(TAG, "Socket connected")
            PassageLogger.info(TAG, "Socket ID: ${socket.id()}")
            isConnected = true
            // Join with intent token (re-enabled)
            val joinData = JSONObject().apply {
                put("intentToken", intentToken)
                put("agentName", config.agentName)
            }
            PassageLogger.info(TAG, "Sending join event with data:")
            PassageLogger.info(TAG, "  - intentToken: ${intentToken?.take(50)}...")
            PassageLogger.info(TAG, "  - agentName: ${config.agentName}")
            PassageLogger.info(TAG, "  - full join payload: $joinData")
            socket.emit("join", joinData)
            PassageLogger.info(TAG, "Sent join event with token")
        }

        socket.on(Socket.EVENT_DISCONNECT) { args ->
            PassageLogger.info(TAG, "Socket disconnected")
            PassageLogger.info(TAG, "Disconnect args: ${args.contentToString()}")
            isConnected = false
            PassageAnalytics.trackRemoteControlDisconnect("socket_disconnect")
        }

        socket.on("error") { args ->
            val error = args.firstOrNull()?.toString() ?: "Unknown error"
            PassageLogger.error(TAG, "Socket error: $error")
            PassageAnalytics.trackRemoteControlConnectError(error)
        }

        // Custom events
        socket.on("connected") { args ->
            PassageLogger.info(TAG, "Received 'connected' acknowledgment")
            PassageLogger.info(TAG, "Connected args: ${args.contentToString()}")
            PassageAnalytics.trackRemoteControlConnectSuccess(socket.id())
        }

        socket.on("connection") { args ->
            handleConnectionEvent(args)
        }

        socket.on("command") { args ->
            handleCommandEvent(args)
        }

        socket.on("error") { args ->
            handleErrorEvent(args)
        }

        // Handle CONNECTION_SUCCESS events from socket (matching Swift SDK)
        socket.on("CONNECTION_SUCCESS") { args ->
            PassageLogger.info(TAG, "🎉 CONNECTION_SUCCESS event received")
            PassageLogger.debug(TAG, "Connection success data: ${args.contentToString()}")

            try {
                val eventData = args[0] as? JSONObject
                if (eventData != null) {
                    val connectionsArray = eventData.optJSONArray("connections")
                    val connectionId = eventData.optString("connectionId", "")

                    val connections = mutableListOf<Map<String, Any>>()
                    if (connectionsArray != null) {
                        for (i in 0 until connectionsArray.length()) {
                            val item = connectionsArray.getJSONObject(i)
                            val map = mutableMapOf<String, Any>()
                            item.keys().forEach { key ->
                                val keyString = key as? String ?: return@forEach
                                item.get(key)?.let { value ->
                                    map[keyString] = value
                                }
                            }
                            connections.add(map)
                        }
                    }

                    val successData = PassageSuccessData(
                        history = connections.map { item ->
                            PassageHistoryItem(structuredData = item, additionalData = emptyMap())
                        },
                        connectionId = connectionId
                    )
                    // Skip calling onSuccess here - onConnectionComplete should only be called from done command
                    // onSuccess?.invoke(successData)
                    PassageLogger.info(TAG, "CONNECTION_SUCCESS data received but not calling onSuccess - will be handled by done command")
                }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Error handling CONNECTION_SUCCESS event", e)
            }
        }

        // Handle CONNECTION_ERROR events from socket (matching Swift SDK)
        socket.on("CONNECTION_ERROR") { args ->
            PassageLogger.error(TAG, "❌ CONNECTION_ERROR event received")
            PassageLogger.error(TAG, "Connection error data: ${args.contentToString()}")

            try {
                val eventData = args[0] as? JSONObject
                if (eventData != null) {
                    val error = eventData.optString("error", "Unknown error")
                    val errorData = PassageErrorData(error, eventData.toMap())
                    onError?.invoke(errorData)
                }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Error handling CONNECTION_ERROR event", e)
            }
        }

        // Handle DATA_COMPLETE events (matching Swift SDK)
        socket.on("DATA_COMPLETE") { args ->
            PassageLogger.info(TAG, "📊 DATA_COMPLETE event received")
            PassageLogger.debug(TAG, "Data complete data: ${args.contentToString()}")

            try {
                val eventData = args[0] as? JSONObject
                if (eventData != null) {
                    // Check if data state allows onDataComplete to be called
                    val status = eventData.optString("status")
                    PassageLogger.debug(TAG, "Data complete status: $status")

                    // Only call onDataComplete when data is available or partially available
                    if (status == "data_available" || status == "data_partially_available") {
                        PassageLogger.info(TAG, "Status allows onDataComplete - calling callback")

                        val dataValue = eventData.opt("data")
                        val promptsArray = eventData.optJSONArray("prompts")

                        val prompts = mutableListOf<Map<String, Any>>()
                        if (promptsArray != null) {
                            for (i in 0 until promptsArray.length()) {
                                val item = promptsArray.getJSONObject(i)
                                val map = mutableMapOf<String, Any>()
                                item.keys().forEach { key ->
                                    val keyString = key as? String ?: return@forEach
                                    item.get(key)?.let { value ->
                                        map[keyString] = value
                                    }
                                }
                                prompts.add(map)
                            }
                        }

                        val dataResult = PassageDataResult(
                            data = dataValue,
                            prompts = if (prompts.isNotEmpty()) prompts else null
                        )
                        onDataComplete?.invoke(dataResult)
                    } else {
                        PassageLogger.info(TAG, "Status '$status' does not allow onDataComplete - skipping callback")
                    }
                }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Error handling DATA_COMPLETE event", e)
            }
        }

        // Handle PROMPT_COMPLETE events (matching Swift SDK)
        socket.on("PROMPT_COMPLETE") { args ->
            PassageLogger.info(TAG, "🎯 PROMPT_COMPLETE event received")
            PassageLogger.debug(TAG, "Prompt complete data: ${args.contentToString()}")

            try {
                val eventData = args[0] as? JSONObject
                if (eventData != null) {
                    val key = eventData.optString("key", "")
                    val value = eventData.optString("value", "")
                    val response = eventData.opt("response")

                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        val promptResponse = PassagePromptResponse(
                            key = key,
                            value = value,
                            response = response
                        )
                        onPromptComplete?.invoke(promptResponse)
                    }
                }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Error handling PROMPT_COMPLETE event", e)
            }
        }

        socket.on("disconnect") { args ->
            PassageLogger.info(TAG, "Server requested disconnect")
            PassageLogger.info(TAG, "Server disconnect args: ${args.contentToString()}")
            if (args.isNotEmpty()) {
                PassageLogger.info(TAG, "Server disconnect reason: ${args[0]}")
            }
            disconnect()
        }
    }

    private fun handleConnectionEvent(args: Array<Any>) {
        try {
            val data = args[0] as JSONObject
            PassageLogger.info(TAG, "========== CONNECTION EVENT ==========")

            val connectionData = parseConnectionData(data)
            val connectionId = data.optString("connectionId")
            val userActionRequired = data.optBoolean("userActionRequired", false)

            PassageLogger.info(TAG, "Connection data items: ${connectionData.size}")
            PassageLogger.info(TAG, "Connection ID: $connectionId")
            PassageLogger.info(TAG, "User action required: $userActionRequired")

            // Store connection data
            this.connectionData = connectionData
            this.connectionId = connectionId

            // Handle WebView switching based on userActionRequired
            if (userActionRequired) {
                PassageLogger.info(TAG, "Switching to AUTOMATION webview")
                sendBroadcast(PassageConstants.BroadcastActions.SHOW_AUTOMATION_WEBVIEW)
                currentWebViewType = PassageConstants.WebViewTypes.AUTOMATION
            } else {
                PassageLogger.info(TAG, "Keeping UI webview visible")
                sendBroadcast(PassageConstants.BroadcastActions.SHOW_UI_WEBVIEW)
                currentWebViewType = PassageConstants.WebViewTypes.UI
            }

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Error handling connection event", e)
        }
    }

    private fun handleCommandEvent(args: Array<Any>) {
        try {
            val commandData = args[0] as JSONObject
            PassageLogger.info(TAG, "========== COMMAND RECEIVED ==========")

            // Log raw command data for debugging
            PassageLogger.debug(TAG, "Raw command JSON: ${commandData.toString()}")

            val command = parseCommand(commandData)
            PassageLogger.info(TAG, "Command: ${command.id} (type: ${command.javaClass.simpleName})")

            // Log command details
            when (command) {
                is RemoteCommand.Navigate -> {
                    PassageLogger.info(TAG, "Navigate to: ${PassageLogger.truncateUrl(command.url, 100)}")
                }
                is RemoteCommand.Click -> {
                    PassageLogger.info(TAG, "Click command with script: ${command.injectScript?.length ?: 0} chars")
                }
                is RemoteCommand.Input -> {
                    PassageLogger.info(TAG, "Input command with script: ${command.injectScript?.length ?: 0} chars")
                }
                is RemoteCommand.Wait -> {
                    PassageLogger.info(TAG, "Wait command with script: ${command.injectScript?.length ?: 0} chars")
                }
                is RemoteCommand.InjectScript -> {
                    PassageLogger.info(TAG, "Inject script: ${command.injectScript?.length ?: 0} characters")
                }
                is RemoteCommand.Done -> {
                    PassageLogger.info(TAG, "Done command - success: ${command.success}")
                }
            }

            // Store current command
            currentCommand = command

            // Track analytics
            val userActionRequired = commandData.optBoolean("userActionRequired", false)
            PassageLogger.info(TAG, "User action required: $userActionRequired")
            PassageAnalytics.trackCommandReceived(command.id, command.javaClass.simpleName, userActionRequired)

            // Execute command
            executeCommand(command)

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Error handling command", e)
        }
    }

    private fun handleErrorEvent(args: Array<Any>) {
        val error = args[0]?.toString() ?: "Unknown error"
        PassageLogger.error(TAG, "Received error event: $error")

        onError?.invoke(PassageErrorData(error, null))
        PassageAnalytics.trackOnError(error, null)
    }

    private fun parseConnectionData(data: JSONObject): List<Map<String, Any>> {
        val dataArray = data.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<Map<String, Any>>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            val map = mutableMapOf<String, Any>()
            item.keys().forEach { key ->
                val keyString = key as? String ?: return@forEach
                item.get(key)?.let { value ->
                    map[keyString] = value
                }
            }
            result.add(map)
        }

        return result
    }

    private fun parseCommand(data: JSONObject): RemoteCommand {
        val id = data.getString("id")
        val type = data.getString("type")
        val args = parseArgs(data.optJSONObject("args"))
        val injectScript = data.optString("injectScript", null)
        val cookieDomains = parseCookieDomains(data.optJSONArray("cookieDomains"))
        val userActionRequired = data.optBoolean("userActionRequired", false)

        return when (type) {
            "navigate" -> {
                val url = args["url"] as? String ?: ""
                val successUrls = parseSuccessUrls(data.optJSONArray("successUrls"))
                RemoteCommand.Navigate(id, args, url, successUrls, injectScript, cookieDomains, userActionRequired)
            }
            "click" -> RemoteCommand.Click(id, args, injectScript, cookieDomains, userActionRequired)
            "input" -> RemoteCommand.Input(id, args, injectScript, cookieDomains, userActionRequired)
            "wait" -> RemoteCommand.Wait(id, args, injectScript, cookieDomains, userActionRequired)
            "injectScript" -> RemoteCommand.InjectScript(id, args, injectScript, cookieDomains, userActionRequired)
            "done" -> {
                val success = args["success"] as? Boolean ?: true
                val doneData = args["data"]
                RemoteCommand.Done(id, args, success, doneData, injectScript, cookieDomains, userActionRequired)
            }
            else -> throw IllegalArgumentException("Unknown command type: $type")
        }
    }

    private fun parseArgs(argsObject: JSONObject?): Map<String, Any> {
        if (argsObject == null) return emptyMap()

        val map = mutableMapOf<String, Any>()
        argsObject.keys().forEach { key ->
            val keyString = key as? String ?: return@forEach
            argsObject.get(key)?.let { value ->
                map[keyString] = value
            }
        }
        return map
    }

    private fun parseCookieDomains(array: JSONArray?): List<String> {
        if (array == null) return emptyList()

        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    private fun parseSuccessUrls(array: JSONArray?): List<SuccessUrl> {
        if (array == null) return emptyList()

        val list = mutableListOf<SuccessUrl>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(SuccessUrl(
                urlPattern = obj.getString("urlPattern"),
                navigationType = SuccessUrl.NavigationType.valueOf(obj.getString("navigationType"))
            ))
        }
        return list
    }

    private fun executeCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Navigate -> executeNavigateCommand(command)
            is RemoteCommand.Click -> executeScriptCommand(command)
            is RemoteCommand.Input -> executeScriptCommand(command)
            is RemoteCommand.Wait -> executeScriptCommand(command)
            is RemoteCommand.InjectScript -> executeScriptCommand(command)
            is RemoteCommand.Done -> executeDoneCommand(command)
        }
    }

    private fun executeNavigateCommand(command: RemoteCommand.Navigate) {
        PassageLogger.info(TAG, "========== EXECUTING NAVIGATE COMMAND ==========")
        PassageLogger.info(TAG, "Navigate URL: ${command.url}")
        PassageLogger.info(TAG, "Command ID: ${command.id}")

        // Store success URLs
        currentSuccessUrls = command.successUrls ?: emptyList()
        PassageLogger.info(TAG, "Success URLs received: ${currentSuccessUrls.size}")

        for ((index, successUrl) in currentSuccessUrls.withIndex()) {
            PassageLogger.info(TAG, "Success URL #$index:")
            PassageLogger.info(TAG, "  Pattern: '${successUrl.urlPattern}'")
            PassageLogger.info(TAG, "  Type: '${successUrl.navigationType}'")
        }

        if (currentSuccessUrls.isEmpty()) {
            PassageLogger.warn(TAG, "⚠️ NO SUCCESS URLs provided - authentication detection may fail!")
        }

        // Inject console logging for navigation tracking
        val navigationScript = """
            console.log('[Passage] Navigate command starting: ${command.id}');
            console.log('[Passage] Target URL: ${command.url}');
            console.log('[Passage] Current URL before navigation:', window.location.href);

            // Log when navigation completes
            const originalOnLoad = window.onload;
            window.onload = function(event) {
                console.log('[Passage] Navigation completed for command: ${command.id}');
                console.log('[Passage] Final URL:', window.location.href);
                if (originalOnLoad) originalOnLoad.call(window, event);
            };
        """.trimIndent()

        // Inject the navigation tracking script
        sendBroadcast(PassageConstants.BroadcastActions.INJECT_SCRIPT, mapOf(
            "script" to navigationScript,
            "commandId" to "${command.id}_nav_tracking",
            "commandType" to "NavigationTracking"
        ))

        // Send navigation broadcast with command details
        sendBroadcast(PassageConstants.BroadcastActions.NAVIGATE_IN_AUTOMATION, mapOf(
            "url" to command.url,
            "commandId" to command.id
        ))
    }

    private fun executeScriptCommand(command: RemoteCommand) {
        val script = command.injectScript ?: return

        PassageLogger.info(TAG, "========== EXECUTING SCRIPT COMMAND ==========")
        PassageLogger.info(TAG, "Command ID: ${command.id}")
        PassageLogger.info(TAG, "Command Type: ${command.javaClass.simpleName}")
        PassageLogger.info(TAG, "Script length: ${script.length} characters")

        // Log first 200 characters of script for debugging
        val scriptPreview = if (script.length > 200) script.substring(0, 200) + "..." else script
        PassageLogger.debug(TAG, "Script preview: $scriptPreview")

        // Store wait commands for reinjection after navigation
        // This matches Swift behavior where wait commands persist across page transitions
        if (command is RemoteCommand.Wait) {
            lastWaitCommand = command
            executingWaitCommand = command  // Mark as currently executing
            PassageLogger.debug(TAG, "[REMOTE CONTROL] Stored wait command for potential reinjection: ${command.id}")
        }

        // Store user action commands for potential re-execution
        if (command.userActionRequired == true) {
            lastUserActionCommand = command
            PassageLogger.debug(TAG, "[REMOTE CONTROL] Stored user action command: ${command.id}")
        }

        // Add console logging to the script
        val scriptWithLogging = """
            console.log('[Passage] Executing ${command.javaClass.simpleName} command: ${command.id}');
            try {
                $script
                console.log('[Passage] ${command.javaClass.simpleName} command completed: ${command.id}');
            } catch (error) {
                console.error('[Passage] ${command.javaClass.simpleName} command failed: ${command.id}', error);
                throw error;
            }
        """.trimIndent()

        // Send script injection broadcast with command details
        sendBroadcast(PassageConstants.BroadcastActions.INJECT_SCRIPT, mapOf(
            "script" to scriptWithLogging,
            "commandId" to command.id,
            "commandType" to command.javaClass.simpleName
        ))
    }

    private fun executeDoneCommand(command: RemoteCommand.Done) {
        PassageLogger.info(TAG, "========== EXECUTING DONE COMMAND ==========")
        PassageLogger.info(TAG, "Done command success: ${command.success}")
        PassageLogger.info(TAG, "Done command ID: ${command.id}")
        PassageLogger.info(TAG, "Done command data: ${command.data}")

        // Always switch to UI WebView for final result display (matching Swift)
        if (currentWebViewType != PassageConstants.WebViewTypes.UI) {
            PassageLogger.info(TAG, "Switching to UI WebView for final result display")
            sendBroadcast(PassageConstants.BroadcastActions.SHOW_UI_WEBVIEW)
            currentWebViewType = PassageConstants.WebViewTypes.UI
        }

        if (command.success) {
            PassageLogger.info(TAG, "✅ AUTHENTICATION SUCCESS - Done command marked as success")

            // Log the raw command.data first
            PassageLogger.info(TAG, "========== RAW DONE COMMAND DATA ==========")
            PassageLogger.info(TAG, "command.data type: ${command.data?.javaClass?.simpleName}")
            PassageLogger.info(TAG, "command.data: ${command.data}")
            PassageLogger.info(TAG, "============================================")

            // Use async page data collection for done command like Swift
            launchInScope("doneCommandSuccess") {
                val pageData = collectPageData()

                // Send success command result
                sendCommandResultHttp(command.id, "success", command.data, pageData, null)

                // Convert command.data to Map - it could be JSONObject or already a Map
                val commandData = when (command.data) {
                    is JSONObject -> (command.data as JSONObject).toMap()
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        command.data as Map<String, Any>
                    }
                    else -> null
                }

                val connectionId = commandData?.get("connectionId") as? String ?: ""

                // Get history array directly from commandData["history"]
                val historyData = when (val historyField = commandData?.get("history")) {
                    is JSONArray -> historyField.toList().mapNotNull { it as? Map<String, Any> }
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        historyField as? List<Map<String, Any>> ?: emptyList()
                    }
                    else -> emptyList()
                }

                PassageLogger.info(TAG, "Extracted connectionId: $connectionId")
                PassageLogger.info(TAG, "Found ${historyData.size} history items")

                val history = historyData.map { item ->
                    PassageHistoryItem(
                        structuredData = item,
                        additionalData = emptyMap()
                    )
                }

                val successData = PassageSuccessData(
                    history = history,
                    connectionId = connectionId
                )

                PassageLogger.info(TAG, "========== SUCCESS DATA ==========")
                PassageLogger.info(TAG, "successData.connectionId: $connectionId")
                PassageLogger.info(TAG, "successData.history.size: ${history.size}")
                history.forEachIndexed { index, item ->
                    PassageLogger.info(TAG, "History item #$index: ${item.structuredData}")
                }
                PassageLogger.info(TAG, "Full successData: $successData")
                PassageLogger.info(TAG, "===================================")

                PassageLogger.info(TAG, "About to call onSuccess callback")
                onSuccess?.invoke(successData)
                PassageLogger.info(TAG, "onSuccess callback completed")

                // Navigate to success URL in UI webview (matching Swift)
                val successUrl = buildConnectUrl(success = true)
                sendBroadcast(PassageConstants.BroadcastActions.NAVIGATE, mapOf("url" to successUrl))
            }
        } else {
            PassageLogger.error(TAG, "❌ AUTHENTICATION FAILED - Done command marked as failure")
            val commandData = command.data as? Map<String, Any>
            val errorMessage = commandData?.get("error") as? String ?: "Done command indicates failure"
            val errorDetails = commandData?.get("details") as? String
            PassageLogger.error(TAG, "Error message: $errorMessage")
            PassageLogger.error(TAG, "Error details: $errorDetails")

            // Send error command result (matching Swift)
            launchInScope("doneCommandError") {
                sendCommandResultHttp(command.id, "error", null, null, errorMessage)

                val errorData = PassageErrorData(errorMessage, command.data)
                onError?.invoke(errorData)

                // Navigate to error URL in UI webview (matching Swift)
                val errorUrl = buildConnectUrl(success = false, error = errorMessage)
                sendBroadcast(PassageConstants.BroadcastActions.NAVIGATE, mapOf("url" to errorUrl))
            }
        }
    }

    private fun parseHistoryFromDoneCommand(data: Any?): List<PassageHistoryItem> {
        val commandData = data as? Map<String, Any> ?: return emptyList()
        val historyWrapper = commandData["history"] as? Map<String, Any> ?: return emptyList()
        val historyArray = historyWrapper["history"] as? List<Map<String, Any>> ?: return emptyList()

        PassageLogger.info(TAG, "Parsed ${historyArray.size} history items from done command")

        return historyArray.map { item ->
            // The items from done command are already structured data (books, etc.)
            // No need to extract structuredData property (matching Swift implementation)
            PassageHistoryItem(
                structuredData = item,
                additionalData = emptyMap()
            )
        }
    }

    private fun parsePageDataFromMap(data: Map<String, Any>): PageData {
        // Parse localStorage
        val localStorageList = data["localStorage"] as? List<Map<String, Any>>
        val localStorage = localStorageList?.mapNotNull { item ->
            val name = item["name"] as? String
            val value = item["value"] as? String
            if (name != null && value != null) {
                StorageItem(name, value)
            } else null
        }

        // Parse sessionStorage
        val sessionStorageList = data["sessionStorage"] as? List<Map<String, Any>>
        val sessionStorage = sessionStorageList?.mapNotNull { item ->
            val name = item["name"] as? String
            val value = item["value"] as? String
            if (name != null && value != null) {
                StorageItem(name, value)
            } else null
        }

        // Parse cookies
        val cookieList = data["cookies"] as? List<Map<String, Any>>
        val cookies = cookieList?.mapNotNull { item ->
            val name = item["name"] as? String
            val value = item["value"] as? String
            val domain = item["domain"] as? String ?: ""
            val path = item["path"] as? String
            val expires = item["expires"] as? Double
            val secure = item["secure"] as? Boolean
            val httpOnly = item["httpOnly"] as? Boolean
            val sameSite = item["sameSite"] as? String
            if (name != null && value != null) {
                CookieData(name, value, domain, path, expires, secure, httpOnly, sameSite)
            } else null
        }

        // Parse other fields
        val html = data["html"] as? String
        val url = data["url"] as? String

        return PageData(
            cookies = cookies,
            localStorage = localStorage,
            sessionStorage = sessionStorage,
            html = html,
            url = url,
            screenshot = null // Screenshots are handled separately
        )
    }

    private fun buildConnectUrl(success: Boolean, error: String? = null): String {
        val baseUrl = "${config.baseUrl}${PassageConstants.Paths.CONNECT}"
        val intentToken = this.intentToken ?: ""
        val agentName = config.agentName

        var url = "$baseUrl?intentToken=$intentToken&success=$success&appAgentName=$agentName"

        if (error != null) {
            url += "&error=${java.net.URLEncoder.encode(error, "UTF-8")}"
        }

        return url
    }

    fun sendCommandResult(
        commandId: String,
        status: String,
        data: Any?,
        pageData: PageData?,
        error: String?
    ) {
        // Deprecated - use sendCommandResultHttp instead
        launchInScope("sendCommandResult:$commandId") {
            sendCommandResultHttp(commandId, status, data, pageData, error)
        }
    }

    private suspend fun sendCommandResultHttp(
        commandId: String,
        status: String,
        data: Any?,
        pageData: PageData?,
        error: String?
    ) {
        val url = "${config.socketUrl}${PassageConstants.Paths.AUTOMATION_COMMAND_RESULT}"

        val result = JSONObject().apply {
            put("id", commandId)
            put("status", status)
            data?.let { putFlexibleJson("data", it) }
            pageData?.let { put("pageData", JSONObject(gson.toJson(it))) }
            error?.let { put("error", it) }
        }

        try {
            // Logging: summarize what we're about to send (bounded)
            PassageLogger.debug(TAG, "----- Command Result: BEGIN -----")
            PassageLogger.debug(TAG, "Endpoint: $url")
            PassageLogger.debug(TAG, "  id=$commandId status=$status errorPresent=${error != null}")
            data?.let {
                val dataType = it.javaClass.simpleName
                val dataPreview = try {
                    when (it) {
                        is String -> PassageLogger.truncateData(it, PassageConstants.Logging.MAX_DATA_LENGTH)
                        is Number, is Boolean -> it.toString()
                        else -> PassageLogger.truncateData(gson.toJson(it), PassageConstants.Logging.MAX_DATA_LENGTH)
                    }
                } catch (_: Exception) { it.toString() }
                PassageLogger.debug(TAG, "  data type=$dataType preview=${dataPreview}")
            }
            pageData?.let { pd ->
                val urlPreview = pd.url?.let { u -> PassageLogger.truncateUrl(u, PassageConstants.Logging.MAX_URL_LENGTH) } ?: "nil"
                val htmlLen = pd.html?.length ?: 0
                val cookieCount = pd.cookies?.size ?: 0
                val lsCount = pd.localStorage?.size ?: 0
                val ssCount = pd.sessionStorage?.size ?: 0
                PassageLogger.debug(TAG, "  pageData url=$urlPreview htmlLen=$htmlLen cookies=$cookieCount localStorage=$lsCount sessionStorage=$ssCount")
            }

            // Build a sanitized preview of the payload JSON for logs
            val resultForLog = try { JSONObject(result.toString()) } catch (_: Exception) { result }
            resultForLog.optJSONObject("pageData")?.let { pd ->
                val html = pd.optString("html", null)
                if (html != null) pd.put("html", PassageLogger.truncateHtml(html, PassageConstants.Logging.MAX_HTML_LENGTH))
                val url = pd.optString("url", null)
                if (url != null) pd.put("url", PassageLogger.truncateUrl(url, PassageConstants.Logging.MAX_URL_LENGTH))
            }
            val dataNode = resultForLog.opt("data")
            if (dataNode is String) {
                resultForLog.put("data", PassageLogger.truncateData(dataNode, PassageConstants.Logging.MAX_DATA_LENGTH))
            }
            val payloadPreview = resultForLog.toString()
            val payloadBytes = result.toString().toByteArray().size
            PassageLogger.debug(TAG, "  payload bytes=$payloadBytes length=${payloadPreview.length}")
            PassageLogger.debug(TAG, "  payload preview=${PassageLogger.truncateData(payloadPreview, PassageConstants.Logging.MAX_DATA_LENGTH)}")

            val request = Request.Builder()
                .url(url)
                .addHeader("x-intent-token", intentToken ?: "")
                .addHeader("Content-Type", "application/json")
                .post(result.toString().toRequestBody("application/json".toMediaType()))
                .build()

            PassageLogger.info(TAG, "Sending command result via HTTP: $commandId -> $status")

            val startTime = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val durationMs = System.currentTimeMillis() - startTime
                val responseBody = try { response.body?.string() } catch (_: Exception) { null }
                val bodyPreview = responseBody?.let { PassageLogger.truncateData(it, PassageConstants.Logging.MAX_DATA_LENGTH) }

                if (response.isSuccessful) {
                    PassageLogger.info(TAG, "Command result sent successfully for: $commandId (code=${response.code}, ${durationMs}ms)")
                    bodyPreview?.let { PassageLogger.debug(TAG, "Response body: $it") }

                    if (status == "success") {
                        PassageAnalytics.trackCommandSuccess(commandId, currentCommand?.javaClass?.simpleName ?: "")
                    } else {
                        PassageAnalytics.trackCommandError(commandId, currentCommand?.javaClass?.simpleName ?: "", error ?: "")
                    }
                } else {
                    PassageLogger.error(TAG, "Failed to send command result: ${response.code} ${response.message} (${durationMs}ms)")
                    bodyPreview?.let { PassageLogger.error(TAG, "Response body: $it") }
                }
            }

            PassageLogger.debug(TAG, "----- Command Result: END -----")

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Error sending command result", e)
            PassageLogger.debug(TAG, "----- Command Result: END (exception) -----")
        }
    }

    private fun JSONObject.putFlexibleJson(key: String, value: Any) {
        try {
            when (value) {
                is JSONObject -> put(key, value)
                is JSONArray -> put(key, value)
                is Map<*, *> -> put(key, JSONObject(gson.toJson(value)))
                is Collection<*> -> put(key, JSONArray(gson.toJson(value)))
                is Array<*> -> put(key, JSONArray(gson.toJson(value)))
                is Number, is Boolean -> put(key, value)
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try { put(key, JSONObject(trimmed)) } catch (_: Exception) { put(key, value) }
                    } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        try { put(key, JSONArray(trimmed)) } catch (_: Exception) { put(key, value) }
                    } else {
                        put(key, value)
                    }
                }
                else -> put(key, JSONObject(gson.toJson(value)))
            }
        } catch (_: Exception) {
            // Fallback to string representation on any failure
            put(key, value.toString())
        }
    }

    private suspend fun collectPageData(): PageData? = suspendCoroutine { continuation ->
        PassageLogger.debug(TAG, "Collecting page data from automation webview...")

        // JavaScript to collect page data (matching Swift implementation)
        val pageDataScript = """
        (function() {
            const errors = [];

            function isOpaqueOrigin() {
                try {
                    if (!window.location) { return true; }
                    const href = window.location.href || '';
                    if (href === '' || href === 'about:blank') { return true; }
                    return window.location.origin === 'null';
                } catch (originErr) {
                    return true;
                }
            }

            const opaqueOrigin = isOpaqueOrigin();

            function recordError(section, err) {
                const message = err && (err.message || err.toString()) || 'Unknown error';
                const name = err && err.name ? err.name : 'Error';
                errors.push({ section, name, message });
                const logMessage = '[Passage] Page data ' + section + ' error:';
                if (name === 'SecurityError') {
                    console.warn(logMessage, err);
                } else {
                    console.error(logMessage, err);
                }
            }

            function safeCollectStorage(getStorage, label) {
                const items = [];
                if (opaqueOrigin) {
                    console.warn('[Passage] Skipping ' + label + ' collection for opaque origin');
                    return items;
                }
                let storage;
                try {
                    storage = getStorage();
                } catch (accessErr) {
                    recordError(label + '.access', accessErr);
                    return items;
                }

                try {
                    if (storage && typeof storage.length === 'number') {
                        for (let i = 0; i < storage.length; i++) {
                            const key = storage.key(i);
                            if (key != null) {
                                let value = null;
                                try {
                                    value = storage.getItem(key);
                                } catch (getErr) {
                                    recordError(label + '.getItem', getErr);
                                }
                                items.push({ name: key, value });
                            }
                        }
                    }
                } catch (err) {
                    recordError(label, err);
                }
                return items;
            }

            function safeCollectCookies() {
                const cookies = [];
                if (opaqueOrigin) {
                    console.warn('[Passage] Skipping cookie collection for opaque origin');
                    return cookies;
                }
                try {
                    if (document.cookie) {
                        document.cookie.split(';').forEach(rawCookie => {
                            const [namePart, ...valueParts] = rawCookie.trim().split('=');
                            if (!namePart) { return; }
                            const value = valueParts.join('=');
                            cookies.push({
                                name: namePart.trim(),
                                value: (value || '').trim(),
                                domain: location.hostname || null,
                                path: '/',
                                secure: false,
                                httpOnly: false,
                                sameSite: 'None'
                            });
                        });
                    }
                } catch (err) {
                    recordError('cookies', err);
                }
                return cookies;
            }

            const pageData = {
                localStorage: safeCollectStorage(function() { return window.localStorage; }, 'localStorage'),
                sessionStorage: safeCollectStorage(function() { return window.sessionStorage; }, 'sessionStorage'),
                cookies: safeCollectCookies(),
                html: null,
                url: null,
                errors
            };

            try {
                pageData.html = document.documentElement ? document.documentElement.outerHTML : null;
            } catch (err) {
                recordError('html', err);
            }

            try {
                pageData.url = window.location ? window.location.href : null;
            } catch (err) {
                recordError('url', err);
            }

            console.log('[Passage] Page data collection completed with errors: ' + errors.length);

            if (window.passage && window.passage.postMessage) {
                window.passage.postMessage({
                    type: 'pageData',
                    data: pageData,
                    webViewType: 'automation'
                });
            } else {
                console.error('[Passage] passage.postMessage not available');
            }

            return pageData;
        })();
        """.trimIndent()

        // Store the continuation for when we receive the response
        pageDataContinuation = continuation

        // Send broadcast to collect page data
        sendBroadcast(PassageConstants.BroadcastActions.COLLECT_PAGE_DATA, mapOf(
            "script" to pageDataScript
        ))

        // Set timeout to avoid hanging indefinitely
        launchInScope("pageDataTimeout") {
            delay(5000) // 5 second timeout
            if (pageDataContinuation == continuation) {
                PassageLogger.warn(TAG, "Page data collection timed out")
                pageDataContinuation = null
                continuation.resume(null)
            }
        }
    }

    // Success URL checking

    fun checkNavigationStart(url: String) {
        if (checkSuccessUrlMatch(url, SuccessUrl.NavigationType.NAVIGATION_START)) {
            handleSuccessUrlMatch(url)
        }
    }

    fun checkNavigationEnd(url: String) {
        if (checkSuccessUrlMatch(url, SuccessUrl.NavigationType.NAVIGATION_END)) {
            handleSuccessUrlMatch(url)
        }
    }

    private fun checkSuccessUrlMatch(url: String, type: SuccessUrl.NavigationType): Boolean {
        PassageLogger.debug(TAG, "========== CHECKING SUCCESS URL MATCH ==========")
        PassageLogger.debug(TAG, "Checking URL: ${url.take(100)}")
        PassageLogger.debug(TAG, "Navigation type: $type")
        PassageLogger.debug(TAG, "Available success URLs: ${currentSuccessUrls.size}")

        for ((index, successUrl) in currentSuccessUrls.withIndex()) {
            PassageLogger.debug(TAG, "Success URL #$index: pattern='${successUrl.urlPattern}', type='${successUrl.navigationType}'")

            if (successUrl.navigationType == type) {
                PassageLogger.debug(TAG, "Navigation type matches, checking URL pattern...")
                if (urlMatches(url, successUrl.urlPattern)) {
                    PassageLogger.info(TAG, "✅ SUCCESS URL MATCH FOUND: $url matches ${successUrl.urlPattern}")
                    return true
                } else {
                    PassageLogger.debug(TAG, "URL pattern does not match")
                }
            } else {
                PassageLogger.debug(TAG, "Navigation type mismatch: expected '$type', got '${successUrl.navigationType}'")
            }
        }

        PassageLogger.warn(TAG, "❌ NO SUCCESS URL MATCH: $url (type: $type)")
        return false
    }

    private fun urlMatches(url: String, pattern: String): Boolean {
        PassageLogger.debug(TAG, "URL matching: '$url' vs pattern '$pattern'")

        // Exact match
        if (url == pattern) {
            PassageLogger.debug(TAG, "✅ Exact match found")
            return true
        }

        // Wildcard support
        if (pattern.contains("*")) {
            val prefix = pattern.replace("*", "")
            val matches = url.startsWith(prefix)
            PassageLogger.debug(TAG, "Wildcard pattern check: prefix='$prefix', matches=$matches")
            return matches
        }

        // Prefix match
        val matches = url.startsWith(pattern)
        PassageLogger.debug(TAG, "Prefix match check: matches=$matches")
        return matches
    }

    private fun handleSuccessUrlMatch(url: String) {
        PassageLogger.info(TAG, "✅ Switching to UI webview due to success URL match")
        sendBroadcast(PassageConstants.BroadcastActions.SHOW_UI_WEBVIEW)
        currentWebViewType = PassageConstants.WebViewTypes.UI
    }

    // Screenshot capture

    private fun startScreenshotCapture() {
        if (!captureScreenshotFlag) return

        PassageLogger.info(TAG, "Starting screenshot capture with interval: ${screenshotInterval}ms")

        screenshotTimer?.cancel()
        screenshotTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    launchInScope("captureScreenshot") {
                        captureAndSendScreenshot()
                    }
                }
            }, 0, screenshotInterval ?: SCREENSHOT_DEFAULT_INTERVAL)
        }
    }

    private suspend fun captureAndSendScreenshot() {
        val screenshot = captureImageFunction?.invoke()
        // Ask Activity for current URL; Activity will reply via SEND_BROWSER_STATE
        try {
            val intent = Intent(PassageConstants.BroadcastActions.GET_CURRENT_URL).apply {
                screenshot?.let { putExtra("screenshot", it) }
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            PassageLogger.debug(TAG, "Requested current URL for browser state (GET_CURRENT_URL)")
        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to request current URL for browser state", e)
        }
    }

    private fun handleSendBrowserState(intent: Intent) {
        val url = intent.getStringExtra("url")
        val screenshot = intent.getStringExtra("screenshot")
        if (url.isNullOrEmpty()) {
            PassageLogger.warn(TAG, "SEND_BROWSER_STATE received without URL")
            return
        }
        launchInScope("sendBrowserState") {
            val endpoint = "${config.socketUrl}${PassageConstants.Paths.BROWSER_STATE}"
            val browserState = BrowserStateData(url = url, screenshot = screenshot)
            try {
                val body = gson.toJson(browserState).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .addHeader("x-intent-token", intentToken ?: "")
                    .build()
                httpClient.newCall(request).execute().use {
                    PassageLogger.info(TAG, "Browser state sent (url=${url.take(100)}; screenshot=${!screenshot.isNullOrEmpty()})")
                }
            } catch (e: Exception) {
                PassageLogger.error(TAG, "Failed to send browser state", e)
            }
        }
    }

    // Navigation handling

    fun handleNavigationComplete(url: String) {
        PassageLogger.debug(TAG, "[REMOTE CONTROL] Navigation complete called for URL: ${PassageLogger.truncateUrl(url, 100)}")

        // Clear any executing wait command since navigation invalidates it
        executingWaitCommand?.let {
            PassageLogger.debug(TAG, "[REMOTE CONTROL] Clearing executing wait command due to navigation: ${it.id}")
            executingWaitCommand = null
        }

        // Handle navigation command completion
        currentCommand?.let { command ->
            if (command is RemoteCommand.Navigate) {
                PassageLogger.info(TAG, "[REMOTE CONTROL] Completing navigation command: ${command.id}")
                // Collect page data before sending result so backend receives pageData
                launchInScope("navigationComplete:${command.id}") {
                    val pageData = collectPageData()
                    sendCommandResultHttp(command.id, "success", mapOf("url" to url), pageData, null)
                }
                currentCommand = null
            }
        }

        // Check if we need to reinject a wait command after navigation
        // This matches Swift behavior - reinject after ANY navigation, not just Navigate commands
        lastWaitCommand?.let { waitCommand ->
            PassageLogger.info(TAG, "[REMOTE CONTROL] Re-injecting wait command after navigation: ${waitCommand.id}")

            // Add a delay to ensure page is fully loaded before reinjecting
            launchInScope("reinjectWait:${waitCommand.id}") {
                delay(1000) // 1 second delay matches Swift: DispatchQueue.main.asyncAfter(deadline: .now() + 1.0)
                executeScriptCommand(waitCommand)
            }
        }
    }

    // Command result handlers from WebView activity

    private fun handleScriptExecutionResult(intent: Intent) {
        val commandId = intent.getStringExtra("commandId") ?: return
        val success = intent.getBooleanExtra("success", false)
        val result = intent.getStringExtra("result")

        PassageLogger.info(TAG, "[REMOTE CONTROL] Script execution result for command: $commandId, success: $success")

        // Clear wait command if it completed (successfully or not) - matches Swift behavior
        lastWaitCommand?.let { waitCommand ->
            if (waitCommand.id == commandId) {
                PassageLogger.debug(TAG, "[REMOTE CONTROL] Clearing completed wait command: $commandId")
                lastWaitCommand = null
            }
        }
        executingWaitCommand?.let { waitCommand ->
            if (waitCommand.id == commandId) {
                PassageLogger.debug(TAG, "[REMOTE CONTROL] Clearing executing wait command: $commandId")
                executingWaitCommand = null
            }
        }

        launchInScope("scriptResult:$commandId") {
            if (success) {
                val pageData = collectPageData()
                sendCommandResultHttp(commandId, "success", result, pageData, null)
            } else {
                val error = intent.getStringExtra("error") ?: "Script execution failed"
                sendCommandResultHttp(commandId, "error", null, null, error)
            }
        }
    }

    private fun handleNavigationComplete(intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val webViewType = intent.getStringExtra("webViewType") ?: return

        if (webViewType == PassageConstants.WebViewTypes.AUTOMATION) {
            PassageLogger.info(TAG, "Navigation completed in automation WebView: $url")

            // If current command is navigate, send command result
            currentCommand?.let { command ->
                if (command is RemoteCommand.Navigate) {
                    launchInScope("automationNavComplete:${command.id}") {
                        val pageData = collectPageData()
                        sendCommandResultHttp(command.id, "success", mapOf("url" to url), pageData, null)
                    }
                }
            }

            // Check for success URL patterns
            checkSuccessUrlMatch(url)
        }
    }

    private fun checkSuccessUrlMatch(url: String) {
        currentSuccessUrls.forEach { successUrl ->
            if (url.contains(successUrl.urlPattern) || successUrl.urlPattern.contains("*")) {
                PassageLogger.info(TAG, "Success URL pattern matched: ${successUrl.urlPattern}")

                // Switch to UI WebView
                sendBroadcast(PassageConstants.BroadcastActions.SHOW_UI_WEBVIEW)
                currentWebViewType = PassageConstants.WebViewTypes.UI
            }
        }
    }

    // WebView message handling

    fun handleWebViewMessage(message: Map<String, Any>) {
        // Handle messages from WebView
        val type = message["type"] as? String ?: return
        val webViewType = message["webViewType"] as? String

        PassageLogger.info(TAG, "========== WEBVIEW MESSAGE RECEIVED ==========")
        PassageLogger.info(TAG, "Message type: $type")
        PassageLogger.info(TAG, "WebView type: $webViewType")
        PassageLogger.info(TAG, "Message content: ${summarizeMessageForLogging(type, message)}")

        when (type) {
            "commandResult" -> {
                val commandId = message["commandId"] as? String ?: return
                val success = message["success"] as? Boolean ?: false
                val result = message["result"]

                PassageLogger.info(TAG, "Command result: $commandId -> success=$success")
                if (success) {
                    launchInScope("webMessageCommandResult:$commandId") {
                        val pageData = collectPageData()
                        sendCommandResultHttp(commandId, "success", result, pageData, null)
                    }
                } else {
                    val error = message["error"] as? String ?: "Command failed"
                    launchInScope("webMessageCommandError:$commandId") {
                        sendCommandResultHttp(commandId, "error", null, null, error)
                    }
                }
            }
            "wait" -> {
                // Handle Wait command results (sent by injected scripts)
                val commandId = message["commandId"] as? String ?: return
                val value = message["value"] as? Boolean ?: false
                var result = message["result"]

                // Handle undefined/null result for successful wait commands (common after reinjection)
                if (value && result == null) {
                    result = true  // Send the boolean success value when result is undefined
                    PassageLogger.debug(TAG, "Wait command succeeded with undefined result, using boolean value: true")
                }

                PassageLogger.info(TAG, "Wait command result: $commandId -> value=$value, result=$result")
                if (value) {
                    // Clear wait command tracking to prevent re-execution after successful completion
                    if (lastWaitCommand?.id == commandId) {
                        PassageLogger.debug(TAG, "Clearing lastWaitCommand after successful completion: $commandId")
                        lastWaitCommand = null
                    }
                    if (executingWaitCommand?.id == commandId) {
                        PassageLogger.debug(TAG, "Clearing executingWaitCommand after successful completion: $commandId")
                        executingWaitCommand = null
                    }

                    launchInScope("waitCommandSuccess:$commandId") {
                        val pageData = collectPageData()
                        sendCommandResultHttp(commandId, "success", result, pageData, null)
                    }
                } else {
                    // Clear executing wait command on failure too
                    if (executingWaitCommand?.id == commandId) {
                        PassageLogger.debug(TAG, "Clearing executingWaitCommand after failure: $commandId")
                        executingWaitCommand = null
                    }
                    launchInScope("waitCommandError:$commandId") {
                        sendCommandResultHttp(commandId, "error", null, null, "Wait command failed")
                    }
                }
            }
            "injectScript" -> {
                // Handle InjectScript command results (sent by injected scripts via window.passage.postMessage)
                val commandId = message["commandId"] as? String ?: return
                val error = message["error"] as? String
                val valueData = message["value"] ?: message["result"]

                PassageLogger.info(
                    TAG,
                    "InjectScript command result: $commandId -> errorPresent=${error != null} valueType=${valueData?.javaClass?.simpleName}"
                )

                if (error == null) {
                    launchInScope("injectScriptSuccess:$commandId") {
                        val pageData = collectPageData()
                        sendCommandResultHttp(commandId, "success", valueData, pageData, null)
                    }
                } else {
                    launchInScope("injectScriptError:$commandId") {
                        sendCommandResultHttp(commandId, "error", null, null, error)
                    }
                }
            }
            "webViewChanged" -> {
                // Handle webview switching notifications from the JavaScript side
                val changedWebViewType = message["webViewType"] as? String
                PassageLogger.info(TAG, "WebView changed to: $changedWebViewType")
                // This is informational - the actual webview switching is handled by userActionRequired
            }
            "pageData" -> {
                // Handle page data collection results
                PassageLogger.debug(TAG, "Received page data from webview")
                val continuation = pageDataContinuation
                if (continuation != null) {
                    pageDataContinuation = null

                    val dataObj = message["data"]
                    val data = coerceToMap(dataObj)

                    if (data != null) {
                        val errorList = data["errors"] as? Collection<*>
                        if (!errorList.isNullOrEmpty()) {
                            PassageLogger.warn(TAG, "Page data reported ${errorList.size} collection errors")
                            errorList.forEachIndexed { index, item ->
                                PassageLogger.warn(TAG, "  error[$index]: ${PassageLogger.truncateData(item.toString(), 200)}")
                            }
                        }

                        // Parse page data into PageData model
                        val pageData = parsePageDataFromMap(data)
                        PassageLogger.debug(TAG, "Page data parsed successfully")
                        continuation.resume(pageData)
                    } else {
                        val error = message["error"] as? String
                        PassageLogger.error(TAG, "Page data collection error: $error")
                        continuation.resume(null)
                    }
                } else {
                    PassageLogger.warn(TAG, "Received page data but no continuation waiting")
                }
            }
            "console_error" -> {
                // Handle JavaScript console errors
                val errorMessage = message["message"] as? String ?: "Unknown console error"
                val sourceWebViewType = message["webViewType"] as? String ?: "unknown"
                PassageLogger.error(TAG, "[$sourceWebViewType WebView] JavaScript Console Error: $errorMessage")
            }
            "javascript_error" -> {
                // Handle JavaScript runtime errors
                val errorMessage = message["message"] as? String ?: "Unknown error"
                val isWeakMapError = message["isWeakMapError"] as? Boolean ?: false
                val sourceWebViewType = message["webViewType"] as? String ?: "unknown"
                val source = message["source"] as? String
                val line = message["line"] as? Int
                val column = message["column"] as? Int
                val stack = message["stack"] as? String

                if (isWeakMapError) {
                    PassageLogger.warn(TAG, "[$sourceWebViewType WebView] WeakMap error detected: $errorMessage")
                    PassageLogger.warn(TAG, "This may be caused by global JavaScript injection timing")
                } else {
                    PassageLogger.error(TAG, "[$sourceWebViewType WebView] JavaScript Error: $errorMessage")
                    if (source != null) PassageLogger.error(TAG, "  Source: $source:$line:$column")
                    if (stack != null) PassageLogger.error(TAG, "  Stack: $stack")
                }
            }
            "unhandled_rejection" -> {
                // Handle unhandled promise rejections
                val errorMessage = message["message"] as? String ?: "Unknown rejection"
                val sourceWebViewType = message["webViewType"] as? String ?: "unknown"
                PassageLogger.error(TAG, "[$sourceWebViewType WebView] Unhandled Promise Rejection: $errorMessage")
            }
            "clientNavigation" -> {
                // Handle client-side navigation events (pushState, replaceState, hash changes)
                val url = message["url"] as? String
                val navigationMethod = message["navigationMethod"] as? String
                val sourceWebViewType = message["webViewType"] as? String ?: "unknown"

                if (url != null && navigationMethod != null) {
                    PassageLogger.info(TAG, "[$sourceWebViewType WebView] Client navigation ($navigationMethod): ${url.take(100)}")

                    // For automation webview, check success URL patterns on client navigation
                    if (sourceWebViewType == "automation") {
                        // You could add success URL checking here if needed
                    }
                }
            }
            "captureScreenshot" -> {
                // Handle window.passage.captureScreenshot calls
                val sourceWebViewType = message["webViewType"] as? String ?: "unknown"
                PassageLogger.info(TAG, "[$sourceWebViewType WebView] Manual screenshot capture requested")
                // Screenshot capture could be implemented here if needed
            }
            "message" -> {
                handleNestedPassageMessage(message)
            }
            "click" -> {
                // Handle click command results
                val commandId = message["commandId"] as? String ?: return
                val success = message["success"] as? Boolean ?: true
                val result = message["result"]

                PassageLogger.info(TAG, "Click command result: $commandId -> success=$success")

                if (success) {
                    launchInScope("clickCommandSuccess:$commandId") {
                        val pageData = collectPageData()
                        sendCommandResultHttp(commandId, "success", result, pageData, null)
                    }
                } else {
                    val error = message["error"] as? String ?: "Click command failed"
                    launchInScope("clickCommandError:$commandId") {
                        sendCommandResultHttp(commandId, "error", null, null, error)
                    }
                }
            }
            "input" -> {
                // Handle input command results
                val commandId = message["commandId"] as? String ?: return
                val success = message["success"] as? Boolean ?: true
                val result = message["result"]

                PassageLogger.info(TAG, "Input command result: $commandId -> success=$success")

                if (success) {
                    launchInScope("inputCommandSuccess:$commandId") {
                        val pageData = collectPageData()
                        sendCommandResultHttp(commandId, "success", result, pageData, null)
                    }
                } else {
                    val error = message["error"] as? String ?: "Input command failed"
                    launchInScope("inputCommandError:$commandId") {
                        sendCommandResultHttp(commandId, "error", null, null, error)
                    }
                }
            }
            else -> {
                PassageLogger.warn(TAG, "Unknown WebView message type: $type")
            }
        }
    }

    // Connection data

    fun getStoredConnectionData(): Pair<List<Map<String, Any>>?, String?> {
        return Pair(connectionData, connectionId)
    }

    private fun summarizeMessageForLogging(type: String, message: Map<String, Any>): String {
        return when (type) {
            "pageData" -> summarizePageDataMessage(message)
            "commandResult", "wait", "injectScript" -> summarizeCommandLikeMessage(type, message)
            else -> PassageLogger.truncateData(message.toString(), PassageConstants.Logging.MAX_DATA_LENGTH)
        }
    }

    private fun summarizePageDataMessage(message: Map<String, Any>): String {
        val webViewType = message["webViewType"] ?: "unknown"
        val errorPresent = message["error"] != null
        val dataMap = coerceToMap(message["data"], logErrors = false)

        val dataSummary = if (dataMap != null) {
            val url = (dataMap["url"] as? String)?.let { PassageLogger.truncateUrl(it) } ?: "nil"
            val htmlLen = (dataMap["html"] as? String)?.length ?: 0
            val cookieCount = countEntries(dataMap["cookies"])
            val localStorageCount = countEntries(dataMap["localStorage"])
            val sessionStorageCount = countEntries(dataMap["sessionStorage"])
            val screenshotLength = (dataMap["screenshot"] as? String)?.length ?: 0
            val screenshotInfo = if (screenshotLength > 0) "yes(${screenshotLength} chars)" else "nil"
            val errorCount = countEntries(dataMap["errors"])
            val errorInfo = if (errorCount > 0) " errors=$errorCount" else ""

            "url=$url htmlLen=$htmlLen cookies=$cookieCount localStorage=$localStorageCount sessionStorage=$sessionStorageCount screenshot=$screenshotInfo$errorInfo"
        } else {
            "data=${summarizeResultValue(message["data"])}"
        }

        return "pageData{webView=$webViewType $dataSummary errorPresent=$errorPresent}"
    }

    private fun summarizeCommandLikeMessage(type: String, message: Map<String, Any>): String {
        val commandId = message["commandId"] ?: "unknown"
        val success = message["success"]
        val valueSummary = summarizeResultValue(message["value"])
        val resultSummary = summarizeResultValue(message["result"])
        val errorSummary = message["error"]?.let { PassageLogger.truncateData(it.toString(), 120) } ?: "nil"
        return "$type{commandId=$commandId success=$success value=$valueSummary result=$resultSummary error=$errorSummary}"
    }

    private fun summarizeResultValue(node: Any?): String {
        return when (node) {
            null -> "nil"
            is String -> PassageLogger.truncateData(node, 120)
            is Number, is Boolean -> node.toString()
            is Map<*, *> -> "map(${node.size} keys)"
            is Collection<*> -> "collection(${node.size})"
            is Array<*> -> "array(${node.size})"
            is JSONObject -> coerceToMap(node, logErrors = false)?.let { "map(${it.size} keys)" } ?: "jsonObject"
            is JSONArray -> "array(${node.length()})"
            else -> PassageLogger.truncateData(node.toString(), 120)
        }
    }

    private fun countEntries(node: Any?): Int {
        return when (node) {
            is Collection<*> -> node.size
            is Map<*, *> -> node.size
            is Array<*> -> node.size
            is JSONArray -> node.length()
            else -> 0
        }
    }

    private fun handleNestedPassageMessage(message: Map<String, Any>) {
        val sourceWebViewType = message["webViewType"] as? String ?: "unknown"
        val dataMap = coerceToMap(message["data"], logErrors = false)
        val innerType = dataMap?.get("type") as? String

        if (innerType == null) {
            PassageLogger.warn(TAG, "[$sourceWebViewType WebView] Nested message without type: ${summarizeResultValue(message["data"])}")
            return
        }

        if (innerType == "ready") {
            PassageLogger.info(TAG, "[$sourceWebViewType WebView] Passage bridge reported ready")
            return
        }

        if (innerType == "CLOSE_CONFIRMED" || innerType == "CLOSE_CANCELLED") {
            PassageLogger.debug(TAG, "[$sourceWebViewType WebView] Close confirmation handled by activity; ignoring nested message: $innerType")
            return
        }

        PassageLogger.debug(TAG, "[$sourceWebViewType WebView] Routing nested message type: $innerType")

        val forwardedMessage = mutableMapOf<String, Any>()
        forwardedMessage.putAll(dataMap)
        forwardedMessage["type"] = innerType
        forwardedMessage["webViewType"] = sourceWebViewType

        // Preserve command identifiers and timestamp if present on the outer message but missing inside
        if (!forwardedMessage.containsKey("commandId") && message.containsKey("commandId")) {
            forwardedMessage["commandId"] = message["commandId"] as Any
        }
        if (!forwardedMessage.containsKey("timestamp") && message.containsKey("timestamp")) {
            forwardedMessage["timestamp"] = message["timestamp"] as Any
        }

        handleWebViewMessage(forwardedMessage)
    }

    private fun coerceToMap(dataObj: Any?, logErrors: Boolean = true): Map<String, Any>? {
        return when (dataObj) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                dataObj as? Map<String, Any>
            }
            is JSONObject -> {
                try {
                    gson.fromJson<Map<String, Any>>(dataObj.toString(), mapType)
                } catch (e: Exception) {
                    if (logErrors) {
                        PassageLogger.error(TAG, "Failed to convert JSONObject to Map", e)
                    }
                    null
                }
            }
            is String -> {
                val trimmed = dataObj.trim()
                if (trimmed.isEmpty()) return null
                try {
                    gson.fromJson<Map<String, Any>>(trimmed, mapType)
                } catch (e: Exception) {
                    if (logErrors) {
                        PassageLogger.error(TAG, "Failed to parse JSON string into Map", e)
                    }
                    null
                }
            }
            else -> null
        }
    }

    // Recording methods

    suspend fun completeRecording(data: Map<String, Any>) {
        currentCommand?.let { command ->
            val pageData = collectPageData()
            sendCommandResult(command.id, "success", data, pageData, null)

            // Call success callback
            connectionData?.let { connData ->
                val successData = PassageSuccessData(
                    history = connData.map { PassageHistoryItem(it, emptyMap()) },
                    connectionId = connectionId ?: ""
                )
                onSuccess?.invoke(successData)
            }
        }
    }

    suspend fun captureRecordingData(data: Map<String, Any>) {
        currentCommand?.let { command ->
            val pageData = collectPageData()
            sendCommandResult(command.id, "success", data, pageData, null)
        }
    }

    // Modal exit

    suspend fun emitModalExit() = suspendCoroutine<Unit> { continuation ->
        if (isConnected && socket != null) {
            PassageLogger.debug(TAG, "Emitting modalExit event")

            val modalExitData = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("intentToken", intentToken ?: "")
            }

            socket?.emit("modalExit", modalExitData, {
                PassageLogger.debug(TAG, "modalExit acknowledged")
                continuation.resume(Unit)
            })

            // Timeout fallback
            launchInScope("modalExitTimeout") {
                delay(1000)
                if (continuation.context.isActive) {
                    continuation.resume(Unit)
                }
            }
        } else {
            continuation.resume(Unit)
        }
    }

    // Cleanup

    fun disconnect() {
        PassageLogger.info(TAG, "========== DISCONNECTING ==========")

        screenshotTimer?.cancel()
        screenshotTimer = null

        socket?.disconnect()
        socket = null

        isConnected = false
        scope.cancel()
        scopeJob.cancel()

        // Unregister broadcast receiver
        unregisterCommandResultReceiver()

        // Clear all state
        intentToken = null
        connectionData = null
        connectionId = null
        currentCommand = null
        lastUserActionCommand = null
        lastWaitCommand = null
        executingWaitCommand = null
        currentSuccessUrls = emptyList()

        PassageLogger.info(TAG, "Cleanup completed")
    }

    private fun sendBroadcast(action: String, extras: Map<String, String> = emptyMap()) {
        val intent = Intent(action).apply {
            extras.forEach { (key, value) ->
                putExtra(key, value)
            }
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        PassageLogger.debug(TAG, "Broadcast sent: $action with extras: $extras")
    }
}
