package com.example.camera

import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File

class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
    }

    private var mediaRecorder: MediaRecorder? = null
    var currentFile: File? = null
        private set

    /**
     * Set up and configure the MediaRecorder with the specified video settings.
     */
    fun prepare(
        outputFile: File,
        isRearCamera: Boolean,
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,
        useH265: Boolean,
        enableEis: Boolean
    ): Surface {
        currentFile = outputFile
        
        // Instantiate the player with context on API 31+
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        
        mediaRecorder = recorder

        try {
            // Audio source
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            // Video source
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)

            // Container format
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputFile.absolutePath)

            // Bitrates are translated to bps
            val videoBitrate = bitrateMbps * 1_000_000
            recorder.setVideoEncodingBitRate(videoBitrate)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoSize(width, height)

            // Codecs
            val videoEncoder = if (useH265) {
                MediaRecorder.VideoEncoder.HEVC
            } else {
                MediaRecorder.VideoEncoder.H264
            }
            recorder.setVideoEncoder(videoEncoder)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(192000)
            recorder.setAudioSamplingRate(48000)

            // Portrait or orientation logic fits on play
            recorder.setOrientationHint(if (isRearCamera) 90 else 270)

            Log.d(TAG, "Preparing MediaRecorder: size=${width}x${height}, fps=$fps, bitrate=${bitrateMbps}Mbps, H265=$useH265, EIS=$enableEis")
            
            recorder.prepare()
            return recorder.surface
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder configuration failed.", e)
            throw e
        }
    }

    fun start() {
        try {
            mediaRecorder?.start()
            Log.d(TAG, "Video recording started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VideoRecorder", e)
            throw e
        }
    }

    fun stop() {
        try {
            mediaRecorder?.stop()
            Log.d(TAG, "Video recording stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder (probably called too early?)", e)
        } finally {
            reset()
        }
    }

    fun reset() {
        try {
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed.", e)
        }
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
