package com.example.m3u8decrypt

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import org.json.JSONObject

class M3u8DecryptEngine {

    class TaskInfo(
        val id: String,
        val filename: String,
        var status: String = "running",
        var stage: String = "准备中",
        var progress: Double = 0.0,
        var totalSlices: Int = 0,
        var downloadedSlices: Int = 0,
        var decryptedSlices: Int = 0,
        var error: String? = null
    ) {
        fun toJson(): String {
            return JSONObject()
                .put("id", id)
                .put("filename", filename)
                .put("status", status)
                .put("stage", stage)
                .put("progress", Math.round(progress * 10.0) / 10.0)
                .put("total_slices", totalSlices)
                .put("downloaded_slices", downloadedSlices)
                .put("decrypted_slices", decryptedSlices)
                .put("error_message", error)
                .toString()
        }
    }

    private val tasks = ConcurrentHashMap<String, TaskInfo>()
    private val pool = Executors.newFixedThreadPool(8)

    fun getTask(id: String): TaskInfo? = tasks[id]

    fun startTask(
        context: Context,
        m3u8Url: String,
        keyB64: String,
        filename: String,
        folderUri: Uri?
    ): String {
        val taskId = UUID.randomUUID().toString().substring(0, 8)
        val cleanName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val task = TaskInfo(taskId, "$cleanName.ts")
        tasks[taskId] = task

        pool.execute {
            runDecrypt(context, task, m3u8Url, keyB64, folderUri)
        }
        return taskId
    }

    private fun runDecrypt(
        context: Context,
        task: TaskInfo,
        m3u8Url: String,
        keyB64: String,
        folderUri: Uri?
    ) {
        val tempDir = File(context.cacheDir, task.id).apply { mkdirs() }
        try {
            // 1. 初始化 AES Cipher
            val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))

            // 2. 拉取 m3u8
            task.stage = "解析 m3u8"
            val conn = URL(m3u8Url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val m3u8Text = conn.inputStream.bufferedReader().use { it.readText() }

            val baseUrl = m3u8Url.substringBeforeLast("/")
            val tsList = m3u8Text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            task.totalSlices = tsList.size

            // 3. 并发下载分片
            task.stage = "下载分片"
            val downloadPool = Executors.newFixedThreadPool(8)
            val futures = tsList.mapIndexed { index, seg ->
                downloadPool.submit {
                    val fullUrl = if (seg.startsWith("http")) seg else "$baseUrl/$seg"
                    val localFile = File(tempDir, String.format("%05d.ts", index))
                    if (!localFile.exists() || localFile.length() == 0L) {
                        val c = URL(fullUrl).openConnection() as HttpURLConnection
                        c.setRequestProperty("User-Agent", "Mozilla/5.0")
                        localFile.outputStream().use { out -> c.inputStream.copyTo(out) }
                    }
                    synchronized(task) {
                        task.downloadedSlices++
                        task.progress = (task.downloadedSlices.toDouble() / task.totalSlices) * 50.0
                    }
                }
            }
            futures.forEach { it.get() }
            downloadPool.shutdown()

            // 4. PES 级 AES 解密并输出到用户指定的 SAF 目录或外部存储
            task.stage = "解密合并"
            val outputStream: OutputStream = if (folderUri != null) {
                val docFolder = DocumentFile.fromTreeUri(context, folderUri)
                val newFile = docFolder?.createFile("video/mp2t", task.filename)
                context.contentResolver.openOutputStream(newFile!!.uri)!!
            } else {
                // 回退到应用外部缓存文件
                val outFile = File(context.getExternalFilesDir(null), task.filename)
                FileOutputStream(outFile)
            }

            outputStream.use { out ->
                for (i in 0 until task.totalSlices) {
                    val segFile = File(tempDir, String.format("%05d.ts", i))
                    val rawBytes = segFile.readBytes()
                    val decBytes = decryptPes(rawBytes, cipher, intArrayOf(0x100, 0x101))
                    out.write(decBytes)

                    task.decryptedSlices = i + 1
                    task.progress = 50.0 + (task.decryptedSlices.toDouble() / task.totalSlices) * 50.0
                }
            }

            task.stage = "已完成"
            task.status = "finished"
            task.progress = 100.0
        } catch (e: Exception) {
            task.status = "failed"
            task.stage = "失败"
            task.error = e.message
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun decryptPes(data: ByteArray, cipher: Cipher, targetPids: IntArray): ByteArray {
        val length = data.size
        var syncOffset = 0
        while (syncOffset < Math.min(1000, length - 564)) {
            if (data[syncOffset] == 0x47.toByte() && data[syncOffset + 188] == 0x47.toByte() && data[syncOffset + 376] == 0x47.toByte()) {
                break
            }
            syncOffset++
        }
        if (syncOffset >= Math.min(1000, length - 564)) return data

        for (targetPid in targetPids) {
            val sliceOffsets = ArrayList<Int>()
            val sliceLens = ArrayList<Int>()

            fun flushAndDecrypt() {
                if (sliceOffsets.isEmpty()) return
                val pesStream = ByteArrayOutputStream()
                for (k in 0 until sliceOffsets.size) {
                    pesStream.write(data, sliceOffsets[k], sliceLens[k])
                }
                val pesBytes = pesStream.toByteArray()
                if (pesBytes.size >= 9 && pesBytes[0] == 0.toByte() && pesBytes[1] == 0.toByte() && pesBytes[2] == 1.toByte()) {
                    val hdrTotal = 9 + (pesBytes[8].toInt() and 0xFF)
                    val payloadLen = pesBytes.size - hdrTotal
                    if (payloadLen > 0) {
                        val blockLen = (payloadLen / 16) * 16
                        if (blockLen > 0) {
                            val plain = cipher.doFinal(pesBytes, hdrTotal, blockLen)
                            var wr = 0
                            var skipped = 0
                            for (k in 0 until sliceOffsets.size) {
                                val off = sliceOffsets[k]
                                val ln = sliceLens[k]
                                val sliceOff: Int
                                val sliceLen: Int
                                if (skipped < hdrTotal) {
                                    val avail = Math.min(ln, hdrTotal - skipped)
                                    skipped += avail
                                    sliceOff = off + avail
                                    sliceLen = ln - avail
                                } else {
                                    sliceOff = off
                                    sliceLen = ln
                                }
                                if (sliceLen > 0 && wr < blockLen) {
                                    val toWrite = Math.min(sliceLen, blockLen - wr)
                                    System.arraycopy(plain, wr, data, sliceOff, toWrite)
                                    wr += toWrite
                                }
                            }
                        }
                    }
                }
                sliceOffsets.clear()
                sliceLens.clear()
            }

            var i = syncOffset
            while (i <= length - 188) {
                if (data[i] != 0x47.toByte()) {
                    i += 188
                    continue
                }
                val pid = (((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF))
                if (pid != targetPid) {
                    i += 188
                    continue
                }
                val pusi = ((data[i + 1].toInt() ushr 6) and 1) == 1
                val afc = (data[i + 3].toInt() ushr 4) and 3
                var offset = i + 4
                if (afc in 2..3) {
                    offset += 1 + (data[i + 4].toInt() and 0xFF)
                    if (offset >= i + 188) {
                        i += 188
                        continue
                    }
                }
                if (pusi) flushAndDecrypt()
                val chunkLen = (i + 188) - offset
                if (chunkLen > 0) {
                    sliceOffsets.add(offset)
                    sliceLens.add(chunkLen)
                }
                i += 188
            }
            flushAndDecrypt()
        }
        return data
    }
}
