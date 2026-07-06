package com.elysium.vanguard.recordshield.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogFile {
    private var file: File? = null
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        val today = dateFmt.format(Date())
        file = File(dir, "upload_$today.log")
        // Clear old logs
        dir.listFiles()?.filter { it.name != "upload_$today.log" }?.forEach { it.delete() }
    }

    fun log(tag: String, msg: String) {
        val line = "${sdf.format(Date())} $tag: $msg\n"
        try {
            file?.appendText(line)
        } catch (_: Exception) {}
    }

    fun getLogFile(): File? = file
}
