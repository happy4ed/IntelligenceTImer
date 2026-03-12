package com.intellitimer.vision

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 크래시 시 스택트레이스를 공개 Downloads 폴더에 저장.
 *
 * Android 10+ : MediaStore.Downloads API → /sdcard/Download/crash_YYYYMMDD_HHmmss.txt
 * Android  9- : Environment.getExternalStoragePublicDirectory → 동일 경로
 */
class CrashLogger : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (_: Throwable) {
                // 로그 저장 실패해도 기존 핸들러는 반드시 호출
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName  = "crash_$timestamp.txt"

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val content = buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("IntelliTimer Crash Report")
            appendLine("═══════════════════════════════════════")
            appendLine("시각    : $timestamp")
            appendLine("스레드  : ${thread.name} (id=${thread.id})")
            appendLine("기기    : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI     : ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("───────────────────────────────────────")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message : ${throwable.message}")
            appendLine("───────────────────────────────────────")
            appendLine("Stack Trace:")
            appendLine(sw.toString())
            throwable.cause?.let {
                appendLine("───────────────────────────────────────")
                appendLine("Caused by: ${it.javaClass.name}: ${it.message}")
                val cw = StringWriter()
                it.printStackTrace(PrintWriter(cw))
                appendLine(cw.toString())
            }
        }

        val bytes = content.toByteArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore API → 사용자 Downloads 폴더
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { os -> os.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(it, values, null, null)
            }
            android.util.Log.e("CrashLogger", "Crash saved to Downloads/$fileName")
        } else {
            // Android 9 이하: 공개 Downloads 직접 접근
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            File(downloadsDir, fileName).writeBytes(bytes)
            android.util.Log.e("CrashLogger", "Crash saved to ${downloadsDir.absolutePath}/$fileName")
        }

        android.util.Log.e("CrashLogger", content)
    }
}
