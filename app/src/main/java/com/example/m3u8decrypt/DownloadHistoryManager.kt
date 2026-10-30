package com.example.m3u8decrypt

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class DownloadRecord(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val timeStr: String,
    val uriString: String? = null
) {
    fun sizeMbStr(): String {
        return String.format(Locale.getDefault(), "%.2f MB", sizeBytes.toDouble() / (1024 * 1024))
    }
}

object DownloadHistoryManager {
    private const val PREF_NAME = "m3u8_history"
    private const val KEY_LIST = "history_records"

    fun getRecords(context: Context, folderUri: Uri? = null): List<DownloadRecord> {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_LIST, "[]") ?: "[]"
        val map = LinkedHashMap<String, DownloadRecord>()

        // 1. 读取 SharedPreferences 中记录的任务
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fn = obj.getString("filename")
                map[fn] = DownloadRecord(
                    id = obj.getString("id"),
                    filename = fn,
                    sizeBytes = obj.optLong("size", 0L),
                    timeStr = obj.optString("time", ""),
                    uriString = obj.optString("uri", null)
                )
            }
        } catch (e: Exception) {}

        // 2. 实时扫描手机所选文件夹（递归扫描子目录，即使重装或历史清空，只要文件在就能检测到）
        if (folderUri != null) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                    scanDocFolder(rootDoc, "", map)
                }
            } catch (e: Exception) {}
        }

        return map.values.toList()
    }

    private fun scanDocFolder(folder: DocumentFile, parentPath: String, map: LinkedHashMap<String, DownloadRecord>) {
        val files = folder.listFiles()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (f in files) {
            if (f.isDirectory) {
                val subName = f.name ?: ""
                val nextParent = if (parentPath.isEmpty()) subName else "$parentPath / $subName"
                scanDocFolder(f, nextParent, map)
            } else if (f.isFile) {
                val name = f.name ?: ""
                if (name.endsWith(".mp4") || name.endsWith(".ts")) {
                    val displayName = if (parentPath.isEmpty()) name else "$parentPath / $name"
                    if (!map.containsKey(displayName)) {
                        map[displayName] = DownloadRecord(
                            id = UUID.randomUUID().toString().substring(0, 8),
                            filename = displayName,
                            sizeBytes = f.length(),
                            timeStr = sdf.format(Date(f.lastModified())),
                            uriString = f.uri.toString()
                        )
                    }
                }
            }
        }
    }

    fun addRecord(context: Context, filename: String, sizeBytes: Long, uriString: String? = null) {
        val list = getRecords(context).toMutableList()
        val timeNow = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val record = DownloadRecord(
            id = UUID.randomUUID().toString().substring(0, 8),
            filename = filename,
            sizeBytes = sizeBytes,
            timeStr = timeNow,
            uriString = uriString
        )
        list.add(0, record)
        saveList(context, list)
    }

    fun clear(context: Context) {
        saveList(context, emptyList())
    }

    private fun saveList(context: Context, list: List<DownloadRecord>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
                .put("id", item.id)
                .put("filename", item.filename)
                .put("size", item.sizeBytes)
                .put("time", item.timeStr)
                .put("uri", item.uriString)
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }
}
