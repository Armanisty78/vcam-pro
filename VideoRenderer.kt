package com.example.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer

/**
 * High-performance, low-latency native video decoder using MediaExtractor and MediaCodec.
 * Decodes video frames directly onto a provided Surface (e.g., target camera Surface or preview).
 */
class VideoRenderer(
    private val context: Context,
    private val videoUri: Uri,
    private val targetSurface: Surface,
    private val onPlaybackCompleted: () -> Unit = {}
) {
    private val tag = "VCamVideoRenderer"
    private var isRunning = false
    private var decodeThread: Thread? = null
    
    // Playback parameters
    var isLooping = true
    var brightnessOffset = 0f // -1.0 to 1.0
    var isBlurEnabled = false
    
    @Volatile
    private var isPaused = false

    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false
        decodeThread = Thread {
            runDecodeLoop()
        }.apply {
            name = "vcam-codec-thread"
            start()
        }
    }

    fun stop() {
        isRunning = false
        decodeThread?.interrupt()
        try {
            decodeThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        decodeThread = null
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    private fun runDecodeLoop() {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        
        try {
            extractor = MediaExtractor()
            // Set data source from the Uri
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Could not open file descriptor for URI: $videoUri")

            // Find video track
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }

            if (videoTrackIndex < 0) {
                Log.e(tag, "No video track found in file $videoUri")
                onPlaybackCompleted()
                return
            }

            extractor.selectTrack(videoTrackIndex)
            val format = extractor.getTrackFormat(videoTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            
            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, targetSurface, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            var startSystemTime: Long = -1
            var startSampleTime: Long = -1

            while (isRunning && !isOutputEOS) {
                if (isPaused) {
                    SystemClock.sleep(30)
                    // Reset reference clocks so timing remains smooth on resume
                    startSystemTime = -1
                    continue
                }

                // 1. Queue input buffers from extractor
                if (!isInputEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize < 0) {
                            // End of stream
                            if (isLooping) {
                                // Loop back to start
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                val loopSampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (loopSampleSize >= 0) {
                                    val presentationTimeUs = extractor.sampleTime
                                    decoder.queueInputBuffer(inputBufferIndex, 0, loopSampleSize, presentationTimeUs, 0)
                                    extractor.advance()
                                    startSystemTime = -1 // reset timing for seamless loop
                                } else {
                                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isInputEOS = true
                                }
                            } else {
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            }
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. Dequeue decoded output buffers to render to surface
                val outputBufferIndex = decoder.dequeueOutputBuffer(info, 10000)
                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        Log.d(tag, "Output format changed: $newFormat")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No buffer available yet
                    }
                    else -> {
                        if (outputBufferIndex >= 0) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.d(tag, "Decoder received output EOS flag")
                                isOutputEOS = true
                            }

                            if (info.size > 0) {
                                // Frame rate pacing logic using presentation timestamp
                                if (startSystemTime < 0) {
                                    startSystemTime = SystemClock.elapsedRealtimeNanos() / 1000
                                    startSampleTime = info.presentationTimeUs
                                }

                                val sampleDelta = info.presentationTimeUs - startSampleTime
                                val systemDelta = (SystemClock.elapsedRealtimeNanos() / 1000) - startSystemTime
                                val sleepTimeUs = sampleDelta - systemDelta

                                if (sleepTimeUs > 10000) { // sleep if more than 10ms early
                                    SystemClock.sleep(sleepTimeUs / 1000)
                                }

                                // Apply filters conceptually (since we render directly via MediaCodec to Surface,
                                // Advanced shaders are used in OpenGL pipelines, but simple frame delay/controls are maintained here)
                                decoder.releaseOutputBuffer(outputBufferIndex, true)
                            } else {
                                decoder.releaseOutputBuffer(outputBufferIndex, false)
                            }
                        }
                    }
                }
            }

            if (isOutputEOS) {
                onPlaybackCompleted()
            }

        } catch (e: Exception) {
            Log.e(tag, "Error in Video Renderer Loop: ${e.message}", e)
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) { /* ignore */ }
            try {
                extractor?.release()
            } catch (e: Exception) { /* ignore */ }
        }
    }
}
