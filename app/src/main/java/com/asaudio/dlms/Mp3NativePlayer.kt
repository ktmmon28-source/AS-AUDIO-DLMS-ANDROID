
package com.asaudio.dlms

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

class Mp3NativePlayer(
    context: Context,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext

    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    private val pauseLock = Object()

    fun play(uri: Uri) {
    val previousWorker = worker

    if (previousWorker?.isAlive == true) {
        stopRequested.set(true)

        synchronized(pauseLock) {
            paused.set(false)
            pauseLock.notifyAll()
        }

        previousWorker.interrupt()

        try {
            previousWorker.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        if (previousWorker.isAlive) {
            onError("Lagu sebelumnya belum berhenti")
            return
        }
    }

    stopRequested.set(false)
    paused.set(false)

        worker = Thread {
            var streamStarted = false

            try {
                val decoder = Mp3PcmDecoder(appContext, uri)
                val audioInfo = decoder.inspect()
                    ?: throw IllegalStateException("Track audio tidak ditemukan")

                if (stopRequested.get()) return@Thread

                if (!NativeDsp.startPcmStream(audioInfo.sampleRate)) {
                    throw IllegalStateException("Gagal membuka Native Oboe")
                }

                streamStarted = true
                onStatus("Native MP3 berjalan")

                decoder.decodeToPcm(
    shouldStop = { stopRequested.get() }
) { samples, initialOffset, sampleCount, sampleRate, channels ->

                    if (sampleRate != audioInfo.sampleRate) {
                        throw IllegalStateException(
                            "Perubahan sample rate belum didukung"
                        )
                    }

                    var offset = initialOffset

                    while (offset < sampleCount && !stopRequested.get()) {

                        synchronized(pauseLock) {
                            while (paused.get() && !stopRequested.get()) {
                                pauseLock.wait()
                            }
                        }

                        if (stopRequested.get()) break

                        val writtenFrames = NativeDsp.writePcm(
                            samples,
                            offset,
                            sampleCount - offset,
                            channels
                        )

                        if (writtenFrames > 0) {
                            offset += writtenFrames * channels
                        } else {
                            Thread.sleep(2)
                        }
                    }
                }

                while (
                    !stopRequested.get() &&
                    NativeDsp.getPcmQueuedFrames() > 0
                ) {
                    Thread.sleep(10)
                }

                if (!stopRequested.get()) {
                    onStatus("MP3 selesai")
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                onError(e.message ?: "Kesalahan pemutaran MP3")
            } finally {
                if (streamStarted) {
                    NativeDsp.stopPcmStream()
                }
                onStatus("Native MP3 berhenti")
            }
        }.apply {
            name = "ASAudio-NativeMP3"
            start()
        }
    }

    fun pause() {
        paused.set(true)
        onStatus("MP3 dijeda")
    }

    fun resume() {
        synchronized(pauseLock) {
            paused.set(false)
            pauseLock.notifyAll()
        }
        onStatus("MP3 dilanjutkan")
    }

    fun stop() {
        stopRequested.set(true)

        synchronized(pauseLock) {
            paused.set(false)
            pauseLock.notifyAll()
        }

        worker?.interrupt()
        onStatus("Menghentikan MP3")
    }
}
