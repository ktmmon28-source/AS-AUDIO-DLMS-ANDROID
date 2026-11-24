
package com.asaudio.dlms

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var projectionManager: MediaProjectionManager
    private var receiverRegistered = false

    companion object {
        private const val REQUEST_CAPTURE_PERMISSION = 1001
        private const val REQUEST_AUDIO_PERMISSION = 1002
    }

    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(
                AudioCaptureService.EXTRA_STATUS
            ) ?: return

            val left = intent.getFloatExtra(
                AudioCaptureService.EXTRA_LEFT_LEVEL, 0f
            ).coerceIn(0f, 1f)

            val right = intent.getFloatExtra(
                AudioCaptureService.EXTRA_RIGHT_LEVEL, 0f
            ).coerceIn(0f, 1f)

            val safeStatus = JSONObject.quote(status)

            webView.post {
                webView.evaluateJavascript(
                    "window.onAudioCaptureUpdate($safeStatus, $left, $right);",
                    null
                )
            }
        }
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

        registerCaptureReceiver()

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun registerCaptureReceiver() {
        val filter = IntentFilter(
            AudioCaptureService.ACTION_CAPTURE_STATUS
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                captureStatusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(captureStatusReceiver, filter)
        }

        receiverRegistered = true
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun requestAudioCapture() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_AUDIO_PERMISSION
                    )
                } else {
                    requestProjectionPermission()
                }
            }
        }
    }

    private fun requestProjectionPermission() {
        val captureIntent =
            projectionManager.createScreenCaptureIntent()

        startActivityForResult(
            captureIntent,
            REQUEST_CAPTURE_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                requestProjectionPermission()
            } else {
                webView.evaluateJavascript(
                    "window.onCapturePermissionResult(false);",
                    null
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
                try {
                    AudioCaptureService.start(
                        this,
                        resultCode,
                        data
                    )

                    webView.evaluateJavascript(
                        "window.onCapturePermissionResult(true);",
                        null
                    )

                } catch (e: Exception) {
                    webView.evaluateJavascript(
                        "window.onCapturePermissionResult(false);",
                        null
                    )
                }
            } else {
                webView.evaluateJavascript(
                    "window.onCapturePermissionResult(false);",
                    null
                )
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(captureStatusReceiver)
            receiverRegistered = false
        }

        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
