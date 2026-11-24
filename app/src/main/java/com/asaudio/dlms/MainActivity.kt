
package com.asaudio.dlms
import android.util.Log
import android.widget.Toast
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var projectionManager: MediaProjectionManager
    private var receiverRegistered = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val REQUEST_CAPTURE_PERMISSION = 1001
        private const val REQUEST_AUDIO_PERMISSION = 1002
        private const val REQUEST_FILE_PICKER = 1003
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
        try {
            val dspStatus = NativeDsp.getEngineStatus()
            Log.i("AS_AUDIO_DSP", dspStatus)
            Toast.makeText(this, dspStatus, Toast.LENGTH_LONG).show()
        } catch (e: LinkageError) {
            Log.e("AS_AUDIO_DSP", "Native DSP library error", e)
            Toast.makeText(this, "Native DSP library error", Toast.LENGTH_LONG).show()
        }
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = callback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                return try {
                    startActivityForResult(
                        intent,
                        REQUEST_FILE_PICKER
                    )
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

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

        if (requestCode == REQUEST_FILE_PICKER) {
            val results: Array<Uri>? =
                if (resultCode == RESULT_OK && data != null) {
                    val clipData = data.clipData

                    when {
                        clipData != null -> {
                            Array(clipData.itemCount) { index ->
                                clipData.getItemAt(index).uri
                            }
                        }

                        data.data != null -> {
                            arrayOf(data.data!!)
                        }

                        else -> null
                    }
                } else {
                    null
                }

            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }

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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

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
