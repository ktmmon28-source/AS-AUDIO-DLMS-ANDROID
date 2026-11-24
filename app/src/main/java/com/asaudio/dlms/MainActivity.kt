
package com.asaudio.dlms

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var projectionManager: MediaProjectionManager

    private var projectionPermissionData: Intent? = null

    companion object {
        private const val REQUEST_CAPTURE_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun requestAudioCapture() {
            runOnUiThread {
                val captureIntent =
                    projectionManager.createScreenCaptureIntent()

                startActivityForResult(
                    captureIntent,
                    REQUEST_CAPTURE_PERMISSION
                )
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE_PERMISSION) {

            if (resultCode == RESULT_OK && data != null) {
                projectionPermissionData = data

                webView.evaluateJavascript(
                    "window.onCapturePermissionResult(true);",
                    null
                )

            } else {
                projectionPermissionData = null

                webView.evaluateJavascript(
                    "window.onCapturePermissionResult(false);",
                    null
                )
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}

