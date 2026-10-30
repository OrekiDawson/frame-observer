#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Oreki Frame Observer —— 30 分钟 soak 测试采样器

验收标准对应：
    连续运行 30 分钟无崩溃、无明显内存泄漏。

方法
----
每 60 秒采一轮（共 31 轮，含 t=0 基线）：
    /stats        —— 实际 FPS、丢帧、延迟、java heap、pss、线程数
    adb pidof     —— 观察者与游戏进程是否存活
    logcat 增量   —— 有无 FATAL / AndroidRuntime 崩溃

判定
----
    崩溃判定：任一轮观察者进程消失，或 logcat 出现 FATAL。
    泄漏判定：java_heap 末值 / 首值 > 1.5，或 pss 单调上升且末值比首值高 50%。
    （阈值偏宽是刻意的：模拟器 GC 与 LMK 波动大，过严会误报。）
"""

import json
import subprocess
import sys
import time
import urllib.request

ADB = r"C:\Users\Oreki\WorkBuddy\AD-Oreki-Unified\android\AD_Oreki_Edition_Android\tools\platform-tools\adb.exe"
SERIAL = "emulator-5554"
URL = "http://127.0.0.1:8731/stats"
ROUNDS = 31
INTERVAL = 60
OUT = r"C:\Users\Oreki\WorkBuddy\AD-Oreki-Unified\frame-observer\_buildlog\soak_samples.jsonl"

LOGCAT_MARK = "soak-start-marker"


def adb(*args, check=True):
    r = subprocess.run([ADB, "-s", SERIAL] + list(args),
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode("utf-8", "replace").strip()
    if check and r.returncode != 0:
        print("[soak] adb 失败: %s -> %s" % (args, out[:200]))
    return out


def main():
    subprocess.run([ADB, "-s", SERIAL, "forward", "tcp:8731", "tcp:8731"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # 打标记，之后 logcat 只取标记之后的增量
    adb("logcat", "-c")
    samples = []
    print("[soak] 开始，共 %d 轮 × %ds" % (ROUNDS, INTERVAL), flush=True)

    fatal_seen = 0
    for i in range(ROUNDS):
        t0 = time.time()
        rec = {"round": i, "t": time.strftime("%H:%M:%S")}
        try:
            with urllib.request.urlopen(URL, timeout=5) as resp:
                rec["stats"] = json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            rec["stats_error"] = repr(e)
        rec["observer_pid"] = adb("shell", "pidof", "com.oreki.frameobserver", check=False) or None
        rec["game_pid"] = adb("shell", "pidof", "com.oreki.antimatterdimensions", check=False) or None
        lc = adb("logcat", "-d", "-s", "AndroidRuntime:E", "OrekiCapture:I", "OrekiHttp:W",
                 "OrekiA11y:E", check=False)
        rec["fatal_lines"] = [l for l in lc.splitlines() if "FATAL" in l or "Process:" in l and "died" in l]
        fatal_seen += len(rec["fatal_lines"])

        if rec["stats"]:
            s = rec["stats"]
            print("[soak] r%02d fps=%.1f processed=%d dropped=%d heap=%.1fMB pss=%.1fMB threads=%d obs=%s game=%s fatal=%d"
                  % (i, s.get("actual_fps", 0), s.get("frames_processed", 0), s.get("frames_dropped", 0),
                     s["proc"]["java_heap_mb"], s["proc"]["pss_mb"], s["proc"]["threads"],
                     bool(rec["observer_pid"]), bool(rec["game_pid"]), len(rec["fatal_lines"])),
                  flush=True)
        else:
            print("[soak] r%02d stats 不可用 obs=%s game=%s" % (i, rec["observer_pid"], rec["game_pid"]), flush=True)

        samples.append(rec)
        with open(OUT, "w", encoding="utf-8") as f:
            json.dump(samples, f, ensure_ascii=False, indent=1)
        wait = INTERVAL - (time.time() - t0)
        if wait > 0 and i < ROUNDS - 1:
            time.sleep(wait)

    # ------------------------------------------------ 汇总判定
    ok = samples[0].get("stats") or {}
    last = samples[-1].get("stats") or {}
    crashes = []
    for r in samples:
        if not r.get("observer_pid"):
            crashes.append("r%02d 观察者进程消失" % r["round"])
        if r.get("fatal_lines"):
            crashes.append("r%02d logcat 异常: %s" % (r["round"], r["fatal_lines"][:2]))

    heap0 = ok.get("proc", {}).get("java_heap_mb", 0)
    heap1 = last.get("proc", {}).get("java_heap_mb", 0)
    pss0 = ok.get("proc", {}).get("pss_mb", 0)
    pss1 = last.get("proc", {}).get("pss_mb", 0)
    leak = (heap0 > 0 and heap1 / heap0 > 1.5) or (pss0 > 0 and pss1 / pss0 > 1.5)

    print("\n[soak] ===== 结论 =====")
    print("[soak] 崩溃: %s" % ("无" if not crashes else "; ".join(crashes)))
    print("[soak] 内存: java_heap %.1f -> %.1f MB, pss %.1f -> %.1f MB -> %s"
          % (heap0, heap1, pss0, pss1, "疑似泄漏" if leak else "无明显泄漏"))
    print("[soak] 累计帧: %d, 丢帧: %d, 实际FPS(末值): %.1f"
          % (last.get("frames_processed", 0), last.get("frames_dropped", 0), last.get("actual_fps", 0)))
    print("[soak] PASS" if not crashes and not leak else "[soak] FAIL")
    sys.exit(0 if not crashes and not leak else 2)


if __name__ == "__main__":
    main()
