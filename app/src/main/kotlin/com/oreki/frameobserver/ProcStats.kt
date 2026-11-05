package com.oreki.frameobserver

import android.os.Debug
import android.os.Process
import java.io.BufferedReader
import java.io.FileReader
import java.util.Locale

/**
 * 进程自身的 CPU / 内存采样。
 *
 * 不引第三方 profiler：验收要求「记录 CPU、内存和传输开销」且「长时间运行不得
 * 明显内存泄漏」，只需要两个数 —— 本进程的累计 CPU 时间（`/proc/self/stat` 的
 * utime+stime，单位 jiffies）与当前 Java 堆占用。采这两个数不需要任何权限。
 *
 * CPU 用「增量 / 墙钟增量」算占用率，而不是累计值：累计值无法区分「早期忙」和
 * 「现在忙」，看不出泄漏或退化趋势。
 */
class ProcStats {

    private var lastCpuJiffies: Long = -1
    private var lastMonoNs: Long = 0
    private var cpuPct: Double = 0.0

    /** 单次采样并返回本区间 CPU 占用百分比（多核不归一，直接用 jiffies 比例）。 */
    fun sampleCpu(): Double {
        val now = System.nanoTime()
        val j = readCpuJiffies()
        var pct = cpuPct
        if (j >= 0 && lastCpuJiffies >= 0 && lastMonoNs > 0) {
            val dj = j - lastCpuJiffies
            val dn = now - lastMonoNs
            if (dn > 0) {
                // CLK_TCK 在 Android 上恒为 100，即 1 jiffy = 10ms。
                val cpuMs = dj * 10.0
                pct = cpuMs * 100.0 / (dn / 1_000_000.0)
            }
        }
        lastCpuJiffies = j
        lastMonoNs = now
        cpuPct = pct
        return pct
    }

    companion object {
        @JvmStatic
        fun javaHeapBytes(): Long {
            val r = Runtime.getRuntime()
            return r.totalMemory() - r.freeMemory()
        }

        @JvmStatic
        fun nativeHeapBytes(): Long {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            return mi.totalPss * 1024L
        }

        @JvmStatic
        fun human(): String = String.format(
            Locale.US,
            "pid=%d javaHeap=%.1fMB pss=%.1fMB threads=%d",
            Process.myPid(), javaHeapBytes() / 1048576.0, nativeHeapBytes() / 1048576.0,
            Thread.activeCount(),
        )

        private fun readCpuJiffies(): Long {
            // /proc/self/stat 的字段 14/15 是 utime/stime（1-based），但 comm 字段可能含空格，
            // 所以必须从最后一个 ')' 之后开始数，不能简单 split(" ")。
            try {
                BufferedReader(FileReader("/proc/self/stat")).use { br ->
                    val line = br.readLine() ?: return -1
                    val rp = line.lastIndexOf(')')
                    if (rp < 0 || rp + 2 >= line.length) return -1
                    val rest = line.substring(rp + 2).split(" ")
                    // rest[0] = state(字段3)，故 utime(14) = rest[11]，stime(15) = rest[12]
                    if (rest.size < 13) return -1
                    return rest[11].toLong() + rest[12].toLong()
                }
            } catch (_: Exception) {
                return -1
            }
        }
    }
}
