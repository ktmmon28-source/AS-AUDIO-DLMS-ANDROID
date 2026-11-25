
package com.asaudio.dlms

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
}
