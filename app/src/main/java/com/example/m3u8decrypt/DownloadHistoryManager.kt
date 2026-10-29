package com.example.m3u8decrypt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

    fun getRecords(context: Context): List<DownloadRecord> {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_LIST, "[]") ?: "[]"
        val list = mutableListOf<DownloadRecord>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DownloadRecord(
                        id = obj.getString("id"),
                        filename = obj.getString("filename"),
                        sizeBytes = obj.optLong("size", 0L),
                        timeStr = obj.optString("time", ""),
                        uriString = obj.optString("uri", null)
                    )
                )
            }
        } catch (e: Exception) {}
        return list
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
