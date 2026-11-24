
package com.asaudio.dlms

object NativeDsp {

    init {
        System.loadLibrary("as_audio_dsp")
    }

    external fun getEngineStatus(): String
    external fun startTestTone(): Boolean
    external fun stopTestTone()
}
