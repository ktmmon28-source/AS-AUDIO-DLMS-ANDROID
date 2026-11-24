
package com.asaudio.dlms

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"

        const val ACTION_CAPTURE_STATUS = "com.asaudio.dlms.CAPTURE_STATUS"
        const val EXTRA_STATUS = "capture_status"
        const val EXTRA_LEFT_LEVEL = "capture_left_level"
        const val EXTRA_RIGHT_LEVEL = "capture_right_level"

        private const val CHANNEL_ID = "AS_AUDIO_CAPTURE"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "AS_AUDIO_CAPTURE"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishStatus(
        message: String,
        left: Float = 0f,
        right: Float = 0f
    ) {
        val update = Intent(ACTION_CAPTURE_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, message)
            putExtra(EXTRA_LEFT_LEVEL, left.coerceIn(0f, 1f))
            putExtra(EXTRA_RIGHT_LEVEL, right.coerceIn(0f, 1f))
        }
        sendBroadcast(update)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = buildNotification("Menyiapkan audio capture...")

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || data == null) {
            publishStatus("DATA IZIN CAPTURE TIDAK ADA")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            publishStatus("IZIN MIKROFON BELUM DIBERIKAN")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAudioCapture(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Capture initialization failed", e)
            publishStatus("CAPTURE GAGAL: ${e.message ?: "kesalahan engine"}")
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startAudioCapture(resultCode: Int, data: Intent) {

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data)

        val mediaProjection = projection
            ?: throw IllegalStateException("MediaProjection tidak tersedia")

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    capturing = false
                    publishStatus("IZIN CAPTURE DIHENTIKAN")
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper())
        )

        val captureConfig =
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val sampleRate = 48000

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            throw IllegalStateException("Buffer audio tidak valid: $minBuffer")
        }

        val bufferSize = minBuffer * 2

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord gagal diinisialisasi")
        }

        audioRecord = recorder
        recorder.startRecording()

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord tidak mulai merekam")
        }

        capturing = true
        updateNotification("PCM 48 kHz Stereo berjalan")
        publishStatus("CAPTURE AKTIF - MENUNGGU SINYAL")

        captureThread = Thread {
            val pcm = ShortArray(bufferSize / 2)
            var lastUpdate = 0L

            while (capturing) {
                val count = recorder.read(
                    pcm, 0, pcm.size, AudioRecord.READ_BLOCKING
                )

                if (count > 0) {
                    var leftSum = 0.0
                    var rightSum = 0.0
                    var frames = 0
                    var i = 0

                    while (i + 1 < count) {
                        val left = pcm[i].toDouble() / 32768.0
                        val right = pcm[i + 1].toDouble() / 32768.0

                        leftSum += left * left
                        rightSum += right * right
                        frames++
                        i += 2
                    }

                    val leftRms = if (frames > 0) {
                        sqrt(leftSum / frames).toFloat()
                    } else 0f

                    val rightRms = if (frames > 0) {
                        sqrt(rightSum / frames).toFloat()
                    } else 0f

                    val now = System.nanoTime()

                    if (now - lastUpdate >= 100_000_000L) {
                        val signal = leftRms > 0.002f || rightRms > 0.002f

                        val message = if (signal) {
                            "PCM STEREO TERDETEKSI"
                        } else {
                            "CAPTURE AKTIF - BELUM ADA SINYAL"
                        }

                        publishStatus(message, leftRms, rightRms)
                        lastUpdate = now
                    }

                } else if (count < 0) {
                    Log.e(TAG, "AudioRecord read error: $count")
                    publishStatus("AUDIO READ ERROR: $count")
                    capturing = false
                }
            }

            Log.d(TAG, "Capture thread selesai")
        }.apply {
            name = "ASAudioPcmCapture"
            start()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AS AUDIO Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AS AUDIO DLMS")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message)
        )
    }

    override fun onDestroy() {
        capturing = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        audioRecord?.release()
        audioRecord = null

        projection?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }

        projection = null
        super.onDestroy()
    }
}
