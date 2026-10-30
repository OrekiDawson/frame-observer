package com.oreki.frameobserver;

import android.os.Debug;
import android.os.Process;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * 进程自身的 CPU / 内存采样。
 *
 * <p>为什么不引第三方 profiler：验收要求「记录 CPU、内存和传输开销」且「长时间运行不得
 * 明显内存泄漏」，只需要两个数就够了 —— 本进程的累计 CPU 时间（/proc/self/stat 的
 * utime+stime，单位 jiffies）与当前 Java 堆占用。采这两个数不需要任何权限。
 *
 * <p>CPU 用「增量 / 墙钟增量」算占用率，而不是累计值，因为累计值无法区分「早期忙」和
 * 「现在忙」，看不出泄漏或退化趋势。
 */
public final class ProcStats {

    private long lastCpuJiffies = -1;
    private long lastMonoNs = 0;
    private double cpuPct = 0;

    /** 单次采样并返回本区间 CPU 占用百分比（多核不归一，直接用 jiffies 比例）。 */
    public double sampleCpu() {
        long now = System.nanoTime();
        long j = readCpuJiffies();
        double pct = cpuPct;
        if (j >= 0 && lastCpuJiffies >= 0 && lastMonoNs > 0) {
            long dj = j - lastCpuJiffies;
            long dn = now - lastMonoNs;
            if (dn > 0) {
                // CLK_TCK 在 Android 上恒为 100，即 1 jiffy = 10ms。
                double cpuMs = dj * 10.0;
                pct = cpuMs * 100.0 / (dn / 1_000_000.0);
            }
        }
        lastCpuJiffies = j;
        lastMonoNs = now;
        cpuPct = pct;
        return pct;
    }

    public static long javaHeapBytes() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    public static long nativeHeapBytes() {
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        return mi.getTotalPss() * 1024L;
    }

    public static String human() {
        return String.format(Locale.US, "pid=%d javaHeap=%.1fMB pss=%.1fMB threads=%d",
                Process.myPid(), javaHeapBytes() / 1048576d, nativeHeapBytes() / 1048576d,
                Thread.activeCount());
    }

    private static long readCpuJiffies() {
        // /proc/self/stat 的字段 14/15 是 utime/stime（1-based），但 comm 字段可能含空格，
        // 所以必须从最后一个 ')' 之后开始数，不能简单 split(" ")。
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = br.readLine();
            if (line == null) {
                return -1;
            }
            int rp = line.lastIndexOf(')');
            if (rp < 0 || rp + 2 >= line.length()) {
                return -1;
            }
            String[] rest = line.substring(rp + 2).split(" ");
            // rest[0] = state(字段3)，故 utime(14) = rest[11]，stime(15) = rest[12]
            if (rest.length < 13) {
                return -1;
            }
            return Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
        } catch (Exception e) {
            return -1;
        }
    }
}
