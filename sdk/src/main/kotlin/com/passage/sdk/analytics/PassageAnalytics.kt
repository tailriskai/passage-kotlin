package com.passage.sdk.analytics

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.passage.sdk.PassageAnalyticsEvent
import com.passage.sdk.PassageAnalyticsPayload
import com.passage.sdk.PassageConstants
import com.passage.sdk.PassageSDK
import com.passage.sdk.logging.PassageLogger
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

/**
 * Analytics module for Passage SDK
 * Tracks SDK events with proper payload structure and HTTP transport
 * Implements batching and retry logic matching Swift SDK
 */
object PassageAnalytics {

    private const val TAG = "PassageAnalytics"

    // Configuration
    private var endpoint = PassageConstants.Defaults.ANALYTICS_ENDPOINT
    private var batchSize = PassageConstants.Analytics.BATCH_SIZE
    private var flushInterval = PassageConstants.Analytics.FLUSH_INTERVAL
    private var maxRetries = PassageConstants.Analytics.MAX_RETRIES
    private var retryDelay = PassageConstants.Analytics.RETRY_DELAY
    private var enabled = true

    // Event queue
    private val eventQueue = mutableListOf<PassageAnalyticsPayload>()
    private val queueLock = Object()

    // Session information
    private var sdkVersion: String? = null
    private var sessionId: String? = null
    private var intentToken: String? = null

    // Device info cache
    private val deviceInfo: Map<String, String> by lazy {
        PassageSDK.getDeviceInfo()
    }

    // Coroutine management
    private var flushJob: Job? = null
    private val analyticsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessing = false

    // HTTP client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Configure analytics with SDK version and optional custom settings
     */
    fun configure(
        sdkVersion: String? = null,
        customEndpoint: String? = null,
        customBatchSize: Int? = null,
        customFlushInterval: Long? = null
    ) {
        this.sdkVersion = sdkVersion
        customEndpoint?.let { endpoint = it }
        customBatchSize?.let { batchSize = it }
        customFlushInterval?.let { flushInterval = it }

        if (enabled) {
            startAnalytics()
            PassageLogger.debug(TAG, "Analytics configured - endpoint: $endpoint, enabled: true")
        } else {
            stopAnalytics()
            PassageLogger.debug(TAG, "Analytics disabled")
        }
    }

    /**
     * Update session information from intent token
     */
    fun updateSessionInfo(intentToken: String?, sessionId: String?) {
        this.intentToken = intentToken
        this.sessionId = sessionId ?: extractSessionIdFromToken(intentToken)
        PassageLogger.debug(TAG, "Session info updated - sessionId: ${this.sessionId ?: "nil"}")
    }

    /**
     * Track an analytics event
     */
    fun track(event: PassageAnalyticsEvent, metadata: Map<String, Any>? = null) {
        if (!enabled) return

        PassageLogger.debug(TAG, "Tracking event: ${event.eventName}")

        val payload = PassageAnalyticsPayload(
            event = event.eventName,
            source = "sdk",
            sdkName = "kotlin-android",
            sdkVersion = sdkVersion,
            sessionId = sessionId,
            timestamp = dateFormatter.format(Date()),
            metadata = metadata,
            platform = "android",
            deviceInfo = deviceInfo
        )

        queueEvent(payload)
    }

    // Convenience tracking methods

    fun trackModalOpened(presentationStyle: String, url: String?) {
        track(PassageAnalyticsEvent.SDK_MODAL_OPENED, mapOf(
            "presentationStyle" to presentationStyle,
            "url" to (url ?: "")
        ))
    }

    fun trackModalClosed(reason: String) {
        track(PassageAnalyticsEvent.SDK_MODAL_CLOSED, mapOf(
            "reason" to reason
        ))
    }

    fun trackConfigureStart() {
        track(PassageAnalyticsEvent.SDK_CONFIGURE_START)
    }

    fun trackConfigureSuccess(config: Map<String, Any>) {
        track(PassageAnalyticsEvent.SDK_CONFIGURE_SUCCESS, config)
    }

    fun trackConfigureError(error: String) {
        track(PassageAnalyticsEvent.SDK_CONFIGURE_ERROR, mapOf(
            "error" to error
        ))
    }

    fun trackConfigurationRequest(url: String) {
        track(PassageAnalyticsEvent.SDK_CONFIGURATION_REQUEST, mapOf(
            "url" to PassageLogger.truncateUrl(url, 200)
        ))
    }

    fun trackConfigurationSuccess(userAgent: String?, integrationUrl: String?) {
        track(PassageAnalyticsEvent.SDK_CONFIGURATION_SUCCESS, mapOf(
            "hasUserAgent" to (userAgent != null),
            "hasIntegrationUrl" to (integrationUrl != null)
        ))
    }

    fun trackConfigurationError(error: String, url: String) {
        track(PassageAnalyticsEvent.SDK_CONFIGURATION_ERROR, mapOf(
            "error" to error,
            "url" to PassageLogger.truncateUrl(url, 200)
        ))
    }

    fun trackRemoteControlConnectStart(socketUrl: String, namespace: String) {
        track(PassageAnalyticsEvent.SDK_REMOTE_CONTROL_CONNECT_START, mapOf(
            "socketUrl" to socketUrl,
            "namespace" to namespace
        ))
    }

    fun trackRemoteControlConnectSuccess(socketId: String? = null) {
        track(PassageAnalyticsEvent.SDK_REMOTE_CONTROL_CONNECT_SUCCESS, mapOf(
            "socketId" to (socketId ?: ""),
            "connected" to true
        ))
    }

    fun trackRemoteControlConnectError(error: String, attempt: Int = 1) {
        track(PassageAnalyticsEvent.SDK_REMOTE_CONTROL_CONNECT_ERROR, mapOf(
            "error" to error,
            "attempt" to attempt,
            "connected" to false
        ))
    }

    fun trackRemoteControlDisconnect(reason: String) {
        track(PassageAnalyticsEvent.SDK_REMOTE_CONTROL_DISCONNECT, mapOf(
            "reason" to reason
        ))
    }

    fun trackWebViewSwitch(from: String, to: String, reason: String) {
        track(PassageAnalyticsEvent.SDK_WEBVIEW_SWITCH, mapOf(
            "fromWebView" to from,
            "toWebView" to to,
            "reason" to reason
        ))
    }

    fun trackNavigationStart(url: String, webViewType: String) {
        track(PassageAnalyticsEvent.SDK_NAVIGATION_START, mapOf(
            "url" to PassageLogger.truncateUrl(url, 200),
            "webViewType" to webViewType
        ))
    }

    fun trackNavigationSuccess(url: String, webViewType: String, duration: Long? = null) {
        val metadata = mutableMapOf(
            "url" to PassageLogger.truncateUrl(url, 200),
            "webViewType" to webViewType
        )
        duration?.let { metadata["duration"] = it.toString() }
        track(PassageAnalyticsEvent.SDK_NAVIGATION_SUCCESS, metadata)
    }

    fun trackNavigationError(url: String, webViewType: String, error: String) {
        track(PassageAnalyticsEvent.SDK_NAVIGATION_ERROR, mapOf(
            "url" to PassageLogger.truncateUrl(url, 200),
            "webViewType" to webViewType,
            "error" to error
        ))
    }

    fun trackCommandReceived(commandId: String, commandType: String, userActionRequired: Boolean) {
        track(PassageAnalyticsEvent.SDK_COMMAND_RECEIVED, mapOf(
            "commandId" to commandId,
            "commandType" to commandType,
            "userActionRequired" to userActionRequired
        ))
    }

    fun trackCommandSuccess(commandId: String, commandType: String, duration: Long? = null) {
        val metadata = mutableMapOf(
            "commandId" to commandId,
            "commandType" to commandType
        )
        duration?.let { metadata["duration"] = it.toString() }
        track(PassageAnalyticsEvent.SDK_COMMAND_SUCCESS, metadata)
    }

    fun trackCommandError(commandId: String, commandType: String, error: String) {
        track(PassageAnalyticsEvent.SDK_COMMAND_ERROR, mapOf(
            "commandId" to commandId,
            "commandType" to commandType,
            "error" to error
        ))
    }

    fun trackOpenRequest(token: String) {
        track(PassageAnalyticsEvent.SDK_OPEN_REQUEST, mapOf(
            "tokenLength" to token.length,
            "hasToken" to token.isNotEmpty()
        ))
    }

    fun trackOpenSuccess(url: String) {
        track(PassageAnalyticsEvent.SDK_OPEN_SUCCESS, mapOf(
            "finalUrl" to PassageLogger.truncateUrl(url, 200)
        ))
    }

    fun trackOpenError(error: String, context: String? = null) {
        val metadata = mutableMapOf("error" to error)
        context?.let { metadata["context"] = it }
        track(PassageAnalyticsEvent.SDK_OPEN_ERROR, metadata)
    }

    fun trackOnSuccess(historyCount: Int, connectionId: String) {
        track(PassageAnalyticsEvent.SDK_ON_SUCCESS, mapOf(
            "historyCount" to historyCount,
            "connectionId" to connectionId,
            "hasData" to (historyCount > 0)
        ))
    }

    fun trackOnError(error: String, data: Any?) {
        track(PassageAnalyticsEvent.SDK_ON_ERROR, mapOf(
            "error" to error,
            "hasData" to (data != null)
        ))
    }

    // Private methods

    private fun startAnalytics() {
        stopAnalytics() // Stop existing timer if any

        flushJob = analyticsScope.launch {
            while (isActive) {
                delay(flushInterval)
                flushEvents()
            }
        }
    }

    private fun stopAnalytics() {
        flushJob?.cancel()
        flushJob = null
        // Don't synchronously flush on stop to avoid blocking
    }

    private fun queueEvent(payload: PassageAnalyticsPayload) {
        synchronized(queueLock) {
            eventQueue.add(payload)

            // Flush if batch size reached
            if (eventQueue.size >= batchSize) {
                analyticsScope.launch {
                    flushEvents()
                }
            }
        }
    }

    fun flushEvents() {
        analyticsScope.launch {
            if (!enabled || isProcessing) return@launch

            val eventsToSend: List<PassageAnalyticsPayload>
            synchronized(queueLock) {
                if (eventQueue.isEmpty()) return@launch
                eventsToSend = ArrayList(eventQueue)
                eventQueue.clear()
            }

            isProcessing = true

            sendEvents(eventsToSend, 0) { success ->
                isProcessing = false

                if (!success) {
                    // Re-queue failed events (with limit to prevent infinite growth)
                    synchronized(queueLock) {
                        if (eventQueue.size < 100) {
                            eventQueue.addAll(0, eventsToSend)
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendEvents(
        events: List<PassageAnalyticsPayload>,
        retryCount: Int,
        completion: (Boolean) -> Unit
    ) {
        try {
            val batch = mapOf("events" to events)
            val jsonBody = gson.toJson(batch)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .apply {
                    intentToken?.let { addHeader("x-intent-token", it) }
                }
                .build()

            PassageLogger.debug(TAG, "Sending ${events.size} events to analytics endpoint")

            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    PassageLogger.debug(TAG, "Events sent successfully - Status: ${response.code}")
                    completion(true)
                } else {
                    PassageLogger.error(TAG, "Server error - Status: ${response.code}")

                    // Retry logic
                    if (retryCount < maxRetries) {
                        delay(retryDelay * (retryCount + 1))
                        sendEvents(events, retryCount + 1, completion)
                    } else {
                        completion(false)
                    }
                }
            }

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Error sending events", e)

            // Retry logic
            if (retryCount < maxRetries) {
                delay(retryDelay * (retryCount + 1))
                sendEvents(events, retryCount + 1, completion)
            } else {
                completion(false)
            }
        }
    }

    private fun extractSessionIdFromToken(token: String?): String? {
        if (token == null) return null

        try {
            // Simple JWT parsing - extract middle part
            val parts = token.split(".")
            if (parts.size != 3) return null

            val payload = parts[1]
            val decodedBytes = android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            val json = String(decodedBytes)

            // Parse JSON to extract sessionId
            val jsonObject = org.json.JSONObject(json)
            return jsonObject.optString("sessionId", null)

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to extract session ID from token", e)
            return null
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopAnalytics()
        analyticsScope.cancel()
    }
}