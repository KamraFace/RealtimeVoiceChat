package com.example.realtimevoicechatkotlin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val sampleRate = 24000 // Sunucudan gelen TTS sesinin örnekleme hızı (RealtimeTTS varsayılanı)
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var minBufferSize: Int = 0

    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackJob: Job? = null
    private var isPlaying = false

    init {
        minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            Log.e("AudioPlayer", "AudioTrack.getMinBufferSize returned an error or invalid size: $minBufferSize. Using default 4096.")
            minBufferSize = 4096 // Güvenli bir varsayılan değer
        }
         Log.d("AudioPlayer", "AudioTrack minBufferSize: $minBufferSize")
    }

    fun startPlaying() {
        if (isPlaying) {
            Log.w("AudioPlayer", "Already playing.")
            return
        }

        try {
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize * 2) // Min buffer'dan biraz daha büyük
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize * 2,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("AudioPlayer", "AudioTrack initialization failed. State: ${audioTrack?.state}")
                audioTrack?.release()
                audioTrack = null
                return
            }
            audioTrack?.play()
            isPlaying = true
            Log.d("AudioPlayer", "AudioTrack started.")
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error initializing or starting AudioTrack: ${e.message}", e)
            audioTrack?.release()
            audioTrack = null
            return
        }


        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d("AudioPlayer", "Playback job started. Waiting for audio data...")
            try {
                while (isActive && isPlaying) {
                    // Kuyruktan ses verisini al (bloklayarak bekler)
                    val audioData = audioQueue.take()
                    if (audioData.isNotEmpty() && audioTrack != null && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        // Log.d("AudioPlayer", "Writing ${audioData.size} bytes to AudioTrack.")
                        var written = 0
                        while (written < audioData.size && isActive) {
                            val result = audioTrack?.write(audioData, written, audioData.size - written) ?: -1
                            if (result > 0) {
                                written += result
                            } else {
                                Log.e("AudioPlayer", "AudioTrack write error: $result")
                                // Hata durumunda döngüyü kırmak veya beklemek gerekebilir
                                break
                            }
                        }
                    } else if (audioTrack == null || audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        Log.w("AudioPlayer", "AudioTrack not playing or null, discarding data.")
                        // Eğer çalma durumu değiştiyse kuyruğu temizleyip döngüden çıkabiliriz.
                        // audioQueue.clear()
                        // break
                    }
                }
            } catch (e: InterruptedException) {
                Log.i("AudioPlayer", "Playback job interrupted.")
                Thread.currentThread().interrupt() // Kesme durumunu koru
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Exception in playback loop: ${e.message}", e)
            } finally {
                Log.d("AudioPlayer", "Playback job finished.")
                // isPlaying = false // StopPlaying içinde yönetiliyor
            }
        }
    }

    fun playAudioChunk(chunk: ByteArray) {
        if (!isPlaying) {
            // Log.w("AudioPlayer", "Not playing, cannot queue audio chunk. Call startPlaying() first.")
            // Otomatik başlatma isteniyorsa:
            // startPlaying()
            // Ancak bu, her chunk geldiğinde kontrol edilmeli ve sadece ilk chunk'ta başlatılmalı.
            // Veya MainActivity bu durumu yönetmeli.
            // Şimdilik, eğer oynatıcı başlamadıysa chunk'ı atla.
            return
        }
        if (chunk.isNotEmpty()) {
            try {
                audioQueue.put(chunk) // Kuyruğa ekle
                // Log.d("AudioPlayer", "Queued ${chunk.size} bytes. Queue size: ${audioQueue.size}")
            } catch (e: InterruptedException) {
                Log.e("AudioPlayer", "Failed to queue audio chunk: ${e.message}", e)
                Thread.currentThread().interrupt()
            }
        }
    }

    fun stopPlaying() {
        if (!isPlaying && audioQueue.isEmpty()) {
            Log.w("AudioPlayer", "Not playing or already stopped.")
            return
        }
        isPlaying = false // Önce bu flag'i ayarla ki döngü bitsin
        playbackJob?.cancel() // Coroutine'i iptal et
        playbackJob = null

        // Kuyruktaki bekleyen verileri temizle, böylece stop çağrıldığında eski sesler çalınmaz.
        audioQueue.clear()
        // Döngünün bitmesini sağlamak için boş bir chunk eklenebilir (eğer take() blokluyorsa)
        // audioQueue.offer(ByteArray(0)) // playbackJob zaten iptal edildiği için gerekmeyebilir.


        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.pause() // Pause, verinin buffer'dan flush edilmesini beklemez
                audioTrack?.flush() // Kalan veriyi at
                audioTrack?.stop()  // Durdur
                Log.d("AudioPlayer", "AudioTrack stopped and flushed.")
            }
        } catch (e: IllegalStateException) {
            Log.e("AudioPlayer", "Error stopping AudioTrack: ${e.message}", e)
        } finally {
            audioTrack?.release()
            audioTrack = null
            Log.d("AudioPlayer", "AudioTrack released.")
        }
    }

    // Kullanıcı konuşmaya başladığında TTS'i hemen durdurmak için
    fun interrupt() {
        Log.d("AudioPlayer", "Interrupting playback.")
        audioQueue.clear() // Gelecekteki chunk'ları temizle
        // Mevcut çalan sesi hemen durdurmak için AudioTrack'i durdur ve yeniden başlat
        // Ancak bu, sesin kesilmesine neden olabilir.
        // Genellikle flush yeterli olabilir.
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack?.pause() // Anında durdurma için pause + flush daha etkili olabilir
                audioTrack?.flush() // Buffer'daki veriyi temizle
                Log.d("AudioPlayer", "AudioTrack flushed due to interruption.")
                // audioTrack?.play() // Gerekirse tekrar başlatmak için, ama genellikle yeni TTS gelene kadar bekleriz.
            } catch (e: IllegalStateException) {
                Log.e("AudioPlayer", "Error flushing AudioTrack on interrupt: ${e.message}", e)
            }
        }
    }

    fun isPlaying(): Boolean {
        return isPlaying
    }
}
