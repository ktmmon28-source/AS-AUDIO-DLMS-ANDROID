
package com.asaudio.dlms
import android.media.MediaCodec
import android.media.MediaFormat
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class Mp3AudioInfo(
    val mime: String,
    val sampleRate: Int,
    val channelCount: Int
)

class Mp3PcmDecoder(
    private val context: Context,
    private val uri: Uri
) {

    fun inspect(): Mp3AudioInfo? {
        val extractor = MediaExtractor()

        return try {
            extractor.setDataSource(context, uri, null)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: continue

                if (mime.startsWith("audio/")) {
                    val sampleRate =
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        else 0

                    val channels =
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        else 0

                    return Mp3AudioInfo(
                        mime,
                        sampleRate,
                        channels
                    )
                }
            }

            null
        } finally {
            extractor.release()
        }
    }
    
fun decodeToPcm(
    onPcm: (ByteArray, Int, Int, Int, Int) -> Unit
) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var started = false

    try {
        extractor.setDataSource(context, uri)

        var trackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue

            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }

        require(trackIndex >= 0 && format != null) {
            "Audio track tidak ditemukan"
        }

        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        started = true

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {

            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10000)

                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()

                    val size = extractor.readSampleData(inputBuffer, 0)

                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            size,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, 10000)

            if (outputIndex >= 0) {
                if (info.size > 0) {
                    val output = codec.getOutputBuffer(outputIndex)!!
                    val buffer = output.duplicate()

                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)

                    val pcm = ByteArray(info.size)
                    buffer.get(pcm)

                    onPcm(
                        pcm,
                        0,
                        pcm.size,
                        sampleRate,
                        channels
                    )
                }

                val eos =
                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                codec.releaseOutputBuffer(outputIndex, false)

                if (eos) outputDone = true

            } else if (
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {
                val newFormat = codec.outputFormat

                sampleRate = newFormat.getInteger(
                    MediaFormat.KEY_SAMPLE_RATE
                )

                channels = newFormat.getInteger(
                    MediaFormat.KEY_CHANNEL_COUNT
                )
            }
        }

    } finally {
        if (started) {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
        }

        codec?.release()
        extractor.release()
    }
}

}
