package com.example.testapplication

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

private const val MIN_DBFS = -160.0
private const val MAX_SHORT = 32768.0

suspend fun startNoiseMeter(onDb: (Double) -> Unit) = withContext(Dispatchers.Default) {
    val sampleRate = 44100
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )

    val buffer = ShortArray(bufferSize)
    audioRecord.startRecording()
    try {
        while (isActive) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                var sum = 0.0
                for (i in 0 until read) {
                    val v = buffer[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / read)
                onDb(rmsToDbfs(rms))
            }
        }
    } finally {
        audioRecord.stop()
        audioRecord.release()
    }
}

private fun rmsToDbfs(rms: Double): Double {
    if (rms <= 0.0) return MIN_DBFS
    return 20.0 * log10(rms / MAX_SHORT)
}
