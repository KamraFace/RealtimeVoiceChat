package com.example.realtimevoicechatkotlin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.toByteString

class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private val sampleRate = 16000 // 16kHz, sunucunun beklediği örnekleme hızıyla eşleşmeli
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSizeInBytes = 0

    var audioChunkListener: ((ByteArray) -> Unit)? = null
    var audioChunkByteStringListener: ((ByteString) -> Unit)? = null


    init {
        bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSizeInBytes == AudioRecord.ERROR_BAD_VALUE || bufferSizeInBytes == AudioRecord.ERROR) {
            Log.e("AudioRecorder", "Invalid buffer size: $bufferSizeInBytes. Using default 2048.")
            // Sorun varsa veya çok küçükse makul bir varsayılan ayarla
            bufferSizeInBytes = 2048 * 2 // Örnek: 16-bit için 2048 sample * 2 byte/sample
        }
        // Daha büyük bir buffer, daha az ama daha büyük chunk'lar anlamına gelir.
        // Sunucunun beklentilerine ve ağ koşullarına göre ayarlanabilir.
        // Örneğin, her 100ms'de bir veri göndermek istiyorsak:
        // bufferSizeInBytes = (sampleRate * 16 * 1 * 100) / (1000 * 8) // 16 bit, mono
        // bufferSizeInBytes = (sampleRate / 10) * 2 // 100ms chunk for 16-bit mono
    }

    fun startRecording() {
        if (isRecording) {
            Log.w("AudioRecorder", "Already recording.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioRecorder", "RECORD_AUDIO permission not granted.")
            // İzin kontrolü MainActivity'de yapılmalı, burada sadece logluyoruz.
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeInBytes
            )
        } catch (e: IllegalArgumentException) {
            Log.e("AudioRecorder", "Failed to create AudioRecord instance: ${e.message}", e)
            // Farklı bir buffer boyutu veya yapılandırma dene
            // bufferSizeInBytes = sampleRate * 2 * 5 // 5 saniyelik buffer gibi daha büyük bir değer
             try {
                bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2 // İkiye katla
                Log.i("AudioRecorder", "Retrying with buffer size: $bufferSizeInBytes")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSizeInBytes
                )
            } catch (e2: IllegalArgumentException) {
                Log.e("AudioRecorder", "Failed to create AudioRecord instance on retry: ${e2.message}", e2)
                return
            }
        }


        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecorder", "AudioRecord initialization failed.")
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        Log.d("AudioRecorder", "Recording started with buffer size: $bufferSizeInBytes")

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val audioBuffer = ByteArray(bufferSizeInBytes)
            while (isActive && isRecording) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readResult > 0 && readResult != AudioRecord.ERROR_INVALID_OPERATION) {
                    // Ses verisini kopyala çünkü buffer yeniden kullanılacak
                    val chunk = audioBuffer.copyOf(readResult)
                    audioChunkListener?.invoke(chunk) // ByteArray olarak gönder
                    audioChunkByteStringListener?.invoke(chunk.toByteString(0, readResult)) // ByteString olarak gönder
                } else if (readResult < 0) {
                    Log.e("AudioRecorder", "Error reading audio data: $readResult")
                     // Hata durumunda döngüyü kırmak veya yeniden başlatmayı denemek gerekebilir
                    if (readResult == AudioRecord.ERROR_INVALID_OPERATION && !isRecording) {
                        Log.i("AudioRecorder", "Recording stopped, ignoring ERROR_INVALID_OPERATION.")
                        break
                    }
                    // Diğer hatalar için:
                    // stopRecording() // Kaydı durdur
                    // break
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            Log.w("AudioRecorder", "Not recording or already stopped.")
            return
        }
        isRecording = false // Önce bu flag'i ayarla ki döngü bitsin
        recordingJob?.cancel() // Coroutine'i iptal et
        recordingJob = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: IllegalStateException) {
            Log.e("AudioRecorder", "Failed to stop AudioRecord: ${e.message}", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
            Log.d("AudioRecorder", "Recording stopped and resources released.")
        }
    }

    fun isRecording(): Boolean {
        return isRecording
    }
}
