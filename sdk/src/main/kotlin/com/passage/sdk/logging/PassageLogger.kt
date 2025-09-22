package com.passage.sdk.logging

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.passage.sdk.LogBatch
import com.passage.sdk.LogEntry
import com.passage.sdk.PassageConstants
import com.passage.sdk.PassageLogLevel
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Logging module for Passage SDK
 * Provides multi-level logging with console output and HTTP transport
 * Matching Swift SDK's PassageLogger implementation
 */
object PassageLogger {

    private const val TAG = "PassageSDK"
    private const val BATCH_SIZE = 20
    private const val FLUSH_INTERVAL = 10000L // 10 seconds
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY = 1000L // 1 second

    // Configuration
    private var currentLogLevel = PassageLogLevel.INFO
    private var isDebugMode = false
    private var httpTransportEnabled = false
    private var endpoint = PassageConstants.Defaults.LOGGER_ENDPOINT

    // Session info
    private var sessionId: String? = null
    private var sdkVersion: String? = null
    private var intentToken: String? = null

    // Log queue for HTTP transport
    private val logQueue = mutableListOf<LogEntry>()
    private val queueLock = Object()

    // Coroutine management
    private var flushJob: Job? = null
    private val loggerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessing = false

    // HTTP client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Configure the logger
     */
    fun configure(
        debug: Boolean = false,
        level: PassageLogLevel? = null,
        httpTransport: Boolean = false,
        customEndpoint: String? = null,
        sdkVersion: String? = null
    ) {
        isDebugMode = debug
        currentLogLevel = when {
            debug -> PassageLogLevel.DEBUG
            level != null -> level
            else -> PassageLogLevel.INFO
        }
        httpTransportEnabled = httpTransport
        customEndpoint?.let { endpoint = it }
        this.sdkVersion = sdkVersion

        if (httpTransportEnabled) {
            startHttpTransport()
        } else {
            stopHttpTransport()
        }

        info("Logger configured - level: $currentLogLevel, debug: $isDebugMode, httpTransport: $httpTransportEnabled")
    }

    /**
     * Update intent token for session tracking
     */
    fun updateIntentToken(token: String?) {
        intentToken = token
        sessionId = extractSessionIdFromToken(token)
        debug("Session ID updated: ${sessionId ?: "nil"}")
    }

    // Logging methods

    fun debug(message: String) {
        debug(TAG, message)
    }

    fun debug(tag: String, message: String, exception: Throwable? = null) {
        if (currentLogLevel.priority <= PassageLogLevel.DEBUG.priority) {
            Log.d(tag, message, exception)
            queueLog(PassageLogLevel.DEBUG, message, tag, exception)
        }
    }

    fun info(message: String) {
        info(TAG, message)
    }

    fun info(tag: String, message: String, exception: Throwable? = null) {
        if (currentLogLevel.priority <= PassageLogLevel.INFO.priority) {
            Log.i(tag, message, exception)
            queueLog(PassageLogLevel.INFO, message, tag, exception)
        }
    }

    fun warn(message: String) {
        warn(TAG, message)
    }

    fun warn(tag: String, message: String, exception: Throwable? = null) {
        if (currentLogLevel.priority <= PassageLogLevel.WARN.priority) {
            Log.w(tag, message, exception)
            queueLog(PassageLogLevel.WARN, message, tag, exception)
        }
    }

    fun error(message: String) {
        error(TAG, message)
    }

    fun error(tag: String, message: String, exception: Throwable? = null) {
        if (currentLogLevel.priority <= PassageLogLevel.ERROR.priority) {
            Log.e(tag, message, exception)
            queueLog(PassageLogLevel.ERROR, message, tag, exception)
        }
    }

    // Structured logging

    fun debugMethod(methodName: String, params: Map<String, Any>? = null) {
        val message = if (params != null) {
            "[$methodName] Called with params: $params"
        } else {
            "[$methodName] Called"
        }
        debug(message)
    }

    // Data truncation utilities

    fun truncateData(data: String, maxLength: Int = PassageConstants.Logging.MAX_DATA_LENGTH): String {
        return if (data.length > maxLength) {
            "${data.take(maxLength)}... (${data.length} total chars)"
        } else {
            data
        }
    }

    fun truncateUrl(url: String, maxLength: Int = PassageConstants.Logging.MAX_URL_LENGTH): String {
        return if (url.length > maxLength) {
            val start = url.take(maxLength / 2)
            val end = url.takeLast(maxLength / 4)
            "$start...$end"
        } else {
            url
        }
    }

    fun truncateCookie(cookie: String, maxLength: Int = PassageConstants.Logging.MAX_COOKIE_LENGTH): String {
        return if (cookie.length > maxLength) {
            "${cookie.take(maxLength)}... (${cookie.length} chars)"
        } else {
            cookie
        }
    }

    fun truncateHtml(html: String, maxLength: Int = PassageConstants.Logging.MAX_HTML_LENGTH): String {
        return if (html.length > maxLength) {
            "${html.take(maxLength)}... (${html.length} chars)"
        } else {
            html
        }
    }

    // Private methods

    private fun queueLog(level: PassageLogLevel, message: String, context: String?, exception: Throwable?) {
        if (!httpTransportEnabled) return

        val logEntry = LogEntry(
            level = level.name,
            message = message,
            context = context,
            metadata = exception?.let {
                mapOf(
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""),
                    "stackTrace" to it.stackTraceToString().take(1000)
                )
            },
            timestamp = dateFormatter.format(Date()),
            sessionId = sessionId,
            source = "sdk",
            sdkName = "kotlin-android",
            sdkVersion = sdkVersion,
            appVersion = getAppVersion(),
            platform = "android",
            deviceInfo = getDeviceInfo()
        )

        synchronized(queueLock) {
            logQueue.add(logEntry)

            // Flush if batch size reached
            if (logQueue.size >= BATCH_SIZE) {
                loggerScope.launch {
                    flushLogs()
                }
            }
        }
    }

    private fun startHttpTransport() {
        stopHttpTransport()

        flushJob = loggerScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL)
                flushLogs()
            }
        }
    }

    private fun stopHttpTransport() {
        flushJob?.cancel()
        flushJob = null
    }

    private suspend fun flushLogs() {
        if (!httpTransportEnabled || isProcessing) return

        val logsToSend: List<LogEntry>
        synchronized(queueLock) {
            if (logQueue.isEmpty()) return
            logsToSend = ArrayList(logQueue)
            logQueue.clear()
        }

        isProcessing = true

        sendLogs(logsToSend, 0) { success ->
            isProcessing = false

            if (!success) {
                // Re-queue failed logs (with limit)
                synchronized(queueLock) {
                    if (logQueue.size < 100) {
                        logQueue.addAll(0, logsToSend)
                    }
                }
            }
        }
    }

    private suspend fun sendLogs(
        logs: List<LogEntry>,
        retryCount: Int,
        completion: (Boolean) -> Unit
    ) {
        try {
            val batch = LogBatch(logs)
            val jsonBody = gson.toJson(batch)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .apply {
                    intentToken?.let { addHeader("x-intent-token", it) }
                }
                .build()

            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    completion(true)
                } else {
                    if (retryCount < MAX_RETRIES) {
                        delay(RETRY_DELAY * (retryCount + 1))
                        sendLogs(logs, retryCount + 1, completion)
                    } else {
                        completion(false)
                    }
                }
            }

        } catch (e: Exception) {
            if (retryCount < MAX_RETRIES) {
                delay(RETRY_DELAY * (retryCount + 1))
                sendLogs(logs, retryCount + 1, completion)
            } else {
                completion(false)
            }
        }
    }

    private fun extractSessionIdFromToken(token: String?): String? {
        if (token == null) return null

        try {
            val parts = token.split(".")
            if (parts.size != 3) return null

            val payload = parts[1]
            val decodedBytes = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val json = String(decodedBytes)
            val jsonObject = org.json.JSONObject(json)
            return jsonObject.optString("sessionId", null)

        } catch (e: Exception) {
            return null
        }
    }

    private fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "systemName" to "Android",
            "systemVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT.toString()
        )
    }

    private fun getAppVersion(): String? {
        // In a real app, this would get the actual app version
        // For now, returning null
        return null
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopHttpTransport()
        loggerScope.cancel()
    }
}