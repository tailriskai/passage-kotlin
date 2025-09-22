package com.passage.sdk.utils

import android.util.Base64
import com.auth0.android.jwt.DecodeException
import com.auth0.android.jwt.JWT
import com.passage.sdk.logging.PassageLogger
import org.json.JSONObject

/**
 * JWT decoder utility for extracting claims from intent tokens
 * Used to parse feature flags and session information
 */
object JwtDecoder {

    private const val TAG = "JwtDecoder"

    /**
     * Decode a JWT token
     */
    fun decode(token: String): JWT {
        try {
            return JWT(token)
        } catch (e: DecodeException) {
            PassageLogger.error(TAG, "Failed to decode JWT token", e)
            throw e
        }
    }

    /**
     * Manually decode JWT without library (fallback method)
     */
    fun decodeManually(token: String): Map<String, Any>? {
        try {
            val parts = token.split(".")
            if (parts.size != 3) {
                PassageLogger.error(TAG, "Invalid JWT format - expected 3 parts, got ${parts.size}")
                return null
            }

            val payload = parts[1]
            val paddedPayload = addPadding(payload)
            val decodedBytes = Base64.decode(paddedPayload, Base64.URL_SAFE or Base64.NO_PADDING)
            val json = String(decodedBytes)

            PassageLogger.debug(TAG, "Decoded JWT payload: $json")

            val jsonObject = JSONObject(json)
            val map = mutableMapOf<String, Any>()

            jsonObject.keys().forEach { key ->
                val keyStr = key as? String ?: return@forEach
                map[keyStr] = jsonObject.get(keyStr)
            }

            return map

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to manually decode JWT token", e)
            return null
        }
    }

    /**
     * Extract specific boolean claim from token
     */
    fun getBooleanClaim(token: String, claimName: String): Boolean? {
        return try {
            val jwt = decode(token)
            jwt.getClaim(claimName).asBoolean()
        } catch (e: Exception) {
            // Fallback to manual parsing
            val claims = decodeManually(token)
            claims?.get(claimName) as? Boolean
        }
    }

    /**
     * Extract specific string claim from token
     */
    fun getStringClaim(token: String, claimName: String): String? {
        return try {
            val jwt = decode(token)
            jwt.getClaim(claimName).asString()
        } catch (e: Exception) {
            // Fallback to manual parsing
            val claims = decodeManually(token)
            claims?.get(claimName) as? String
        }
    }

    /**
     * Extract specific double claim from token
     */
    fun getDoubleClaim(token: String, claimName: String): Double? {
        return try {
            val jwt = decode(token)
            jwt.getClaim(claimName).asDouble()
        } catch (e: Exception) {
            // Fallback to manual parsing
            val claims = decodeManually(token)
            when (val value = claims?.get(claimName)) {
                is Double -> value
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
    }

    /**
     * Extract recording flags from token
     */
    fun extractRecordingFlags(token: String): RecordingFlags {
        val recordFlag = getBooleanClaim(token, "record") ?: false
        val captureScreenshot = getBooleanClaim(token, "captureScreenshot") ?: false
        val captureInterval = getDoubleClaim(token, "captureScreenshotInterval")
        val sessionId = getStringClaim(token, "sessionId")

        PassageLogger.info(TAG, "Recording flags extracted:")
        PassageLogger.info(TAG, "  - record: $recordFlag")
        PassageLogger.info(TAG, "  - captureScreenshot: $captureScreenshot")
        PassageLogger.info(TAG, "  - captureInterval: $captureInterval")
        PassageLogger.info(TAG, "  - sessionId: $sessionId")

        return RecordingFlags(
            record = recordFlag,
            captureScreenshot = captureScreenshot,
            captureScreenshotInterval = captureInterval,
            sessionId = sessionId
        )
    }

    /**
     * Add padding to base64 string if needed
     */
    private fun addPadding(base64: String): String {
        val remainder = base64.length % 4
        return if (remainder > 0) {
            base64 + "=".repeat(4 - remainder)
        } else {
            base64
        }
    }

    /**
     * Data class for recording flags
     */
    data class RecordingFlags(
        val record: Boolean,
        val captureScreenshot: Boolean,
        val captureScreenshotInterval: Double?,
        val sessionId: String?
    )
}