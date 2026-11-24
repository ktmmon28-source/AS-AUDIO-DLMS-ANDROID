
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

class AudioCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"

        private const val CHANNEL_ID = "AS_AUDIO_CAPTURE"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "AS_AUDIO_CAPTURE"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val serviceIntent =
                Intent(context, AudioCaptureService::class.java).apply {
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

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                capturing = false
                stopSelf()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = buildNotification(
            "Menyiapkan audio capture..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Capture permission data missing")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Izin audio belum diberikan")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAudioCapture(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Capture initialization failed", e)
            updateNotification("Audio capture gagal dimulai")
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startAudioCapture(
        resultCode: Int,
        data: Intent
    ) {

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data)

        val mediaProjection = projection
            ?: throw IllegalStateException("MediaProjection tidak tersedia")

        mediaProjection.registerCallback(
            projectionCallback,
            Handler(Looper.getMainLooper())
        )

        val captureConfig =
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val sampleRate = 48000

        val audioFormat = AudioFormat.Builder()
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
            throw IllegalStateException("Buffer audio tidak valid")
        }

        val bufferSize = minBuffer * 2

        val recorder = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
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
        updateNotification("Audio capture aktif - PCM 48 kHz Stereo")

        captureThread = Thread {
            val pcmBuffer = ShortArray(bufferSize / 2)

            while (capturing) {
                val count = recorder.read(
                    pcmBuffer,
                    0,
                    pcmBuffer.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count > 0) {
                    // Data PCM stereo 16-bit diterima di sini.
                    // Tahap berikutnya akan menghubungkannya ke DSP.
                } else if (count < 0) {
                    Log.e(TAG, "AudioRecord read error: $count")
                    break
                }
            }

            Log.d(TAG, "Capture thread selesai")
        }

        captureThread?.start()

        Log.d(TAG, "PCM capture started: 48000 Hz stereo")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AS AUDIO Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
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
        val manager = getSystemService(
            NotificationManager::class.java
        )

        manager.notify(
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
                it.unregisterCallback(projectionCallback)
                it.stop()
            } catch (_: Exception) {
            }
        }

        projection = null

        super.onDestroy()
    }
}
