package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class PadRecorder(
    private val context: Context
) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /**
     * @return the output file if recording actually started, or null if it
     * failed (mic busy with another app, hardware error, etc). This used to
     * let prepare()/start() throw straight out of the function — a very
     * reachable real-world failure (any other app holding the mic) that
     * would crash the whole app instead of just failing this one recording.
     */
    fun startRecording(): File? {

        val file = File(
            context.cacheDir,
            "pad_record_${System.currentTimeMillis()}.m4a"
        )

        val newRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

        return try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            outputFile = file
            android.util.Log.d("REC", "Recording Started")
            file
        } catch (e: Exception) {
            android.util.Log.e("REC", "Failed to start recording: ${e.message}", e)
            newRecorder.release()
            recorder = null
            outputFile = null
            null
        }
    }

    fun stopRecording(): File? {

        try {
            recorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        recorder?.release()
        recorder = null

        android.util.Log.d(
            "REC",
            "Saved: ${outputFile?.absolutePath}"
        )

        return outputFile
    }
}