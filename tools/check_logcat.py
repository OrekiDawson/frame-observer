"""R74：frame-observer 的 logcat 诊断模块自测。

为什么需要：这套能力是用来「在真机/AVD 上回答『谁改了这个值』」的，
一旦它自身有静默缺陷（比如采集为空、时间戳错年份、JSON 多包一层），
排查者会得到**错误结论**而不是报错。所以每次改完都必须跑一遍这个脚本。

用法：
    python tools/check_logcat.py              # 默认 127.0.0.1:8731
    python tools/check_logcat.py --port 8731 --device emulator-5554

前置：
    1. 已安装并启动 OrekiFrameObserver
    2. `adb forward tcp:8731 tcp:8731`
    3. 已 `pm grant ... READ_LOGS`，且首次 start 时点了系统的
       “Allow one-time access”（否则采集会 running=true 但 buffered=0）

判定：全部通过退出码 0，否则 1。
"""

import argparse
import datetime
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_PORT = 8731


class Client:
    def __init__(self, host: str, port: int):
        self.base = f"http://{host}:{port}"

    def get(self, path: str, timeout: float = 10.0) -> str:
        with urllib.request.urlopen(self.base + path, timeout=timeout) as r:
            return r.read().decode("utf-8", "replace")

    def post(self, path: str, timeout: float = 10.0) -> str:
        req = urllib.request.Request(self.base + path, method="POST")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode("utf-8", "replace")

    def stats(self) -> dict:
        return json.loads(self.get("/logcat/stats"))

    def lines(self, limit: int = 50, kw: str = "") -> list:
        q = f"?limit={limit}" + (f"&kw={urllib.parse.quote(kw)}" if kw else "")
        body = self.get("/logcat" + q)
        out = []
        for line in body.splitlines():
            line = line.strip()
            if line:
                out.append(json.loads(line))
        return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--device", default=None, help="用于断连测试的 adb 设备，如 emulator-5554")
    args = ap.parse_args()

    c = Client(args.host, args.port)
    checks = 0
    fails = []

    def check(name, ok, detail=""):
        nonlocal checks
        checks += 1
        if not ok:
            fails.append(f"{name}  ← {detail}" if detail else name)

    # ---------- 0. 服务可达 ----------
    try:
        st = json.loads(c.get("/stats"))
        check("服务可达 /stats", True)
    except Exception as e:
        print(f"[FAIL] 连不上 frame-observer：{e}")
        print("       先 `adb forward tcp:8731 tcp:8731` 并启动 app。")
        return 1

    # ---------- 1. 启动采集 ----------
    c.post("/logcat/stop")
    time.sleep(0.3)
    c.post("/logcat/clear")
    r = json.loads(c.post("/logcat/start"))
    check("POST /logcat/start 返回 ok", r.get("ok") is True, str(r))
    time.sleep(5)

    st = c.stats()
    check("采集进程在跑 running=true", st.get("running") is True, str(st))
    check("子进程存活 alive=true", st.get("alive") is True, str(st))
    check("缓冲有数据 buffered>0", st.get("buffered", 0) > 0,
          f"buffered={st.get('buffered')}（0 多半是没点系统的 Allow one-time access）")

    # ---------- 2. 采样与时间戳合理性 ----------
    lines = c.lines(limit=20)
    check("能取到日志行", len(lines) > 0, f"lines={len(lines)}")
    if lines:
        now = time.time() * 1000
        worst = 0
        for l in lines:
            worst = max(worst, abs(now - l["wall_ms"]))
        # 允许 10 分钟偏差（设备时区/时钟可能与本机不同，这里只抓「1970 年」这类明显错误）
        check("wall_ms 不是 1970 年", worst < 1000 * 60 * 60,
              f"最大偏差 {worst/1000:.0f}s，多半是没补年份")
        check("字段齐全", all(k in lines[0] for k in
                              ("seq", "wall_ms", "level", "tag", "pid", "text")),
              str(sorted(lines[0].keys())))

    # ---------- 3. 等级过滤 ----------
    c.post("/logcat/level?min=4")
    time.sleep(0.3)
    errs = c.lines(limit=20)
    check("等级过滤只返回 E 及以上", all(x["level"] in ("E", "F") for x in errs),
          str([x["level"] for x in errs[:5]]))
    c.post("/logcat/level?min=0")

    # ---------- 4. Tag 过滤 ----------
    c.post("/logcat/filter?tag=OREKI")
    time.sleep(0.3)
    tagged = c.lines(limit=20)
    check("Tag 过滤生效", all("OREKI" in x["tag"] for x in tagged),
          str([x["tag"] for x in tagged[:5]]))
    c.post("/logcat/filter?tag=")

    # ---------- 5. 关键词过滤（空串清位，不应该把 tag 也清掉）----------
    c.post("/logcat/filter?tag=OREKI")
    c.post("/logcat/filter?kw=OREKI")      # 只改 kw，tag 保持
    time.sleep(0.3)
    both = c.lines(limit=20)
    check("只改 kw 时 tag 过滤保留", all("OREKI" in x["tag"] for x in both),
          "kw 那次请求把 tag 冲掉了")
    check("关键词命中", all("OREKI" in x["text"] for x in both),
          str([x["text"][:20] for x in both[:3]]))
    c.post("/logcat/filter?tag=&kw=")

    # ---------- 6. 暂停 / 继续 ----------
    c.post("/logcat/pause?on=1")
    s1 = c.stats()["last_seq"]
    time.sleep(2.0)
    s2 = c.stats()["last_seq"]
    c.post("/logcat/pause?on=0")
    check("暂停期间序号不增长", s1 == s2, f"{s1} -> {s2}")
    time.sleep(2.0)
    s3 = c.stats()["last_seq"]
    check("继续后序号恢复增长", s3 > s2, f"{s2} -> {s3}")

    # ---------- 7. 清空 ----------
    # 先暂停再清空：不停的话每秒都有新日志进来，`buffered==0` 会在
    # 「清空」与「下一次断言」之间被新数据打破（R74 第一次跑就误报成失败）。
    c.post("/logcat/pause?on=1")
    c.post("/logcat/clear")
    time.sleep(0.2)
    check("清空后缓冲归零", c.stats()["buffered"] == 0, str(c.stats()["buffered"]))
    c.post("/logcat/pause?on=0")
    time.sleep(2)

    # ---------- 8. 导出 ----------
    txt = c.get("/logcat/export?jsonl=0&limit=5")
    check("文本导出非空", len(txt.strip()) > 0)
    jsonl = c.get("/logcat/export?jsonl=1&limit=5")
    ok_json = True
    try:
        for line in jsonl.splitlines():
            if line.strip():
                json.loads(line)
    except Exception:
        ok_json = False
    check("JSONL 导出的每一行都能解析", ok_json)

    # ---------- 9. 诊断规则 ----------
    c.post("/logcat/rule/remove?session=selftest")
    r = json.loads(c.post("/logcat/rule?cls=Selftest&method=m&session=selftest"))
    check("注册诊断规则", r.get("ok") is True, str(r))
    rules = json.loads(c.get("/logcat/rule"))
    check("规则结构是裸字符串（不是 {v:...} 包装）",
          any(x.get("cls") == "Selftest" for x in rules.get("rules", [])),
          str(rules))
    n = json.loads(c.post("/logcat/rule/remove?session=selftest")).get("removed")
    check("移除诊断规则", n == 1, f"removed={n}")

    # ---------- 10. timeline ----------
    tl = json.loads(c.get("/timeline?before=60000&after=60000"))
    check("timeline 返回 from/to/logs", all(k in tl for k in ("from", "to", "logs")), str(tl.keys()))

    # ---------- 11. 截图不受影响 ----------
    # 注意：重装 app 后**投影授权会失效**（capturing=false），此时 /snapshot.jpg 返 503
    # 是正确行为，不是缺陷。这里把它区分为「未授权」而不是失败，
    # 但仍强制要求：未授权时 /stats 必须如实报告 capturing=false（不能假装成功）。
    capturing = json.loads(c.get("/stats")).get("capturing")
    t0 = time.time()
    try:
        with urllib.request.urlopen(c.base + "/snapshot.jpg", timeout=15) as r:
            n_bytes = len(r.read())
        dt = (time.time() - t0) * 1000
        check("采集期间 /snapshot.jpg 正常", n_bytes > 1000, f"{n_bytes} bytes")
        check("截图响应 < 3s", dt < 3000, f"{dt:.0f} ms")
    except urllib.error.HTTPError as e:
        check("未授权投影时 /snapshot.jpg 返 503 且 capturing=false",
              e.code == 503 and capturing is False,
              f"code={e.code} capturing={capturing}")
        print("  [跳过] 截图响应：投影未授权（需在设备上点『开始捕获』并确认授权）")
    except Exception as e:
        check("采集期间 /snapshot.jpg 正常", False, str(e))

    # ---------- 12. 断连检测（可选）----------
    if args.device:
        st = c.stats()
        check("断连前 alive=true", st.get("alive") is True, str(st))
        try:
            # Windows 上 `adb` 常不在 PATH，优先用项目自带的 platform-tools
            adb = "adb"
            local = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                 "..", "android", "AD_Oreki_Edition_Android", "tools",
                                 "platform-tools", "adb.exe")
            if os.path.exists(local):
                adb = local
            out = subprocess.run(
                [adb, "-s", args.device, "shell", "pidof", "logcat"],
                capture_output=True, text=True, timeout=10).stdout.split()
            if out:
                subprocess.run([adb, "-s", args.device, "shell", "kill", "-9", out[-1]],
                               capture_output=True, timeout=10)
                time.sleep(3)
                st2 = c.stats()
                check("杀掉子进程后 alive=false", st2.get("alive") is False, str(st2))
                r = json.loads(c.post("/logcat/start"))
                check("可重新 start 恢复", r.get("ok") is True, str(r))
            else:
                check("断连测试：找到 logcat 子进程", False, "pidof logcat 为空")
        except Exception as e:
            check("断连测试", False, str(e))

    c.post("/logcat/stop")

    print(f"\nframe-observer logcat 自测：{checks} 项，失败 {len(fails)} 项")
    if fails:
        for f in fails:
            print("  FAIL", f)
        return 1
    print("  全部通过")
    return 0


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (放在末尾避免未使用告警误报)
    sys.exit(main())
