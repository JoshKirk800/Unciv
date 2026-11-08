package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.Timers
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.time.TimeSource

/**
 * Measures what each turn of a [FullGameSim] game costs: wall time and allocation of the simulating thread, CPU time of the process,
 * garbage collections, the heap in use, and, with [spans], the time of each [Timers.timeThis] span (AI steps, city and unit turns,
 * pathfinding, ...). The game runs on that one thread, so its wall time and allocation are the game's; process CPU also counts the JIT
 * compiler and the garbage collector. Windows counts CPU time in 15.6 ms ticks (and gave the simulating thread's own CPU time as half of
 * the same work in two identical runs), so CPU is only good summed over many turns; wall time is the main measure, on an otherwise idle
 * machine. Measuring never changes the game.
 *
 * Spans nest (`automateCivMoves` contains `AI.automateUnits`, which contains `PathingMap.getShortestPath`), so their shares add up to
 * more than 100%. Timing a span costs a clock read and a lock per call, which makes the hottest spans (tile filters, conditionals)
 * noticeably slower: compare span runs with span runs, and resource runs with resource runs.
 *
 * Writes one row per turn to [turnsCsv] and, with spans, one row per turn and span to [spansCsv], for `tools/perf_compare.py`.
 */
internal class SimPerformance(private val game: GameInfo, private val spans: Boolean, private val turnsCsv: File, private val spansCsv: File?) {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val threadId = Thread.currentThread().id
    private val system = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private val memory = ManagementFactory.getMemoryMXBean()

    private class SpanTotal { var calls = 0L; var micros = 0L; var maxTurnMicros = 0L }
    private val spanTotals = HashMap<String, SpanTotal>()
    private class TurnRow(val turn: Int, val wallMs: Double, val cpuMs: Double, val allocMb: Double, val gcCount: Long, val gcMs: Long,
        val heapMb: Double, val waitMs: Long)
    private val rows = ArrayList<TurnRow>()

    private var wallStart = TimeSource.Monotonic.markNow()
    private var cpuStart = 0L
    private var allocStart = 0L
    private var gcCountStart = 0L
    private var gcTimeStart = 0L
    private var waitStart = 0L

    init {
        if (threads.isThreadContentionMonitoringSupported) threads.isThreadContentionMonitoringEnabled = true
        turnsCsv.writeText("turn,wallMs,processCpuMs,allocMB,gcCount,gcMs,heapMB,waitMs,majors,cities,units\n")
        spansCsv?.writeText("turn,span,calls,micros\n")
        if (spans) {
            Timers.singleton.startTiming()
            // startTiming does nothing when logging is off; the spans only check this field
            if (Timers.singleton.timingEnabledTimestamp == Timers.TIMING_DISABLED) Timers.singleton.timingEnabledTimestamp = TimeSource.Monotonic.markNow()
        }
    }

    private fun gcCount() = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
    private fun gcTime() = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
    /** Time the simulating thread spent blocked on a lock or waiting (sleep, I/O through a monitor, another thread), -1 if unknown */
    private fun waitTime(): Long {
        val info = threads.getThreadInfo(threadId) ?: return -1
        if (info.blockedTime < 0) return -1
        return info.blockedTime + info.waitedTime
    }

    fun beforeTurn() {
        cpuStart = system.processCpuTime
        allocStart = threads.getThreadAllocatedBytes(threadId)
        gcCountStart = gcCount()
        gcTimeStart = gcTime()
        waitStart = waitTime()
        wallStart = TimeSource.Monotonic.markNow()
    }

    fun afterTurn(turn: Int) {
        val wallMs = wallStart.elapsedNow().inWholeMicroseconds / 1000.0
        val row = TurnRow(turn, wallMs, (system.processCpuTime - cpuStart) / 1e6,
            (threads.getThreadAllocatedBytes(threadId) - allocStart) / 1048576.0, gcCount() - gcCountStart, gcTime() - gcTimeStart,
            memory.heapMemoryUsage.used / 1048576.0, waitTime() - waitStart)
        rows.add(row)
        val majors = game.civilizations.filter { it.isMajorCiv && !it.isDefeated() }
        turnsCsv.appendText(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%d,%d,%.0f,%d,%d,%d,%d\n", turn, row.wallMs, row.cpuMs, row.allocMb,
            row.gcCount, row.gcMs, row.heapMb, row.waitMs, majors.size, game.civilizations.sumOf { it.cities.size },
            game.civilizations.sumOf { it.units.getCivUnitsSize() }))
        if (!spans) return
        val lines = StringBuilder()
        val times = Timers.singleton.spanTimesInMicroseconds
        synchronized(times) {
            for ((name, list) in times) {
                if (list.isEmpty) continue
                var sum = 0L
                for (i in 0 until list.size) sum += list[i]
                val total = spanTotals.getOrPut(name) { SpanTotal() }
                total.calls += list.size
                total.micros += sum
                total.maxTurnMicros = maxOf(total.maxTurnMicros, sum)
                lines.append(turn).append(',').append(name).append(',').append(list.size).append(',').append(sum).append('\n')
                list.clear()
            }
        }
        spansCsv?.appendText(lines.toString())
    }

    /** Turns span timing off again, so that nothing after the game pays for it */
    fun close() {
        if (spans) Timers.singleton.timingEnabledTimestamp = Timers.TIMING_DISABLED
    }

    fun report(): String {
        val text = StringBuilder("## Performance\n")
        if (rows.isEmpty()) return text.append("no turns\n").toString()
        fun line(label: String, values: List<Double>) {
            val sorted = values.sorted()
            fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            text.append(String.format(Locale.ROOT, "%-22s total %10.0f  mean %8.1f  p50 %8.1f  p95 %8.1f  max %8.1f\n",
                label, values.sum(), values.average(), p(0.5), p(0.95), sorted.last()))
        }
        text.appendLine("Per turn (${Runtime.getRuntime().availableProcessors()} processors, max heap ${Runtime.getRuntime().maxMemory() / 1048576} MB; CPU is the whole process):")
        line("wall ms", rows.map { it.wallMs })
        line("process CPU ms", rows.map { it.cpuMs })
        line("allocated MB", rows.map { it.allocMb })
        line("GC ms", rows.map { it.gcMs.toDouble() })
        line("blocked+waited ms", rows.map { it.waitMs.toDouble() })
        line("heap in use MB", rows.map { it.heapMb })
        text.appendLine("By 50 turns (mean wall ms / process CPU ms / allocated MB per turn):")
        for ((bucket, inBucket) in rows.groupBy { (it.turn - 1) / 50 }) {
            text.append(String.format(Locale.ROOT, "  turns %3d-%3d  %8.1f  %8.1f  %8.1f\n", bucket * 50 + 1, bucket * 50 + 50,
                inBucket.map { it.wallMs }.average(), inBucket.map { it.cpuMs }.average(), inBucket.map { it.allocMb }.average()))
        }
        val slowest = rows.sortedByDescending { it.wallMs }.take(5)
        text.appendLine("Slowest turns: " + slowest.joinToString { String.format(Locale.ROOT, "%d (%.0f ms)", it.turn, it.wallMs) })
        if (spans) {
            val all = rows.sumOf { it.wallMs } * 1000
            text.appendLine("Spans (nested, so shares add up to more than 100%), by total time:")
            text.appendLine(String.format(Locale.ROOT, "  %-52s %10s %7s %12s %12s %12s", "span", "total ms", "share", "calls", "µs/call", "max turn ms"))
            for ((name, total) in spanTotals.entries.sortedByDescending { it.value.micros }.take(60)) {
                text.append(String.format(Locale.ROOT, "  %-52s %10.0f %6.1f%% %12d %12.1f %12.1f\n", name, total.micros / 1000.0,
                    100.0 * total.micros / all, total.calls, total.micros.toDouble() / total.calls, total.maxTurnMicros / 1000.0))
            }
        }
        return text.toString()
    }
}
