package com.passage.sdk

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Data models for Passage SDK
 * Matching Swift SDK data structures
 */

// Configuration
data class PassageConfig(
    val baseUrl: String = PassageConstants.Defaults.BASE_URL,
    val socketUrl: String = PassageConstants.Defaults.SOCKET_URL,
    val socketNamespace: String = PassageConstants.Defaults.SOCKET_NAMESPACE,
    val debug: Boolean = false,
    val agentName: String = PassageConstants.Defaults.AGENT_NAME
) : Serializable

// Success/Error data
data class PassageSuccessData(
    val history: List<Any?>,
    val connectionId: String
)

data class PassageErrorData(
    val error: String,
    val data: Any?
)

data class PassageDataResult(
    val data: Any?,
    val prompts: List<Map<String, Any>>?
)

data class PassagePromptResponse(
    val key: String,
    val value: String,
    val response: Any?
)

data class PassagePrompt(
    val identifier: String,
    val prompt: String,
    val integrationId: String,
    val forceRefresh: Boolean = false
)

// Opening options
data class PassageOpenOptions(
    val intentToken: String? = null,
    val prompts: List<PassagePrompt>? = null,
    val onConnectionComplete: ((PassageSuccessData) -> Unit)? = null,
    val onConnectionError: ((PassageErrorData) -> Unit)? = null,
    val onDataComplete: ((PassageDataResult) -> Unit)? = null,
    val onPromptComplete: ((PassagePromptResponse) -> Unit)? = null,
    val onExit: ((String?) -> Unit)? = null,
    val onWebviewChange: ((String) -> Unit)? = null,
    val presentationStyle: PassagePresentationStyle = PassagePresentationStyle.MODAL
)

// Initialize options
data class PassageInitializeOptions(
    val publishableKey: String,
    val prompts: List<PassagePrompt>? = null,
    val onConnectionComplete: ((PassageSuccessData) -> Unit)? = null,
    val onError: ((PassageErrorData) -> Unit)? = null,
    val onDataComplete: ((PassageDataResult) -> Unit)? = null,
    val onPromptComplete: ((PassagePromptResponse) -> Unit)? = null,
    val onExit: ((String?) -> Unit)? = null
)

enum class PassagePresentationStyle {
    MODAL,
    FULL_SCREEN;

    fun toAndroidStyle(): Int {
        return when (this) {
            MODAL -> android.R.style.Theme_Material_Dialog
            FULL_SCREEN -> android.R.style.Theme_Material_NoActionBar
        }
    }
}

// Remote control command types
sealed class RemoteCommand {
    abstract val id: String
    abstract val args: Map<String, Any>?
    abstract val injectScript: String?
    abstract val cookieDomains: List<String>?
    abstract val userActionRequired: Boolean?

    data class Navigate(
        override val id: String,
        override val args: Map<String, Any>?,
        val url: String,
        val successUrls: List<SuccessUrl>? = null,
        override val injectScript: String? = null,
        override val cookieDomains: List<String>? = null,
        override val userActionRequired: Boolean? = null
    ) : RemoteCommand()

    data class Click(
        override val id: String,
        override val args: Map<String, Any>?,
        override val injectScript: String?,
        override val cookieDomains: List<String>? = null,
        override val userActionRequired: Boolean? = null
    ) : RemoteCommand()

    data class Input(
        override val id: String,
        override val args: Map<String, Any>?,
        override val injectScript: String?,
        override val cookieDomains: List<String>? = null,
        override val userActionRequired: Boolean? = null
    ) : RemoteCommand()

    data class Wait(
        override val id: String,
        override val args: Map<String, Any>?,
        override val injectScript: String?,
        override val cookieDomains: List<String>? = null,
        override val userActionRequired: Boolean? = null
    ) : RemoteCommand()

    data class InjectScript(
        override val id: String,
        override val args: Map<String, Any>?,
        override val injectScript: String?,
        override val cookieDomains: List<String>? = null,
        override val userActionRequired: Boolean? = null
    ) : RemoteCommand()

    data class Done(
        override val id: String,
        override val args: Map<String, Any>?,
        val success: Boolean,
        val data: Any?,
        override val injectScript: String? = null,
        override val cookieDomains: List<String>? = null,
        override val userActionRequired: Boolean? = null
    ) : RemoteCommand()
}

// Command result
data class CommandResult(
    val id: String,
    val status: String,
    val data: Any?,
    val pageData: PageData?,
    val error: String?
)

// Page data collected from WebView
data class PageData(
    val cookies: List<CookieData>?,
    val localStorage: List<StorageItem>?,
    val sessionStorage: List<StorageItem>?,
    val html: String?,
    val url: String?,
    val screenshot: String?
)

data class CookieData(
    val name: String,
    val value: String,
    val domain: String,
    val path: String?,
    val expires: Double?,
    val secure: Boolean?,
    val httpOnly: Boolean?,
    val sameSite: String?
)

data class StorageItem(
    val name: String,
    val value: String
)

// Success URL configuration
data class SuccessUrl(
    val urlPattern: String,
    @SerializedName("navigationType")
    val navigationType: NavigationType
) {
    enum class NavigationType(val value: String) {
        NAVIGATION_START("navigationStart"),
        NAVIGATION_END("navigationEnd")
    }
}

// Configuration response from backend
data class ConfigurationResponse(
    val integration: IntegrationConfig?,
    val cookieDomains: List<String>?,
    val globalJavascript: String?,
    val automationUserAgent: String?,
    val imageOptimization: ImageOptimizationConfig?
)

data class IntegrationConfig(
    val url: String?,
    val name: String?,
    val slug: String?
)

data class ImageOptimizationConfig(
    val quality: Float?,
    val maxWidth: Int?,
    val maxHeight: Int?
)

// Analytics event types
enum class PassageAnalyticsEvent(val eventName: String) {
    // SDK Lifecycle Events
    SDK_MODAL_OPENED("SDK_MODAL_OPENED"),
    SDK_MODAL_CLOSED("SDK_MODAL_CLOSED"),
    SDK_CONFIGURE_START("SDK_CONFIGURE_START"),
    SDK_CONFIGURE_SUCCESS("SDK_CONFIGURE_SUCCESS"),
    SDK_CONFIGURE_ERROR("SDK_CONFIGURE_ERROR"),
    SDK_CONFIGURATION_REQUEST("SDK_CONFIGURATION_REQUEST"),
    SDK_CONFIGURATION_SUCCESS("SDK_CONFIGURATION_SUCCESS"),
    SDK_CONFIGURATION_ERROR("SDK_CONFIGURATION_ERROR"),
    SDK_OPEN_REQUEST("SDK_OPEN_REQUEST"),
    SDK_OPEN_SUCCESS("SDK_OPEN_SUCCESS"),
    SDK_OPEN_ERROR("SDK_OPEN_ERROR"),
    SDK_ON_SUCCESS("SDK_ON_SUCCESS"),
    SDK_ON_ERROR("SDK_ON_ERROR"),

    // Remote Control Events
    SDK_REMOTE_CONTROL_CONNECT_START("SDK_REMOTE_CONTROL_CONNECT_START"),
    SDK_REMOTE_CONTROL_CONNECT_SUCCESS("SDK_REMOTE_CONTROL_CONNECT_SUCCESS"),
    SDK_REMOTE_CONTROL_CONNECT_ERROR("SDK_REMOTE_CONTROL_CONNECT_ERROR"),
    SDK_REMOTE_CONTROL_DISCONNECT("SDK_REMOTE_CONTROL_DISCONNECT"),
    SDK_WEBVIEW_SWITCH("SDK_WEBVIEW_SWITCH"),

    // Navigation Events
    SDK_NAVIGATION_START("SDK_NAVIGATION_START"),
    SDK_NAVIGATION_SUCCESS("SDK_NAVIGATION_SUCCESS"),
    SDK_NAVIGATION_ERROR("SDK_NAVIGATION_ERROR"),

    // Command Events
    SDK_COMMAND_RECEIVED("SDK_COMMAND_RECEIVED"),
    SDK_COMMAND_SUCCESS("SDK_COMMAND_SUCCESS"),
    SDK_COMMAND_ERROR("SDK_COMMAND_ERROR")
}

// Analytics payload
data class PassageAnalyticsPayload(
    val event: String,
    val source: String = "sdk",
    val sdkName: String = "kotlin-android",
    val sdkVersion: String? = PassageConstants.Defaults.SDK_VERSION,
    val sessionId: String?,
    val timestamp: String,
    val metadata: Map<String, Any>?,
    val platform: String = "android",
    val deviceInfo: Map<String, String>?
)

// Log levels
enum class PassageLogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    SILENT(4)
}

// Log entry for HTTP transport
data class LogEntry(
    val level: String,
    val message: String,
    val context: String?,
    val metadata: Map<String, String>?,
    val timestamp: String,
    val sessionId: String?,
    val source: String = "sdk",
    val sdkName: String = "kotlin-android",
    val sdkVersion: String?,
    val appVersion: String?,
    val platform: String = "android",
    val deviceInfo: Map<String, String>?
)

data class LogBatch(
    val logs: List<LogEntry>
)

// Socket event data structures
data class JoinEventData(
    val intentToken: String,
    val agentName: String
)

data class ConnectionEventData(
    val data: List<Map<String, Any>>?,
    val connectionId: String?,
    val userActionRequired: Boolean
)

data class AppStateUpdateData(
    val state: String,
    val timestamp: String,
    val intentToken: String
)

data class ModalExitData(
    val timestamp: String,
    val intentToken: String
)

// Browser state for screenshot capture
data class BrowserStateData(
    val url: String,
    val screenshot: String? = null,
    val html: String? = null,
    val cookies: List<CookieData>? = null,
    val localStorage: List<StorageItem>? = null,
    val sessionStorage: List<StorageItem>? = null
)

// Error types
sealed class PassageError : Exception() {
    object NoRemoteControl : PassageError() {
        override val message = "Remote control is not available. Make sure Passage is properly configured."
    }

    object InvalidConfiguration : PassageError() {
        override val message = "Invalid Passage configuration."
    }

    data class RecordingFailed(val reason: String) : PassageError() {
        override val message = "Recording failed: $reason"
    }

    object NoActivityContext : PassageError() {
        override val message = "No activity context available for presentation."
    }

    object AlreadyPresenting : PassageError() {
        override val message = "Modal is already being presented."
    }
}