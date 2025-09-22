package com.passage.sdk.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.os.Build
import android.util.Base64
import android.view.View
import android.webkit.WebView
import com.passage.sdk.PassageConstants
import com.passage.sdk.logging.PassageLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Utility for capturing screenshots from WebViews and Views
 * Supports both full WebView content and visible area capture
 */
object ScreenshotCapture {

    private const val TAG = "ScreenshotCapture"

    /**
     * Capture screenshot of a WebView
     * Uses the most appropriate method based on Android version
     */
    suspend fun captureWebView(webView: WebView): Bitmap? = withContext(Dispatchers.Main) {
        try {
            PassageLogger.debug(TAG, "Capturing WebView screenshot")

            // Method 1: Use capturePicture for older devices (deprecated but still works)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return@withContext captureWebViewLegacy(webView)
            }

            // Method 2: Draw WebView to Canvas (most reliable)
            return@withContext captureWebViewCanvas(webView)

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to capture WebView screenshot", e)
            null
        }
    }

    /**
     * Capture WebView using Canvas drawing
     */
    private fun captureWebViewCanvas(webView: WebView): Bitmap? {
        return try {
            // Enable drawing cache
            webView.isDrawingCacheEnabled = true
            webView.buildDrawingCache(true)

            // Get the content dimensions
            val contentWidth = webView.width
            val contentHeight = webView.height

            if (contentWidth <= 0 || contentHeight <= 0) {
                PassageLogger.warn(TAG, "WebView has invalid dimensions: ${contentWidth}x${contentHeight}")
                return null
            }

            // Create bitmap with WebView dimensions
            val bitmap = Bitmap.createBitmap(contentWidth, contentHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw WebView content to canvas
            webView.draw(canvas)

            // Disable drawing cache
            webView.isDrawingCacheEnabled = false
            webView.destroyDrawingCache()

            PassageLogger.info(TAG, "WebView screenshot captured: ${contentWidth}x${contentHeight}")
            bitmap

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Canvas capture failed", e)
            null
        }
    }

    /**
     * Legacy method for older Android versions
     */
    @Suppress("DEPRECATION")
    private fun captureWebViewLegacy(webView: WebView): Bitmap? {
        return try {
            val picture = webView.capturePicture()
            if (picture == null) {
                PassageLogger.warn(TAG, "capturePicture returned null")
                return null
            }

            val bitmap = Bitmap.createBitmap(
                picture.width,
                picture.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            picture.draw(canvas)

            PassageLogger.info(TAG, "Legacy screenshot captured: ${picture.width}x${picture.height}")
            bitmap

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Legacy capture failed", e)
            null
        }
    }

    /**
     * Capture screenshot of entire screen/view
     * Used for record mode (full UI capture)
     */
    suspend fun captureView(view: View): Bitmap? = withContext(Dispatchers.Main) {
        try {
            PassageLogger.debug(TAG, "Capturing View screenshot")

            val width = view.width
            val height = view.height

            if (width <= 0 || height <= 0) {
                PassageLogger.warn(TAG, "View has invalid dimensions: ${width}x${height}")
                return@withContext null
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)

            PassageLogger.info(TAG, "View screenshot captured: ${width}x${height}")
            bitmap

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to capture View screenshot", e)
            null
        }
    }

    /**
     * Convert bitmap to base64 string
     * Applies image optimization based on configuration
     */
    fun bitmapToBase64(
        bitmap: Bitmap,
        quality: Int = PassageConstants.Screenshot.JPEG_QUALITY,
        maxWidth: Int = PassageConstants.Screenshot.MAX_WIDTH,
        maxHeight: Int = PassageConstants.Screenshot.MAX_HEIGHT
    ): String? {
        return try {
            // Scale bitmap if needed
            val scaledBitmap = scaleBitmapIfNeeded(bitmap, maxWidth, maxHeight)

            // Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            // Convert to base64
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            PassageLogger.debug(TAG, "Bitmap converted to base64: ${base64.length} chars")

            // Add data URI prefix for web compatibility
            "data:image/jpeg;base64,$base64"

        } catch (e: Exception) {
            PassageLogger.error(TAG, "Failed to convert bitmap to base64", e)
            null
        }
    }

    /**
     * Scale bitmap if it exceeds maximum dimensions
     */
    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Check if scaling is needed
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        // Calculate scale factor
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = minOf(scaleWidth, scaleHeight)

        // Calculate new dimensions
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        PassageLogger.debug(TAG, "Scaling bitmap from ${width}x${height} to ${newWidth}x${newHeight}")

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Capture screenshot with custom image optimization parameters
     */
    suspend fun captureWithOptimization(
        webView: WebView,
        imageOptimization: Map<String, Any>?
    ): String? {
        val bitmap = captureWebView(webView) ?: return null

        val quality = (imageOptimization?.get("quality") as? Float)?.times(100)?.toInt()
            ?: PassageConstants.Screenshot.JPEG_QUALITY

        val maxWidth = imageOptimization?.get("maxWidth") as? Int
            ?: PassageConstants.Screenshot.MAX_WIDTH

        val maxHeight = imageOptimization?.get("maxHeight") as? Int
            ?: PassageConstants.Screenshot.MAX_HEIGHT

        return bitmapToBase64(bitmap, quality, maxWidth, maxHeight)
    }

    /**
     * Data class for screenshot result
     */
    data class ScreenshotResult(
        val base64: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Int
    )

    /**
     * Capture screenshot with detailed result information
     */
    suspend fun captureWithDetails(webView: WebView): ScreenshotResult? {
        val bitmap = captureWebView(webView) ?: return null

        val base64 = bitmapToBase64(bitmap) ?: return null

        // Calculate approximate size (base64 is ~1.37x larger than binary)
        val sizeBytes = (base64.length * 3 / 4)

        return ScreenshotResult(
            base64 = base64,
            width = bitmap.width,
            height = bitmap.height,
            sizeBytes = sizeBytes
        )
    }
}