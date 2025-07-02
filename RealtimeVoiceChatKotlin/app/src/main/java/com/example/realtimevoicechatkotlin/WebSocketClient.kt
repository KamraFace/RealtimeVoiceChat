package com.example.realtimevoicechatkotlin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketClient {

    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Allow long-running connections
        .build()
    private var webSocket: WebSocket? = null
    private var job: Job? = null
    var isManuallyClosed: Boolean = false // MainActivity'den erişilebilir olması için public veya internal
        private set // Sadece bu sınıf içinden değiştirilebilir

    var listener: WebSocketClientListener? = null

    interface WebSocketClientListener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosing(code: Int, reason: String)
        fun onFailure(t: Throwable, response: Response?)
        fun onClosed(code: Int, reason: String)
        fun onBinaryMessage(bytes: ByteString)
    }

    fun connect(url: String) {
        if (webSocket != null && job?.isActive == true) {
            Log.w("WebSocketClient", "Already connected or connecting.")
            return
        }
        isManuallyClosed = false
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocketClient", "Connected to $url")
                this@WebSocketClient.webSocket = webSocket
                listener?.onOpen()
                // Start a keep-alive mechanism if needed by the server
                // startKeepAlive()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocketClient", "Receiving: $text")
                listener?.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("WebSocketClient", "Receiving bytes: ${bytes.hex()}")
                listener?.onBinaryMessage(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocketClient", "Closing: $code / $reason")
                listener?.onClosing(code, reason)
                // No need to call webSocket.close() here, it's already happening
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocketClient", "Closed: $code / $reason")
                this@WebSocketClient.webSocket = null
                job?.cancel()
                listener?.onClosed(code, reason)
                // Attempt to reconnect if not manually closed
                if (!isManuallyClosed) {
                     Log.d("WebSocketClient", "Attempting to reconnect...")
                     // Implement a backoff strategy for reconnection
                     CoroutineScope(Dispatchers.IO).launch {
                         delay(5000) // Wait 5 seconds before trying to reconnect
                         connect(url)
                     }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketClient", "Error: " + t.message, t)
                listener?.onFailure(t, response)
                this@WebSocketClient.webSocket = null // Ensure it's null on failure
                job?.cancel()
                // Attempt to reconnect if not manually closed
                if (!isManuallyClosed) {
                    Log.d("WebSocketClient", "Attempting to reconnect after failure...")
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000)
                        connect(url)
                    }
                }
            }
        })
    }

    fun sendMessage(message: String) {
        if (webSocket?.send(message) == true) {
            Log.d("WebSocketClient", "Sent message: $message")
        } else {
            Log.w("WebSocketClient", "Failed to send message: $message. WebSocket is null or queue is full.")
        }
    }

    fun sendBinaryMessage(bytes: ByteString) {
        if (webSocket?.send(bytes) == true) {
            Log.d("WebSocketClient", "Sent binary message: ${bytes.hex()}")
        } else {
            Log.w("WebSocketClient", "Failed to send binary message. WebSocket is null or queue is full.")
        }
    }

    fun close() {
        isManuallyClosed = true
        webSocket?.close(1000, "Client closed connection normally.")
        // job?.cancel() // Keep-alive job'ı burada iptal etme, onClosed içinde ediliyor.
        Log.d("WebSocketClient", "Manual close requested.")
    }

    // Optional: Keep-alive mechanism if server requires it or to prevent timeouts
    private fun startKeepAlive() { // OkHttp zaten periyodik ping gönderir, bu özel bir durum için.
        if (job?.isActive == true) return // Zaten çalışıyorsa tekrar başlatma

        job = CoroutineScope(Dispatchers.IO).launch {
            Log.d("WebSocketClient", "Keep-alive job started.")
            try {
                while (isActive) {
                    delay(30000) // Send a ping every 30 seconds
                    if (webSocket != null && webSocket?.send("{\"type\":\"ping\"}") == true) {
                        // OkHttp'nin kendi ping'i genellikle yeterlidir.
                        // Bu, sunucunun özel bir ping formatı beklediği durumlar için.
                        Log.d("WebSocketClient", "Sent custom keep-alive ping.")
                    } else if (webSocket == null) {
                        Log.w("WebSocketClient", "Cannot send keep-alive ping, WebSocket is null. Stopping keep-alive.")
                        break // Stop if WebSocket is gone
                    } else {
                        Log.w("WebSocketClient", "Failed to send keep-alive ping (queue full or error).")
                    }
                }
            } finally {
                Log.d("WebSocketClient", "Keep-alive job ended.")
            }
        }
    }

    private fun stopKeepAlive() {
        job?.cancel()
        job = null
        Log.d("WebSocketClient", "Keep-alive job explicitly stopped.")
    }


    fun isConnected(): Boolean {
        // `webSocket` null değilse ve `onOpen` çağrılmışsa (ve henüz `onClosed` çağrılmamışsa) bağlı kabul edilebilir.
        // OkHttp'nin WebSocket durumu için daha kesin bir yolu olmayabilir, bu yüzden `webSocket` null kontrolü genelde yeterlidir.
        return webSocket != null
    }
}
