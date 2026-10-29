package com.example.m3u8decrypt

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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

    // 进度卡片控件
    private lateinit var cardProgress: LinearLayout
    private lateinit var tvTaskTitle: TextView
    private lateinit var tvCurrentStage: TextView
    private lateinit var tvCurrentPercent: TextView
    private lateinit var pbProgress: ProgressBar
    private lateinit var tvSlicesCount: TextView

    // 历史记录
    private lateinit var llHistoryContainer: LinearLayout
    private lateinit var tvEmptyHistory: TextView
    private lateinit var btnClearHistory: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgressUi()
            handler.postDelayed(this, 600)
        }
    }

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

        cardProgress = findViewById(R.id.cardProgress)
        tvTaskTitle = findViewById(R.id.tvTaskTitle)
        tvCurrentStage = findViewById(R.id.tvCurrentStage)
        tvCurrentPercent = findViewById(R.id.tvCurrentPercent)
        pbProgress = findViewById(R.id.pbProgress)
        tvSlicesCount = findViewById(R.id.tvSlicesCount)

        llHistoryContainer = findViewById(R.id.llHistoryContainer)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        // 恢复持久化目录
        val savedUriStr = getPreferences(MODE_PRIVATE).getString("saved_folder_uri", null)
        if (savedUriStr != null) {
            val uri = Uri.parse(savedUriStr)
            HttpServerService.selectedFolderUri = uri
            val doc = DocumentFile.fromTreeUri(this, uri)
            tvFolder.text = "已选保存目录: ${doc?.name ?: uri.path}"
        }

        btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // 监听后台服务真实运行状态变更
        HttpServerService.onStatusChanged = { running ->
            updateUiState()
        }

        btnToggleService.setOnClickListener {
            btnToggleService.isEnabled = false
            if (HttpServerService.isRunning) {
                btnToggleService.text = "⏳ 正在停止服务..."
                stopHttpService()
            } else {
                btnToggleService.text = "⏳ 正在启动服务..."
                startHttpService()
            }
            // 500ms 后重新启用按钮防止连击
            handler.postDelayed({ btnToggleService.isEnabled = true }, 600)
        }

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
            tvLog.text = "任务已提交 (ID: $taskId)，正在后台下载与 PES 解密..."
        }

        btnClearHistory.setOnClickListener {
            DownloadHistoryManager.clear(this)
            loadHistoryUi()
        }

        updateUiState()
        loadHistoryUi()
    }

    private fun updateProgressUi() {
        val task = M3u8DecryptEngine.activeTask
        if (task != null && task.status == "running") {
            cardProgress.visibility = View.VISIBLE
            tvTaskTitle.text = "正在处理: ${task.filename}"
            tvCurrentStage.text = task.stage
            val pInt = task.progress.toInt()
            pbProgress.progress = pInt
            tvCurrentPercent.text = "$pInt%"
            tvSlicesCount.text = "分片下载: ${task.downloadedSlices}/${task.totalSlices} · 解密: ${task.decryptedSlices}"
        } else if (task != null && (task.status == "finished" || task.status == "failed")) {
            if (cardProgress.visibility == View.VISIBLE) {
                if (task.status == "finished") {
                    tvCurrentStage.text = "🎉 解密完成！已保存到指定文件夹"
                    pbProgress.progress = 100
                    tvCurrentPercent.text = "100%"
                    loadHistoryUi()
                } else {
                    tvCurrentStage.text = "❌ 失败: ${task.error}"
                }
            }
        }
    }

    private fun loadHistoryUi() {
        llHistoryContainer.removeAllViews()
        val records = DownloadHistoryManager.getRecords(this)
        if (records.isEmpty()) {
            tvEmptyHistory.visibility = View.VISIBLE
        } else {
            tvEmptyHistory.visibility = View.GONE
            for (rec in records) {
                val itemView = layoutInflater.inflate(android.R.layout.simple_list_item_2, llHistoryContainer, false)
                val text1 = itemView.findViewById<TextView>(android.R.id.text1)
                val text2 = itemView.findViewById<TextView>(android.R.id.text2)

                text1.text = "🎬 ${rec.filename}"
                text1.setTextColor(0xFFFFFFFF.toInt())
                text1.textSize = 13f

                text2.text = "${rec.sizeMbStr()} · ${rec.timeStr}"
                text2.setTextColor(0xFF94A3B8.toInt())
                text2.textSize = 11f

                itemView.setBackgroundColor(0xFF090E1A.toInt())
                itemView.setPadding(12, 10, 12, 10)

                // 点击尝试调用系统播放器
                if (rec.uriString != null) {
                    itemView.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(rec.uriString), "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "文件已保存在所选文件夹中", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                llHistoryContainer.addView(itemView)
            }
        }
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
        loadHistoryUi()
        handler.post(progressUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(progressUpdater)
    }
}
