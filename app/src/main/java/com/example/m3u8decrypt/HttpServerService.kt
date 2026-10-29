package com.example.m3u8decrypt

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import org.json.JSONObject

class HttpServerService : Service() {

    companion object {
        const val PORT = 8765
        const val ACTION_STOP = "com.example.m3u8decrypt.ACTION_STOP"
        const val CHANNEL_ID = "m3u8_decrypt_service"
        
        @Volatile
        var isRunning = false
            private set
            
        var selectedFolderUri: Uri? = null
        var currentLog = "服务已启动，监听端口: $PORT"

        private val mainHandler = Handler(Looper.getMainLooper())
        var onStatusChanged: ((Boolean) -> Unit)? = null

        fun notifyStatus(running: Boolean) {
            isRunning = running
            mainHandler.post {
                onStatusChanged?.invoke(running)
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private val engine = M3u8DecryptEngine()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("解密监听服务运行中 (端口: $PORT)")
        startForeground(1001, notification)

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        threadPool.execute {
            try {
                serverSocket = ServerSocket(PORT)
                notifyStatus(true)
                currentLog = "HTTP 服务正在 127.0.0.1:$PORT 监听油猴请求..."
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    val client = serverSocket!!.accept()
                    threadPool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                currentLog = "服务已退出: ${e.message}"
            } finally {
                notifyStatus(false)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val pathWithQuery = parts[1]

            var contentLength = 0
            var headerLine: String?
            while (reader.readLine().also { headerLine = it } != null) {
                if (headerLine!!.isEmpty()) break
                if (headerLine!!.lowercase().startsWith("content-length:")) {
                    contentLength = headerLine!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val corsHeaders = "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\n"

            if (method == "OPTIONS") {
                output.write("HTTP/1.1 204 No Content\r\n$corsHeaders\r\n".toByteArray())
                output.flush()
                socket.close()
                return
            }

            if (method == "POST" && pathWithQuery == "/api/start") {
                val bodyChars = CharArray(contentLength)
                reader.read(bodyChars, 0, contentLength)
                val body = String(bodyChars)
                val json = JSONObject(body)

                val m3u8Url = json.getString("m3u8_url")
                val keyB64 = json.getString("key_b64")
                val filename = json.optString("filename", "video_${System.currentTimeMillis()}")
                val chapter = json.optString("chapter", "").trim().ifEmpty { null }

                val taskId = engine.startTask(this, m3u8Url, keyB64, filename, selectedFolderUri, chapter)

                val resp = JSONObject().put("task_id", taskId).toString()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n$corsHeaders\r\n$resp".toByteArray())
                output.flush()
            } else if (method == "GET" && pathWithQuery.startsWith("/api/progress")) {
                val taskId = pathWithQuery.substringAfter("task_id=").substringBefore("&")
                val task = engine.getTask(taskId)
                val resp = task?.toJson() ?: JSONObject().put("error", "Not Found").toString()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n$corsHeaders\r\n$resp".toByteArray())
                output.flush()
            } else {
                output.write("HTTP/1.1 404 Not Found\r\n$corsHeaders\r\n\r\n".toByteArray())
                output.flush()
            }
            socket.close()
        } catch (e: Exception) {
            try { socket.close() } catch (ex: Exception) {}
        }
    }

    private fun stopServer() {
        notifyStatus(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "M3U8 解密后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "常驻通知，用于保持后台解密与油猴插件通信" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, HttpServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M3U8 解密助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "⏹ 停止服务", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
