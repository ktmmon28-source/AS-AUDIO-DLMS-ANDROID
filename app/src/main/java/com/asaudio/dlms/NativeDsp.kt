
package com.asaudio.dlms

object NativeDsp {

    init {
        System.loadLibrary("as_audio_dsp")
    }

    external fun getEngineStatus(): String
    external fun startTestTone(): Boolean
    external fun stopTestTone()
        external fun startPcmStream(sampleRate: Int): Boolean

    external fun writePcm(
        samples: ShortArray,
        offset: Int,
        sampleCount: Int,
        channels: Int
    ): Int

    external fun getPcmQueuedFrames(): Long

    external fun stopPcmStream()
}
