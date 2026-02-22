package com.musicdownloader.app.data.api

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Analyzes audio files to measure their loudness in dB (RMS).
 * Used for volume normalization across tracks.
 */
object AudioAnalyzer {

    /**
     * Measure the RMS loudness of an audio file in dB.
     * Returns null if analysis fails.
     */
    suspend fun measureLoudness(file: File): Float? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            // Find the audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) return@withContext null

            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sumSquares = 0.0
            var sampleCount = 0L
            var inputDone = false

            while (true) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Read output (decoded PCM)
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Read as 16-bit PCM samples
                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numSamples = shortBuffer.remaining()

                        for (i in 0 until numSamples) {
                            val sample = shortBuffer.get().toDouble() / Short.MAX_VALUE
                            sumSquares += sample * sample
                            sampleCount++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            codec.stop()
            codec.release()

            if (sampleCount == 0L) return@withContext null

            val rms = sqrt(sumSquares / sampleCount)
            if (rms <= 0.0) return@withContext null

            // Convert to dB
            (20 * log10(rms)).toFloat()
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
