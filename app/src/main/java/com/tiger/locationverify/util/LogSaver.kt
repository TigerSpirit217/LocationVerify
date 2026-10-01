package com.tiger.locationverify.util

import com.tiger.locationverify.R

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地日志：写入应用内部存储（filesDir/logs），无需任何存储权限，
 * 便于离线排查定位/鉴权问题。
 */
object LogSaver {

    private lateinit var dir: File
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    val currentFile: File
        get() = File(dir, "log_${dayFmt.format(Date())}.txt")

    @Synchronized
    fun d(tag: String, msg: String) {
        if (!::dir.isInitialized) return
        try {
            currentFile.appendText("[${timeFmt.format(Date())}][$tag] $msg\n")
        } catch (_: Exception) {
            // 忽略写入失败
        }
    }

    /** 读取当前日志文件的最后 n 行 */
    fun lastLines(context: Context, n: Int): String {
        if (!::dir.isInitialized) return ""
        return try {
            currentFile.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: context.getString(R.string.text_no_logs_yet)
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        if (!::dir.isInitialized) return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
            // 忽略
        }
    }
}