package com.intellitimer.vision.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.intellitimer.vision.R
import java.io.BufferedReader
import java.io.InputStreamReader

class LogViewerActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnAll: Button
    private lateinit var btnInfo: Button
    private lateinit var btnWarn: Button
    private lateinit var btnError: Button
    private lateinit var btnLogcat: Button
    private lateinit var btnPerf: Button
    private lateinit var btnClear: Button
    private lateinit var btnClose: Button
    private lateinit var btnCopy: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLog()
            handler.postDelayed(this, 1500L)
        }
    }

    private var currentFilter: LogCollector.Level? = null   // null = ALL
    private var showLogcat = false
    private var showPerf = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        tvLog      = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)
        btnAll     = findViewById(R.id.btnAll)
        btnInfo    = findViewById(R.id.btnInfo)
        btnWarn    = findViewById(R.id.btnWarn)
        btnError   = findViewById(R.id.btnError)
        btnLogcat  = findViewById(R.id.btnLogcat)
        btnPerf    = findViewById(R.id.btnPerf)
        btnClear   = findViewById(R.id.btnClear)
        btnClose   = findViewById(R.id.btnClose)
        btnCopy    = findViewById(R.id.btnCopy)

        btnClose.setOnClickListener { finish() }
        btnCopy.setOnClickListener { copyDiagnostics() }
        btnClear.setOnClickListener {
            LogCollector.clear()
            tvLog.text = ""
        }

        btnAll.setOnClickListener    { currentFilter = null;                    showLogcat = false; showPerf = false; refreshLog() }
        btnInfo.setOnClickListener   { currentFilter = LogCollector.Level.I;   showLogcat = false; showPerf = false; refreshLog() }
        btnWarn.setOnClickListener   { currentFilter = LogCollector.Level.W;   showLogcat = false; showPerf = false; refreshLog() }
        btnError.setOnClickListener  { currentFilter = LogCollector.Level.E;   showLogcat = false; showPerf = false; refreshLog() }
        btnLogcat.setOnClickListener { showLogcat = true;  showPerf = false;                        refreshLog() }
        btnPerf.setOnClickListener   { showPerf = true;    showLogcat = false;                      refreshLog() }

        handler.post(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }

    // ─── Log rendering ─────────────────────────────────────────────────────────

    private fun refreshLog() {
        val sb = SpannableStringBuilder()
        when {
            showPerf   -> appendPerf(sb)
            showLogcat -> appendLogcat(sb)
            else       -> appendInApp(sb)
        }
        tvLog.text = sb
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendInApp(sb: SpannableStringBuilder) {
        val entries = LogCollector.getAll()
            .let { list ->
                val f = currentFilter
                if (f == null) list else list.filter { it.level.ordinal >= f.ordinal }
            }

        if (entries.isEmpty()) {
            sb.append("(로그 없음)\n")
            return
        }

        for (entry in entries) {
            val color = levelColor(entry.level)
            val line  = "${entry.formatted}\n"
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
        }
    }

    // "Pre=Xms  Infer=Xms  Post=Xms  Total=Xms  det=N  delegate=GPU" 형식 파싱
    private fun appendPerf(sb: SpannableStringBuilder) {
        val perfEntries = LogCollector.getAll()
            .filter { it.tag == "VisionPipeline" }
            .takeLast(100)

        if (perfEntries.isEmpty()) {
            sb.append("(VisionPipeline 데이터 없음 — 카메라가 실행 중인지 확인)\n")
            return
        }

        data class Sample(val pre: Long, val infer: Long, val post: Long, val total: Long)
        val regex = Regex("""Pre=(\d+)ms\s+Infer=(\d+)ms\s+Post=(\d+)ms\s+Total=(\d+)ms""")
        val samples = perfEntries.mapNotNull { entry ->
            regex.find(entry.msg)?.destructured?.let { (pre, infer, post, total) ->
                Sample(pre.toLong(), infer.toLong(), post.toLong(), total.toLong())
            }
        }

        if (samples.isEmpty()) {
            sb.append("(파싱 가능한 프로파일링 항목 없음)\n"); return
        }

        fun stats(values: List<Long>): Triple<Long, Long, Long> {
            val sorted = values.sorted()
            return Triple(sorted.first(), values.sum() / values.size, sorted.last())
        }

        val (preMin, preAvg, preMax)     = stats(samples.map { it.pre })
        val (inferMin, inferAvg, inferMax) = stats(samples.map { it.infer })
        val (postMin, postAvg, postMax)  = stats(samples.map { it.post })
        val (totMin, totAvg, totMax)     = stats(samples.map { it.total })
        val delegate = perfEntries.lastOrNull()?.msg
            ?.substringAfterLast("delegate=", "?") ?: "?"

        val cyan   = Color.rgb(0, 229, 255)
        val green  = Color.rgb(57, 255, 115)
        val amber  = Color.rgb(255, 214, 0)
        val white  = Color.rgb(220, 230, 240)
        val gray   = Color.rgb(140, 155, 170)

        fun appendLine(label: String, color: Int, min: Long, avg: Long, max: Long) {
            val line = "%-10s  min=%3dms  avg=%3dms  max=%3dms\n".format(label, min, avg, max)
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
        }

        val headerStart = sb.length
        sb.append("═══ PERF SUMMARY  [N=${samples.size}  delegate=$delegate] ═══\n")
        sb.setSpan(ForegroundColorSpan(cyan), headerStart, sb.length, 0)

        appendLine("Pre",       green,  preMin,   preAvg,   preMax)
        appendLine("Infer",     amber,  inferMin, inferAvg, inferMax)
        appendLine("Post",      white,  postMin,  postAvg,  postMax)
        appendLine("Total",     cyan,   totMin,   totAvg,   totMax)

        val sepStart = sb.length
        sb.append("─────────────────────────────────────────────\n")
        sb.setSpan(ForegroundColorSpan(gray), sepStart, sb.length, 0)

        val estFps = if (totAvg > 0) 1000.0 / totAvg else 0.0
        val fpsLine = "Est. max FPS ≈ %.1f  (based on avg Total)\n".format(estFps)
        val fpsStart = sb.length
        sb.append(fpsLine)
        sb.setSpan(ForegroundColorSpan(cyan), fpsStart, sb.length, 0)

        val sep2Start = sb.length
        sb.append("═════════════════════════════════════════════\n\n")
        sb.setSpan(ForegroundColorSpan(gray), sep2Start, sb.length, 0)

        // 최근 20개 raw 로그
        val recentStart = sb.length
        sb.append("--- 최근 ${minOf(20, perfEntries.size)}개 raw ---\n")
        sb.setSpan(ForegroundColorSpan(gray), recentStart, sb.length, 0)

        perfEntries.takeLast(20).forEach { entry ->
            val start = sb.length
            sb.append("${entry.formatted}\n")
            sb.setSpan(ForegroundColorSpan(gray), start, sb.length, 0)
        }
    }

    private fun appendLogcat(sb: SpannableStringBuilder) {
        try {
            val pid = android.os.Process.myPid()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=$pid", "-v", "time", "-t", "300")
            )
            val lines = BufferedReader(InputStreamReader(proc.inputStream)).readLines()
            if (lines.isEmpty()) {
                sb.append("(logcat 비어 있음)\n"); return
            }
            for (line in lines) {
                val color = when {
                    line.contains(" E ") || line.contains(" E/") -> Color.rgb(255, 85, 85)
                    line.contains(" W ") || line.contains(" W/") -> Color.rgb(255, 214, 0)
                    line.contains(" I ") || line.contains(" I/") -> Color.rgb(57, 255, 115)
                    else -> Color.rgb(170, 187, 204)
                }
                val start = sb.length
                sb.append("$line\n")
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
            }
        } catch (e: Exception) {
            sb.append("logcat 읽기 실패: ${e.message}\n")
        }
    }

    private fun copyDiagnostics() {
        val sb = StringBuilder()

        // 1. Device info
        sb.appendLine("=== DEVICE INFO ===")
        sb.appendLine("Model   : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android : ${Build.VERSION.RELEASE}  (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI     : ${Build.SUPPORTED_ABIS.firstOrNull()}")
        sb.appendLine()

        // 2. All internal app logs
        sb.appendLine("=== APP LOGS (ALL) ===")
        LogCollector.getAll().forEach { sb.appendLine(it.formatted) }
        sb.appendLine()

        // 3. Logcat — GPU/TFLite/crash keywords
        sb.appendLine("=== LOGCAT (gpu|tflite|delegate|litert|signal|crash|fatal) ===")
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "500")
            )
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                val keywords = listOf("gpu", "tflite", "delegate", "litert", "signal", "crash", "fatal", "npu", "nnapi", "hexagon")
                lines.filter { line -> keywords.any { line.lowercase().contains(it) } }
                     .forEach { sb.appendLine(it) }
            }
        } catch (e: Exception) {
            sb.appendLine("logcat 읽기 실패: ${e.message}")
        }
        sb.appendLine()

        // 4. Full logcat for this PID (last 300 lines)
        sb.appendLine("=== FULL LOGCAT (this PID) ===")
        try {
            val pid = android.os.Process.myPid()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=$pid", "-v", "time", "-t", "300")
            )
            BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { sb.appendLine(it) }
        } catch (e: Exception) {
            sb.appendLine("logcat 읽기 실패: ${e.message}")
        }

        val text = sb.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("IntelliTimer Diagnostics", text))
        Toast.makeText(this, "클립보드에 복사됨 (${text.length} chars)", Toast.LENGTH_SHORT).show()
    }

    private fun levelColor(level: LogCollector.Level): Int = when (level) {
        LogCollector.Level.E -> Color.rgb(255, 85,  85)
        LogCollector.Level.W -> Color.rgb(255, 214, 0)
        LogCollector.Level.I -> Color.rgb(57,  255, 115)
        LogCollector.Level.D -> Color.rgb(170, 187, 204)
        LogCollector.Level.V -> Color.rgb(100, 110, 130)
    }
}
