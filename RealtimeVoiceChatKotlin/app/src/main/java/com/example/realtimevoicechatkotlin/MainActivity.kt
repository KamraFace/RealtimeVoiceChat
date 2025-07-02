package com.example.realtimevoicechatkotlin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.realtimevoicechatkotlin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Response
import okio.ByteString

class MainActivity : AppCompatActivity(), WebSocketClient.WebSocketClientListener {

    private lateinit var webSocketClient: WebSocketClient

    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button
    private lateinit var buttonReset: Button
    private lateinit var textViewTranscription: TextView
    private lateinit var textViewPartialTranscription: TextView
    private lateinit var textViewAiResponse: TextView

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer

    // Sunucu adresi - localhost emulator için 10.0.2.2'dir. Fiziksel cihazda IP adresinizi kullanın.
    // Python sunucunuzun çalıştığı makinenin IP adresi ve portu
    // Örnek: "ws://192.168.1.100:8000/ws" (yerel ağ IP'niz)
    // VEYA "ws://YOUR_SERVER_PUBLIC_IP_OR_DOMAIN/ws" (genel sunucu)
    // Docker kullanıyorsanız ve port yönlendirmesi yaptıysanız, bilgisayarınızın IP'si.
    private val SERVER_URL = "ws://10.0.2.2:8000/ws" // Emulator için localhost
    // private val SERVER_URL = "ws://YOUR_COMPUTER_IP:8000/ws" // Fiziksel cihaz için

    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    private var isAudioPermissionGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)
        buttonReset = findViewById(R.id.buttonReset)
        textViewTranscription = findViewById(R.id.textViewTranscription)
        textViewPartialTranscription = findViewById(R.id.textViewPartialTranscription)
        textViewAiResponse = findViewById(R.id.textViewAiResponse)

        webSocketClient = WebSocketClient()
        webSocketClient.listener = this

        audioRecorder = AudioRecorder(this)
        audioPlayer = AudioPlayer()

        audioRecorder.audioChunkByteStringListener = { audioChunk ->
            if (webSocketClient.isConnected()) {
                // Kullanıcı konuşmaya başladığında (ilk ses chunk'ı gönderilirken) TTS'i kes.
                // Bu, kullanıcının kendi sesini duymaması ve AI'ın hemen susması için.
                if (audioPlayer.isPlaying()) {
                    Log.d("MainActivity", "User started speaking (audio chunk detected), interrupting TTS playback.")
                    audioPlayer.interrupt()
                }
                Log.d("MainActivity", "Sending audio chunk: ${audioChunk.size} bytes")
                webSocketClient.sendBinaryMessage(audioChunk)
            }
        }

        buttonStart.setOnClickListener {
            checkAndRequestAudioPermission()
            if (isAudioPermissionGranted) {
                startChat()
            }
        }

        buttonStop.setOnClickListener {
            stopChat(false) // false: kullanıcı tarafından durduruldu
        }

        buttonReset.setOnClickListener {
            resetChat()
        }

        buttonStop.isEnabled = false
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            isAudioPermissionGranted = true
            // İzin zaten verilmişse doğrudan başlatılabilir veya startChat çağrılabilir
            // startChat() // Eğer buton sadece izin kontrolü yapıyorsa, startChat'i burada çağırmak mantıklı olabilir
        }
    }


    private fun startChat() {
        if (!isAudioPermissionGranted) {
            Toast.makeText(this, "Audio permission is required to start.", Toast.LENGTH_SHORT).show()
            checkAndRequestAudioPermission() // Tekrar izin iste
            return
        }

        if (!webSocketClient.isConnected()) {
            Log.d("MainActivity", "Connecting to WebSocket server: $SERVER_URL")
            textViewTranscription.text = "Connecting..."
            webSocketClient.connect(SERVER_URL)
            // Buton durumları onOpen içinde güncellenecek
        } else {
            Log.d("MainActivity", "Already connected. Ensuring audio recording and player are started.")
            if (!audioRecorder.isRecording()) {
                audioRecorder.startRecording()
                Log.d("MainActivity", "Audio recording started via startChat (already connected).")
            }
            if (!audioPlayer.isPlaying()) {
                audioPlayer.startPlaying() // TTS için oynatıcıyı başlat
                Log.d("MainActivity", "Audio player started via startChat (already connected).")
            }
            // Sunucuya başlangıç mesajı gönderilebilir
            // webSocketClient.sendMessage("{\"type\":\"audio_systems_ready\"}")
            buttonStart.isEnabled = false
            buttonStop.isEnabled = true
        }
    }

    private fun stopChat(isSystemInitiated: Boolean) {
        Log.d("MainActivity", "Stopping chat. System initiated: $isSystemInitiated")
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
            Log.d("MainActivity", "Audio recording stopped.")
        }
        if (audioPlayer.isPlaying()) {
            audioPlayer.stopPlaying()
            Log.d("MainActivity", "Audio player stopped.")
        }

        if (webSocketClient.isConnected()) {
            if (!isSystemInitiated) {
                 webSocketClient.close()
            }
        } else {
            buttonStart.isEnabled = true
            buttonStop.isEnabled = false
        }

        if (isSystemInitiated) {
            textViewTranscription.append("\nConnection lost.")
        } else {
            textViewTranscription.append("\nChat stopped by user.")
        }
    }

    private fun resetChat() {
        Log.d("MainActivity", "Resetting chat.")
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording()
        }
        if (audioPlayer.isPlaying()) {
            audioPlayer.stopPlaying() // Önce durdur
            audioPlayer.startPlaying() // Sonra tekrar başlat ki yeni TTS için hazır olsun
        } else {
            audioPlayer.startPlaying() // Durmuyorsa bile başlat
        }

        textViewTranscription.text = "Transcription will appear here..."
        textViewPartialTranscription.text = ""
        textViewAiResponse.text = "AI response will appear here..."

        if (webSocketClient.isConnected()) {
             webSocketClient.sendMessage("{\"type\":\"reset_context\"}")
        }
        buttonStart.isEnabled = true
        buttonStop.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called.")
        audioRecorder.stopRecording()
        audioPlayer.stopPlaying()
        webSocketClient.close()
    }

    // WebSocketClientListener implementation
    override fun onOpen() {
        runOnUiThread {
            Toast.makeText(this, "Connected to server", Toast.LENGTH_SHORT).show()
            textViewTranscription.text = "Connected! Press Start to speak.\n"
            buttonStart.isEnabled = true
            buttonStop.isEnabled = false // Ses kaydı ve oynatıcı başlayana kadar pasif

            // Bağlantı açıldığında, kullanıcı Start'a basınca hem kayıt hem oynatma başlayacak.
            // Veya burada audioPlayer.startPlaying() çağrılabilir.
            if (!audioPlayer.isPlaying()) {
                audioPlayer.startPlaying() // TTS için oynatıcıyı her zaman hazır tut
                 Log.d("MainActivity", "AudioPlayer started on WebSocket open.")
            }

            // Otomatik ses kaydı isteniyorsa (isAudioPermissionGranted ise)
            // if (isAudioPermissionGranted) {
            //    startChat() // Bu, kayıt ve oynatıcıyı başlatır, butonları ayarlar
            // } else {
            //    textViewTranscription.append("Grant audio permission and press Start.\n")
            // }
             webSocketClient.sendMessage("{\"type\":\"client_ready\"}")
        }
    }

    override fun onMessage(text: String) {
        runOnUiThread {
            // Gelen mesajın tipine göre işlem yap (JSON parse edilebilir)
            // Bu kısım aynı kalabilir veya daha gelişmiş JSON parse eklenebilir.
            // Örnek: {"type": "full_transcription", "text": "hello world"}
            // Örnek: {"type": "partial_transcription", "text": "hello"}
            // Örnek: {"type": "llm_response_start"}
            // Örnek: {"type": "llm_response_chunk", "text": "Hi"}
            // Örnek: {"type": "llm_response_end"}
            // Örnek: {"type": "error", "message": "..."}
            // Örnek: {"type": "error", "message": "...", "is_critical": true}
            // Örnek: {"type": "tts_start"}
            // Örnek: {"type": "tts_end"}
            // Örnek: {"type": "user_interrupted_by_server"}

            Log.d("MainActivity", "Received text message: $text")

            // Gelen mesajları parse etmek için daha sağlam bir yöntem (örn. Gson veya Kotlinx.serialization) kullanılması önerilir.
            // Şimdilik basit string kontrolleri ile devam ediyoruz.
            try {
                // Basit bir JSON parse denemesi (type alanı için)
                val messageType = 간단JsonTypeParser(text) // Basit bir parser

                when (messageType) {
                    "user_interrupted_by_server" -> {
                        Log.d("MainActivity", "Server indicated user interruption, interrupting TTS playback.")
                        audioPlayer.interrupt()
                    }
                    "tts_start" -> {
                        Log.d("MainActivity", "TTS audio stream started by server.")
                        if (!audioPlayer.isPlaying()) {
                            audioPlayer.startPlaying()
                        }
                        // UI'da "AI Speaking..." gibi bir gösterge eklenebilir.
                        // textViewStatus.text = "AI Speaking..."
                    }
                    "tts_end" -> {
                        Log.d("MainActivity", "TTS audio stream ended by server.")
                        // textViewStatus.text = "Your Turn"
                        // AudioPlayer kuyruk boşalınca kendi duracak.
                    }
                    "full_transcription", "transcription" -> {
                        clearTranscriptionViewStatus() // Önceki hata/durum mesajlarını temizle
                        val content = extractContent(text, listOf("text", "data"))
                        textViewTranscription.append(content + "\n")
                    }
                    "partial_transcription" -> {
                        val content = extractContent(text, listOf("text", "data"))
                        textViewPartialTranscription.text = content
                    }
                    "llm_response_start" -> {
                        textViewAiResponse.text = ""
                    }
                    "llm_response_chunk", "ai_response" -> {
                        val content = extractContent(text, listOf("text", "data"))
                        textViewAiResponse.append(content)
                    }
                    "error" -> {
                        val errorMessage = extractContent(text, listOf("message", "data"), "Unknown server error")
                        val isCritical = text.contains("\"is_critical\":true") // Basit kontrol
                        Log.e("MainActivity", "Server error: $errorMessage, Critical: $isCritical")
                        updateTranscriptionViewWithError("Server error: $errorMessage")
                        Toast.makeText(this, "Server error: $errorMessage", Toast.LENGTH_LONG).show()
                        if (isCritical) {
                            // Kritik bir hatada sohbeti durdurabilir veya kullanıcıya bilgi verebiliriz.
                            // stopChat(true) // Sistemi durdur
                            // textViewStatus.text = "Critical error. Please reset."
                        }
                    }
                    else -> {
                        // Bilinmeyen mesaj tipi veya parse edilemeyen JSON
                        if (!text.startsWith("{\"type\":\"ping\"")) { // Ping mesajlarını loglama
                             Log.w("MainActivity", "Unknown or unhandled message type from server: $text")
                             textViewTranscription.append("Server (raw): $text\n")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing message from server: $text", e)
                updateTranscriptionViewWithError("Error processing server message.")
            }
        }
    }

    // Basit bir JSON "type" alanı parser'ı. Üretim için uygun değildir.
    private fun 간단JsonTypeParser(jsonString: String): String? {
        val typeKey = "\"type\":\""
        val startIndex = jsonString.indexOf(typeKey)
        if (startIndex != -1) {
            val actualStartIndex = startIndex + typeKey.length
            val endIndex = jsonString.indexOf("\"", actualStartIndex)
            if (endIndex != -1) {
                return jsonString.substring(actualStartIndex, endIndex)
            }
        }
        return null
    }


    // extractContent fonksiyonu aynı kalır...
    private fun extractContent(jsonString: String, possibleKeys: List<String>): String {
        try {
            for (key in possibleKeys) {
                val searchKey = "\"$key\":\""
                val startIndex = jsonString.indexOf(searchKey)
                if (startIndex != -1) {
                    val actualStartIndex = startIndex + searchKey.length
                    val endIndex = jsonString.indexOf("\"", actualStartIndex)
                    if (endIndex != -1) {
                        return jsonString.substring(actualStartIndex, endIndex)
                            .replace("\\n", "\n") // Kaçış karakterlerini düzelt
                            .replace("\\\"", "\"")
                    }
                }
            }
            if (jsonString.length > 60) return jsonString.substring(0, 57) + "..."
            return jsonString
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing content from JSON: $jsonString", e)
            return jsonString
        }
    }


    override fun onBinaryMessage(bytes: ByteString) {
        runOnUiThread {
            Log.d("MainActivity", "Received binary message: ${bytes.size()} bytes for TTS playback")
            if (audioPlayer.isPlaying()) {
                audioPlayer.playAudioChunk(bytes.toByteArray())
            } else {
                Log.w("MainActivity", "AudioPlayer not active, trying to start and play chunk.")
                audioPlayer.startPlaying()
                if (audioPlayer.isPlaying()) {
                    audioPlayer.playAudioChunk(bytes.toByteArray())
                } else {
                    Log.e("MainActivity", "Failed to start AudioPlayer for binary message.")
                    updateTranscriptionViewWithError("Error: Could not play audio response.")
                }
            }
        }
    }

    override fun onClosing(code: Int, reason: String) {
        runOnUiThread {
            Log.d("MainActivity", "Server is closing connection: $code / $reason")
            Toast.makeText(this, "Server closing: $reason", Toast.LENGTH_SHORT).show()
            if (audioRecorder.isRecording()) {
                audioRecorder.stopRecording()
            }
            if (audioPlayer.isPlaying()) {
                audioPlayer.stopPlaying()
            }
            // updateTranscriptionViewInfo("Server closing connection...")
        }
    }

    override fun onClosed(code: Int, reason: String) {
        runOnUiThread {
            Log.d("MainActivity", "Connection closed: $code / $reason")
            if (audioRecorder.isRecording()) {
                audioRecorder.stopRecording()
            }
            if (audioPlayer.isPlaying()) {
                audioPlayer.stopPlaying()
            }
            buttonStart.isEnabled = true
            buttonStop.isEnabled = false
            if (!webSocketClient.isManuallyClosed) {
                 val message = "Disconnected: $reason. Attempting to reconnect..."
                 updateTranscriptionViewInfo(message) // textViewTranscription'ı güncelle
                 Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                updateTranscriptionViewInfo("Disconnected by user.")
                Toast.makeText(this, "Disconnected by user.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onFailure(t: Throwable, response: Response?) {
        runOnUiThread {
            Log.e("MainActivity", "WebSocket connection failure: ${t.message}", t)
            if (audioRecorder.isRecording()) {
                audioRecorder.stopRecording()
            }
            if (audioPlayer.isPlaying()) {
                audioPlayer.stopPlaying()
            }
            buttonStart.isEnabled = true
            buttonStop.isEnabled = false
            textViewTranscription.append("\nConnection failed: ${t.message}. Check server and network.")
        }
    }

    // İzin isteği sonucu
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    isAudioPermissionGranted = true
                    Toast.makeText(this, "Audio permission granted. Press Start.", Toast.LENGTH_SHORT).show()
                    // İzin alındıktan sonra kullanıcı Start'a basarak chat'i başlatabilir.
                    // Veya otomatik başlatmak isteniyorsa:
                    // startChat()
                } else {
                    isAudioPermissionGranted = false
                    Toast.makeText(this, "Permission for audio recording denied. Cannot start chat.", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
    }
}
