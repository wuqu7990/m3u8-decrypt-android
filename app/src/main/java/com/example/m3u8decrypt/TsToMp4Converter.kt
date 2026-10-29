package com.example.m3u8decrypt

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object TsToMp4Converter {

    /**
     * 利用 Android 原生 MediaExtractor + MediaMuxer 无损极速将 TS 封装为标准 MP4
     * 解决手机原生播放器没有进度条、无法快进的问题
     */
    fun convert(tsFile: File, mp4File: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(tsFile.absolutePath)

            muxer = MediaMuxer(mp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackCount = extractor.trackCount
            val indexMap = HashMap<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrackIndex = muxer.addTrack(format)
                    indexMap[i] = muxerTrackIndex
                }
            }

            if (indexMap.isEmpty()) return false

            muxer.start()

            val maxBufferSize = 2 * 1024 * 1024 // 2MB 缓冲区
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                bufferInfo.offset = 0
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                val muxerTrack = indexMap[trackIndex]
                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                }
                extractor.advance()
            }

            muxer.stop()
            return mp4File.exists() && mp4File.length() > 0
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { muxer?.release() } catch (e: Exception) {}
            try { extractor?.release() } catch (e: Exception) {}
        }
    }
}
