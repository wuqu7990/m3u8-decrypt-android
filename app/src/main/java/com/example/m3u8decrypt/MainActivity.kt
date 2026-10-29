package com.example.m3u8decrypt

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvFolder: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var etM3u8: EditText
    private lateinit var etKey: EditText
    private lateinit var etName: EditText
    private lateinit var btnStartLocal: Button
    private lateinit var tvLog: TextView

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            HttpServerService.selectedFolderUri = it
            val docFile = DocumentFile.fromTreeUri(this, it)
            tvFolder.text = "已选保存目录: ${docFile?.name ?: it.path}"
            getPreferences(MODE_PRIVATE).edit().putString("saved_folder_uri", it.toString()).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvFolder = findViewById(R.id.tvFolder)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        etM3u8 = findViewById(R.id.etM3u8)
        etKey = findViewById(R.id.etKey)
        etName = findViewById(R.id.etName)
        btnStartLocal = findViewById(R.id.btnStartLocal)
        tvLog = findViewById(R.id.tvLog)

        // 恢复持久化目录
        val savedUriStr = getPreferences(MODE_PRIVATE).getString("saved_folder_uri", null)
        if (savedUriStr != null) {
            val uri = Uri.parse(savedUriStr)
            HttpServerService.selectedFolderUri = uri
            val doc = DocumentFile.fromTreeUri(this, uri)
            tvFolder.text = "已选保存目录: ${doc?.name ?: uri.path}"
        }

        // 目录选择
        btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // 服务启停控制
        btnToggleService.setOnClickListener {
            if (HttpServerService.isRunning) {
                stopHttpService()
            } else {
                startHttpService()
            }
            updateUiState()
        }

        // 启动本地任务
        btnStartLocal.setOnClickListener {
            val url = etM3u8.text.toString().trim()
            val key = etKey.text.toString().trim()
            val name = etName.text.toString().trim().ifEmpty { "video_${System.currentTimeMillis()}" }

            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "请填写 M3U8 地址和 Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!HttpServerService.isRunning) {
                startHttpService()
            }

            val engine = M3u8DecryptEngine()
            val taskId = engine.startTask(this, url, key, name, HttpServerService.selectedFolderUri)
            tvLog.text = "任务已发起 (ID: $taskId)，正在后台下载与 PES 解密..."
        }

        updateUiState()
    }

    private fun startHttpService() {
        val intent = Intent(this, HttpServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopHttpService() {
        val intent = Intent(this, HttpServerService::class.java).apply {
            action = HttpServerService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState() {
        if (HttpServerService.isRunning) {
            tvStatus.text = "🟢 监听服务运行中 (端口: ${HttpServerService.PORT})"
            btnToggleService.text = "⏹ 停止后台服务"
        } else {
            tvStatus.text = "🔴 监听服务已停止"
            btnToggleService.text = "▶ 启动后台服务"
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }
}
