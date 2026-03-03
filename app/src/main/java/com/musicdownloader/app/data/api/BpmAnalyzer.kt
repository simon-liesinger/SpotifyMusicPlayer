package com.musicdownloader.app.data.api

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Analyzes audio files to detect BPM (beats per minute).
 * Uses onset detection via energy peaks + autocorrelation.
 *
 * Can be swapped for aubio JNI for higher accuracy later.
 */
object BpmAnalyzer {

    /**
     * Detect the BPM of an audio file.
     * Returns null if detection fails.
     */
    suspend fun detectBpm(file: File): Float? = withContext(Dispatchers.Default) {
        if (!file.exists()) return@withContext null

        val (samples, sampleRate) = decodeToPcm(file) ?: return@withContext null
        if (samples.size < sampleRate * 2) return@withContext null

        detectBpmFromSamples(samples, sampleRate)
    }

    private fun decodeToPcm(file: File): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

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

            if (audioTrackIndex == -1 || format == null) return null

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val monoSamples = mutableListOf<Float>()
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numSamples = shortBuffer.remaining()

                        // Downmix to mono
                        if (channels > 1) {
                            var i = 0
                            while (i + channels <= numSamples) {
                                var sum = 0f
                                for (c in 0 until channels) {
                                    sum += shortBuffer.get().toFloat() / Short.MAX_VALUE
                                }
                                monoSamples.add(sum / channels)
                                i += channels
                            }
                        } else {
                            for (i in 0 until numSamples) {
                                monoSamples.add(shortBuffer.get().toFloat() / Short.MAX_VALUE)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            codec.stop()
            codec.release()

            if (monoSamples.isEmpty()) return null
            return Pair(monoSamples.toFloatArray(), sampleRate)
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    /**
     * BPM detection via onset energy + autocorrelation.
     *
     * 1. Compute energy in overlapping windows
     * 2. Compute onset strength (positive energy derivative)
     * 3. Autocorrelate onset strength
     * 4. Find peak in BPM range [60, 200]
     */
    private fun detectBpmFromSamples(samples: FloatArray, sampleRate: Int): Float? {
        val hopSize = sampleRate / 20  // 50ms hops
        val windowSize = hopSize * 2   // 100ms windows

        // Step 1: compute energy per window
        val energies = mutableListOf<Float>()
        var pos = 0
        while (pos + windowSize <= samples.size) {
            var energy = 0f
            for (i in pos until pos + windowSize) {
                energy += samples[i] * samples[i]
            }
            energies.add(energy)
            pos += hopSize
        }

        if (energies.size < 10) return null

        // Step 2: onset strength = positive derivative of energy
        val onsets = FloatArray(energies.size)
        for (i in 1 until energies.size) {
            val diff = energies[i] - energies[i - 1]
            onsets[i] = if (diff > 0f) diff else 0f
        }

        // Normalize onsets
        val maxOnset = onsets.max()
        if (maxOnset > 0f) {
            for (i in onsets.indices) {
                onsets[i] /= maxOnset
            }
        }

        // Step 3: autocorrelation over BPM range [60, 200]
        val onsetRate = sampleRate.toFloat() / hopSize
        val minLag = (onsetRate * 60f / 200f).toInt()  // lag for 200 BPM
        val maxLag = minOf((onsetRate * 60f / 60f).toInt(), onsets.size / 2)  // lag for 60 BPM

        if (minLag >= maxLag) return null

        var bestBpm = 0f
        var bestCorr = Float.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var corr = 0f
            val n = onsets.size - lag
            for (i in 0 until n) {
                corr += onsets[i] * onsets[i + lag]
            }
            corr /= n

            if (corr > bestCorr) {
                bestCorr = corr
                bestBpm = onsetRate * 60f / lag
            }
        }

        return if (bestBpm in 60f..200f) bestBpm else null
    }
}
