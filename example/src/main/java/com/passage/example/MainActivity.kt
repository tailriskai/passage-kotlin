package com.passage.example

import android.os.Bundle
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.passage.example.databinding.ActivityMainBinding
import com.passage.sdk.PassageConfig
import com.passage.sdk.PassageConstants
import com.passage.sdk.PassageDataResult
import com.passage.sdk.PassageErrorData
import com.passage.sdk.PassageInitializeOptions
import com.passage.sdk.PassageOpenOptions
import com.passage.sdk.PassagePromptResponse
import com.passage.sdk.PassageSDK
import com.passage.sdk.PassageSuccessData
import com.passage.sdk.logging.PassageLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Example activity replicating the iOS sample UI for the Passage SDK.
 * Uses local SDK sources via the :sdk Gradle module.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val resultLog = StringBuilder()

    private val defaultIntegrations = listOf(
        IntegrationOption("passage-test-captcha", "Passage Test Integration (with CAPTCHA)"),
        IntegrationOption("passage-test", "Passage Test Integration"),
        IntegrationOption("amazon", "Amazon"),
        IntegrationOption("uber", "Uber"),
        IntegrationOption("kroger", "Kroger"),
        IntegrationOption("kindle", "Kindle"),
        IntegrationOption("audible", "Audible"),
        IntegrationOption("youtube", "YouTube"),
        IntegrationOption("netflix", "Netflix"),
        IntegrationOption("doordash", "DoorDash"),
        IntegrationOption("ubereats", "UberEats"),
        IntegrationOption("chess", "Chess.com"),
        IntegrationOption("spotify", "Spotify"),
        IntegrationOption("verizon", "Verizon"),
        IntegrationOption("chewy", "Chewy"),
        IntegrationOption("att", "AT&T")
    )

    private var integrationOptions: List<IntegrationOption> = defaultIntegrations
    private var selectedIntegration: String = "kroger"
    private var exitCallCount: Int = 0
    private var isInitialized: Boolean = false
    private var isManualMode: Boolean = false
    private var isLoadingIntegrations: Boolean = false

    private val apiBaseUrl = "https://api.runpassage.ai"
    private val uiBaseUrl = "https://ui.runpassage.ai"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureSdk()
        setupUi()
        fetchIntegrations()
    }

    private fun configureSdk() {
        log("========== ANDROID EXAMPLE STARTING ==========")
        log("Activity created at ${Date()}")

        val config = PassageConfig(
            baseUrl = uiBaseUrl,
            socketUrl = apiBaseUrl,
            socketNamespace = PassageConstants.Defaults.SOCKET_NAMESPACE,
            debug = true
        )

        log("Configuring SDK with baseUrl=$uiBaseUrl, socketUrl=$apiBaseUrl")
        PassageSDK.configure(config)
    }

    private fun setupUi() {
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                binding.autoModeButton.id -> toggleMode(isManual = false)
                binding.manualModeButton.id -> toggleMode(isManual = true)
            }
        }
        binding.modeToggleGroup.check(binding.autoModeButton.id)

        binding.integrationButton.setOnClickListener { showIntegrationPicker() }
        binding.initializeButton.setOnClickListener { handleInitializeTapped() }
        binding.connectButton.setOnClickListener { handleConnectTapped() }

        binding.tokenEditText.doAfterTextChanged { updateManualButtonState() }

        updateIntegrationButton()
        updateManualButtonState()
    }

    private fun toggleMode(isManual: Boolean) {
        isManualMode = isManual
        log("Mode changed to ${if (isManual) "Manual Token" else "Auto-Fetch Token"}")

        binding.tokenLabel.isVisible = isManual
        binding.tokenInputLayout.isVisible = isManual
        binding.initializeButton.isVisible = isManual

        binding.integrationLabel.isVisible = !isManual
        binding.integrationButton.isVisible = !isManual

        if (isManual) {
            isInitialized = false
            updateManualButtonState()
        } else {
            binding.connectButton.isVisible = true
            binding.connectButton.isEnabled = true
            binding.connectButton.text = getString(R.string.connect)
        }
    }

    private fun updateManualButtonState() {
        if (!isManualMode) {
            binding.connectButton.isVisible = true
            return
        }

        val hasToken = !binding.tokenEditText.text.isNullOrBlank()

        if (!isInitialized) {
            binding.initializeButton.isVisible = true
            binding.initializeButton.isEnabled = hasToken
            binding.initializeButton.text = if (hasToken) {
                getString(R.string.initialize)
            } else {
                getString(R.string.enter_token)
            }
            binding.connectButton.isVisible = false
        } else {
            binding.initializeButton.isVisible = false
            binding.connectButton.isVisible = true
            binding.connectButton.isEnabled = true
            binding.connectButton.text = getString(R.string.open_passage)
        }
    }

    private fun updateIntegrationButton() {
        binding.integrationButton.isEnabled = !isLoadingIntegrations
        binding.integrationButton.text = if (isLoadingIntegrations) {
            getString(R.string.loading_integrations)
        } else {
            integrationOptions.firstOrNull { it.value == selectedIntegration }?.label
                ?: getString(R.string.select_integration)
        }
    }

    private fun showIntegrationPicker() {
        val labels = integrationOptions.map { it.label }.toTypedArray()
        val currentIndex = integrationOptions.indexOfFirst { it.value == selectedIntegration }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_integration)
            .setSingleChoiceItems(labels, currentIndex) { dialog, index ->
                val option = integrationOptions[index]
                selectedIntegration = option.value
                updateIntegrationButton()
                log("Selected integration: ${option.label}")
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleInitializeTapped() {
        val token = binding.tokenEditText.text?.toString()?.trim().orEmpty()
        if (token.isEmpty()) {
            showSnack("Please enter an intent token")
            return
        }

        binding.initializeButton.isEnabled = false
        binding.initializeButton.text = getString(R.string.initializing)
        logResult("Initializing SDK with provided token…")

        logTokenDetails(token)
        initializeSdk(token)
    }

    private fun handleConnectTapped() {
        log("========== CONNECT BUTTON TAPPED ==========")
        log("Current time: ${Date()}")

        if (isManualMode) {
            if (!isInitialized) {
                logResult("❌ Please initialize the SDK first")
                return
            }
            val token = binding.tokenEditText.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                logResult("❌ Please enter an intent token")
                return
            }

            binding.connectButton.isEnabled = false
            binding.connectButton.text = getString(R.string.opening_passage)
            logResult("Opening Passage with initialized SDK…")
            openPassage(token)
        } else {
            binding.connectButton.isEnabled = false
            binding.connectButton.text = getString(R.string.fetching_token)
            logResult("Fetching intent token…")
            fetchIntentToken()
        }
    }

    private fun initializeSdk(token: String) {
        val publishableKey = extractPublishableKey(token)
            ?: "pk-live-0d017c4c-307e-441c-8b72-cb60f64f77f8"

        val options = PassageInitializeOptions(
            publishableKey = publishableKey,
            prompts = null,
            onConnectionComplete = { data ->
                runOnUiThread {
                    handleSuccess(data, "INITIALIZE")
                }
            },
            onError = { error ->
                runOnUiThread {
                    handleError(error, "INITIALIZE")
                }
            },
            onDataComplete = { result ->
                runOnUiThread {
                    handleDataComplete(result, "INITIALIZE")
                }
            },
            onPromptComplete = { prompt ->
                runOnUiThread {
                    handlePromptComplete(prompt, "INITIALIZE")
                }
            },
            onExit = { reason ->
                runOnUiThread {
                    exitCallCount += 1
                    log("🚪 INITIALIZE - EXIT CALLBACK #$exitCallCount reason=${reason ?: "unknown"}")
                    handleClose()
                }
            }
        )

        lifecycleScope.launch {
            try {
                PassageSDK.initialize(options)
                isInitialized = true
                binding.initializeButton.isEnabled = true
                binding.initializeButton.text = getString(R.string.initialize)
                updateManualButtonState()
                logResult("✅ SDK initialized successfully!\n\nPublishable Key: $publishableKey")
            } catch (ex: Exception) {
                isInitialized = false
                binding.initializeButton.isEnabled = true
                binding.initializeButton.text = getString(R.string.initialize)
                updateManualButtonState()
                logResult("❌ SDK initialization failed: ${ex.message}")
            }
        }
    }

    private fun openPassage(token: String) {
        PassageLogger.updateIntentToken(token)

        val options = PassageOpenOptions(
            intentToken = token,
            onConnectionComplete = { data ->
                runOnUiThread {
                    handleSuccess(data, "OPEN")
                    resetConnectButton()
                }
            },
            onConnectionError = { error ->
                runOnUiThread {
                    handleError(error, "OPEN")
                    resetConnectButton()
                }
            },
            onDataComplete = { result ->
                runOnUiThread { handleDataComplete(result, "OPEN") }
            },
            onPromptComplete = { prompt ->
                runOnUiThread { handlePromptComplete(prompt, "OPEN") }
            },
            onExit = { reason ->
                runOnUiThread {
                    exitCallCount += 1
                    log("🚪 OPEN - EXIT CALLBACK #$exitCallCount reason=${reason ?: "unknown"}")
                    handleClose()
                    resetConnectButton()
                }
            },
            onWebviewChange = { type ->
                runOnUiThread {
                    log("🌐 WebView changed to $type")
                }
            }
        )

        PassageSDK.open(this, options)
    }

    private fun fetchIntentToken() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { requestIntentToken() }
            when (result) {
                is ResultWrapper.Success -> {
                    val token = result.value
                    log("Successfully fetched intent token (length=${token.length})")
                    logTokenDetails(token)
                    binding.connectButton.text = getString(R.string.opening_passage)
                    openPassage(token)
                }
                is ResultWrapper.Error -> {
                    binding.connectButton.isEnabled = true
                    binding.connectButton.text = getString(R.string.connect)
                    logResult("❌ Failed to fetch intent token:\n\n${result.throwable.message}")
                }
            }
        }
    }

    private suspend fun requestIntentToken(): ResultWrapper<String> {
        val payload = JSONObject(mapOf(
            // API validates the body strictly (forbidNonWhitelisted=true) so only send allowed keys
            "integrationId" to selectedIntegration
        ))
        val request = Request.Builder()
            .url("$apiBaseUrl/intent-token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("accept", "*/*")
            .addHeader("authorization", "Publishable pk-live-0d017c4c-307e-441c-8b72-cb60f64f77f8")
            .addHeader("content-type", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return ResultWrapper.Error(IOException("Empty response"))
                log("Intent token response (${response.code}): $body")
                val token = parseTokenFromResponse(body)
                if (token != null) ResultWrapper.Success(token) else ResultWrapper.Error(IOException("No token field in response"))
            }
        } catch (ex: Exception) {
            ResultWrapper.Error(ex)
        }
    }

    private fun parseTokenFromResponse(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            when {
                json.has("intentToken") -> return json.getString("intentToken")
                json.has("token") -> return json.getString("token")
                json.optJSONObject("data")?.optString("token")?.isNotEmpty() == true ->
                    return json.getJSONObject("data").getString("token")
            }
        }
        return null
    }

    private fun fetchIntegrations() {
        if (isLoadingIntegrations) {
            log("Integrations already loading, skipping…")
            return
        }

        isLoadingIntegrations = true
        updateIntegrationButton()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { requestIntegrations() }
            isLoadingIntegrations = false
            when (result) {
                is ResultWrapper.Success -> {
                    integrationOptions = result.value
                    if (integrationOptions.none { it.value == selectedIntegration }) {
                        selectedIntegration = integrationOptions.firstOrNull()?.value ?: selectedIntegration
                    }
                    log("✅ Loaded ${integrationOptions.size} integrations from API")
                    updateIntegrationButton()
                    binding.integrationButton.isEnabled = true
                }
                is ResultWrapper.Error -> {
                    log("❌ Failed to load integrations: ${result.throwable.message}")
                    integrationOptions = defaultIntegrations
                    updateIntegrationButton()
                    binding.integrationButton.isEnabled = true
                    showSnack("Falling back to default integrations")
                }
            }
        }
    }

    private suspend fun requestIntegrations(): ResultWrapper<List<IntegrationOption>> {
        val request = Request.Builder()
            .url("$apiBaseUrl/integrations")
            .get()
            .addHeader("content-type", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return ResultWrapper.Error(IOException("Empty response"))
                log("Integrations response (${response.code}): $body")
                val options = parseIntegrations(body)
                if (options.isNotEmpty()) ResultWrapper.Success(options) else ResultWrapper.Error(IOException("No integrations in response"))
            }
        } catch (ex: Exception) {
            ResultWrapper.Error(ex)
        }
    }

    private fun parseIntegrations(body: String): List<IntegrationOption> {
        val trimmed = body.trim()
        val list = mutableListOf<IntegrationOption>()

        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    parseIntegrationObject(array.optJSONObject(i))?.let { list.add(it) }
                }
            } else if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                when {
                    json.has("integrations") -> {
                        val array = json.getJSONArray("integrations")
                        for (i in 0 until array.length()) {
                            parseIntegrationObject(array.optJSONObject(i))?.let { list.add(it) }
                        }
                    }
                    json.has("data") -> {
                        val array = json.getJSONArray("data")
                        for (i in 0 until array.length()) {
                            parseIntegrationObject(array.optJSONObject(i))?.let { list.add(it) }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            log("Integration parsing error: ${ex.message}")
        }

        return list
    }

    private fun parseIntegrationObject(obj: JSONObject?): IntegrationOption? {
        obj ?: return null
        val value = obj.optString("slug").ifBlank { obj.optString("id") }
        if (value.isBlank()) return null
        val label = obj.optString("name").ifBlank { obj.optString("displayName") }
            .ifBlank { value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() } }
        return IntegrationOption(value, label)
    }

    private fun handleSuccess(data: PassageSuccessData, source: String) {
        val message = buildString {
            appendLine("✅ $source - CONNECTION COMPLETE")
            appendLine("History entries: ${data.history.size}")
            appendLine("Connection ID: ${data.connectionId}")
        }
        logResult(message)
    }

    private fun handleError(error: PassageErrorData, source: String) {
        val message = buildString {
            appendLine("❌ $source - ERROR")
            appendLine("Message: ${error.error}")
            appendLine("Data: ${error.data}")
        }
        logResult(message)
    }

    private fun handleDataComplete(result: PassageDataResult, source: String) {
        val message = buildString {
            appendLine("📊 $source - DATA COMPLETE")
            appendLine("Data: ${result.data}")
            appendLine("Prompts: ${result.prompts?.size ?: 0}")
        }
        logResult(message)
    }

    private fun handlePromptComplete(prompt: PassagePromptResponse, source: String) {
        val message = buildString {
            appendLine("🎯 $source - PROMPT COMPLETE")
            appendLine("Key: ${prompt.key}")
            appendLine("Value: ${prompt.value}")
        }
        logResult(message)
    }

    private fun handleClose() {
        logResult("🚪 Passage closed")
    }

    private fun resetConnectButton() {
        binding.connectButton.isEnabled = true
        binding.connectButton.text = if (isManualMode) {
            if (isInitialized) getString(R.string.open_passage) else getString(R.string.connect)
        } else {
            getString(R.string.connect)
        }
    }

    private fun extractPublishableKey(token: String): String? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            val payload = padBase64(parts[1])
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            val json = JSONObject(String(decoded, StandardCharsets.UTF_8))
            json.optString("clientId").takeIf { it.isNotBlank() }
        } catch (ex: Exception) {
            log("Failed to decode publishable key: ${ex.message}")
            null
        }
    }

    private fun logTokenDetails(token: String) {
        log("Intent token length: ${token.length}")
        val parts = token.split('.')
        if (parts.size != 3) return
        try {
            val payload = padBase64(parts[1])
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            val json = JSONObject(String(decoded, StandardCharsets.UTF_8))
            log("JWT payload: $json")
            json.optDouble("exp").takeIf { it > 0 }?.let { exp ->
                val expireDate = Date((exp * 1000).toLong())
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                log("Token expires at ${formatter.format(expireDate)} (expired=${expireDate.before(Date())})")
            }
        } catch (ex: Exception) {
            log("Failed to parse JWT: ${ex.message}")
        }
    }

    private fun padBase64(input: String): String {
        val remainder = input.length % 4
        return if (remainder == 0) input else input + "=".repeat(4 - remainder)
    }

    private fun log(message: String) {
        resultLog.appendLine(message)
        binding.resultText.text = resultLog.toString()
    }

    private fun logResult(message: String) {
        resultLog.appendLine()
        resultLog.appendLine(message)
        binding.resultText.text = resultLog.toString()
    }

    private fun showSnack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private data class IntegrationOption(val value: String, val label: String)

    private sealed class ResultWrapper<out T> {
        data class Success<T>(val value: T) : ResultWrapper<T>()
        data class Error(val throwable: Throwable) : ResultWrapper<Nothing>()
    }
}
