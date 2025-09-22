package com.passage.sdk

/**
 * Constants for Passage SDK
 * These constants should be kept in sync with the Swift implementation
 */
object PassageConstants {

    // Message types for WebView communication
    object MessageTypes {
        const val CONNECTION_SUCCESS = "CONNECTION_SUCCESS"
        const val CONNECTION_ERROR = "CONNECTION_ERROR"
        const val MESSAGE = "message"
        const val NAVIGATE = "navigate"
        const val CLOSE = "close"
        const val SET_TITLE = "setTitle"
        const val BACK_PRESSED = "backPressed"
        const val PAGE_LOADED = "page_loaded"
        const val SCRIPT_INJECTION = "script_injection"
        const val INJECT_SCRIPT = "injectScript"
        const val WAIT = "wait"
        const val CLOSE_MODAL = "CLOSE_MODAL"
        const val OPEN_EXTERNAL_URL = "OPEN_EXTERNAL_URL"
        const val NAVIGATION_FINISHED = "navigation_finished"
        const val SWITCH_WEBVIEW = "SWITCH_WEBVIEW"
        const val PASSAGE_MESSAGE = "passage_message"
        const val CURRENT_URL = "currentUrl"
        const val PAGE_DATA = "pageData"
    }

    // Presentation styles
    object PresentationStyles {
        const val FULL_SCREEN = "fullScreen"
        const val PAGE_SHEET = "pageSheet"
        const val FORM_SHEET = "formSheet"
        const val AUTOMATIC = "automatic"
    }

    // Event names for communication
    object EventNames {
        const val MESSAGE_RECEIVED = "messageReceived"
        const val MODAL_CLOSED = "modalClosed"
        const val NAVIGATION_FINISHED = "navigationFinished"
        const val CONNECTION_SUCCESS = "connectionSuccess"
        const val CONNECTION_ERROR = "connectionError"
        const val BUTTON_CLICKED = "buttonClicked"
        const val WEBVIEW_SWITCHED = "webViewSwitched"
        const val EVENT_ERROR = "error"
    }

    // WebView message handler names
    object MessageHandlers {
        const val PASSAGE_WEBVIEW = "passageWebView"
    }

    // WebView identifiers
    object WebViewTypes {
        const val UI = "ui"
        const val AUTOMATION = "automation"
    }

    // Default values
    object Defaults {
        const val MODAL_TITLE = ""
        const val SHOW_GRABBER = false
        const val BASE_URL = "https://ui.runpassage.ai"
        const val SOCKET_URL = "https://api.runpassage.ai"
        const val SOCKET_NAMESPACE = "/ws"
        const val LOGGER_ENDPOINT = "https://ui.runpassage.ai/api/logger"
        const val ANALYTICS_ENDPOINT = "https://api.runpassage.ai/analytics"
        const val AGENT_NAME = "passage-kotlin"
        const val SDK_VERSION = "0.0.2"
    }

    // Error domains
    object ErrorDomains {
        const val PASSAGE = "PassageSDK"
    }

    // URL schemes for local content validation
    object URLSchemes {
        const val HTTP_LOCALHOST = "http://localhost"
        const val HTTP_LOCAL = "http://192.168"
    }

    // WebView configuration keys
    object WebViewConfigKeys {
        const val ALLOW_FILE_ACCESS_FROM_FILE_URLS = "allowFileAccessFromFileURLs"
        const val ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS = "allowUniversalAccessFromFileURLs"
    }

    // Logging configuration
    object Logging {
        const val MAX_DATA_LENGTH = 1000 // Maximum characters for logging data
        const val MAX_COOKIE_LENGTH = 200 // Maximum characters for cookie data
        const val MAX_HTML_LENGTH = 500 // Maximum characters for HTML data
        const val MAX_URL_LENGTH = 100 // Maximum characters for URL logging
    }

    // Path constants
    object Paths {
        const val CONNECT = "/connect"
        const val AUTOMATION_CONFIG = "/automation/configuration"
        const val AUTOMATION_COMMAND_RESULT = "/automation/command-result"
        const val BROWSER_STATE = "/automation/browser-state"
    }

    // Socket configuration
    object Socket {
        const val TIMEOUT = 10000
        val TRANSPORTS = arrayOf("websocket", "polling")
    }

    // Analytics configuration
    object Analytics {
        const val BATCH_SIZE = 10
        const val FLUSH_INTERVAL = 5000L // milliseconds
        const val MAX_RETRIES = 3
        const val RETRY_DELAY = 1000L // milliseconds
    }

    // Screenshot configuration
    object Screenshot {
        const val DEFAULT_INTERVAL = 5.0 // seconds
        const val JPEG_QUALITY = 80 // 0-100
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
    }

    // Intent extras for Activity communication
    object IntentExtras {
        const val INTENT_TOKEN = "intent_token"
        const val PRESENTATION_STYLE = "presentation_style"
        const val CONFIG = "config"
        const val SESSION_ID = "session_id"
    }

    // Broadcast actions for internal communication
    object BroadcastActions {
        const val NAVIGATE = "com.passage.sdk.NAVIGATE"
        const val NAVIGATE_IN_AUTOMATION = "com.passage.sdk.NAVIGATE_IN_AUTOMATION"
        const val INJECT_SCRIPT = "com.passage.sdk.INJECT_SCRIPT"
        const val SHOW_UI_WEBVIEW = "com.passage.sdk.SHOW_UI_WEBVIEW"
        const val SHOW_AUTOMATION_WEBVIEW = "com.passage.sdk.SHOW_AUTOMATION_WEBVIEW"
        const val NAVIGATION_COMPLETED = "com.passage.sdk.NAVIGATION_COMPLETED"
        const val SCRIPT_EXECUTION_RESULT = "com.passage.sdk.SCRIPT_EXECUTION_RESULT"
        const val SEND_BROWSER_STATE = "com.passage.sdk.SEND_BROWSER_STATE"
        const val GET_CURRENT_URL = "com.passage.sdk.GET_CURRENT_URL"
        const val COLLECT_PAGE_DATA = "com.passage.sdk.COLLECT_PAGE_DATA"
    }
}