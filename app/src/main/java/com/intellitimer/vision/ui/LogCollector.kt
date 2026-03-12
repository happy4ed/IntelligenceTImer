package com.intellitimer.vision.ui

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 내 로그 원형 버퍼.
 *
 * 모든 주요 컴포넌트가 android.util.Log 대신 이 클래스를 사용하면
 * 기기에서 직접 로그를 확인할 수 있음 (LogViewerActivity).
 *
 * Thread-safe: synchronized(buffer) 사용.
 */
object LogCollector {

    private const val MAX_ENTRIES = 600

    enum class Level(val tag: String) { V("V"), D("D"), I("I"), W("W"), E("E") }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val msg: String
    ) {
        val formatted: String
            get() = "${fmtTime(timeMs)} ${level.tag}/$tag: $msg"
    }

    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES + 1)
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun v(tag: String, msg: String) { record(Level.V, tag, msg); Log.v(tag, msg) }
    fun d(tag: String, msg: String) { record(Level.D, tag, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { record(Level.I, tag, msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { record(Level.W, tag, msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { record(Level.E, tag, msg); Log.e(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) {
        record(Level.E, tag, "$msg — ${t.message}")
        Log.e(tag, msg, t)
    }

    fun getAll(): List<Entry> = synchronized(buffer) { buffer.toList() }
    fun clear()               = synchronized(buffer) { buffer.clear() }

    private fun record(level: Level, tag: String, msg: String) {
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, msg))
        }
    }

    private fun fmtTime(ms: Long): String = synchronized(sdf) { sdf.format(Date(ms)) }
}
