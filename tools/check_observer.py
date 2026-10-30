#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Oreki Frame Observer —— 第一阶段验收自检

为什么要有这个脚本
------------------
人工「看一眼觉得对」不能作为验收依据。本脚本把用户需求里的每条验收标准
变成一条可失败的断言，任何一条不过就以非零退出码结束。

特别针对两类已经踩过的坑：
  1. 控件树不是合法 JSON（"children" 曾被写在对象闭合括号之外）——
     早期所有树都无法被下游解析，而肉眼看日志原文是「看着有」的，很容易误判。
  2. 顶栏状态是 SpannableStringBuilder 渲染的，文本可能被 span 拆成多个节点，
     等值匹配会得到假阴性。所以这里做「跨兄弟节点拼接 + 子串匹配」。

用法
----
    python tools/check_observer.py            # 默认连 127.0.0.1:8731
    python tools/check_observer.py --port 8731 --json out.json
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time
import urllib.request

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  —— " + detail) if detail else ""), flush=True)
    return ok


def get(base, path, timeout=6):
    with urllib.request.urlopen(base + path, timeout=timeout) as r:
        return r.status, dict(r.headers), r.read()


def collect_text(node, out):
    """收集整棵树的全部文本，并把父子路径拼起来（应对 span 拆分）。"""
    if not isinstance(node, dict):
        return
    t = node.get("text")
    d = node.get("desc")
    if t:
        out.append(t)
    if d:
        out.append(d)
    for c in node.get("children") or []:
        collect_text(c, out)


def walk_nodes(node, fn, path=""):
    """遍历每个节点，回调里可拿到 (node, 累积父亲文本)。"""
    if not isinstance(node, dict):
        return
    fn(node, path)
    own = node.get("text") or ""
    for c in node.get("children") or []:
        walk_nodes(c, fn, path + own)


def validate_tree(node, depth=0):
    """结构校验：每个节点必须有 children 且为 list。返回 (ok, 错误列表)。"""
    errs = []
    if not isinstance(node, dict):
        return False, ["节点不是 dict：%r" % type(node)]
    if "children" not in node:
        errs.append("节点缺少 children 字段：%s" % (node.get("id") or node.get("cls")))
    elif not isinstance(node["children"], list):
        errs.append("children 不是 list：%s -> %r" % (node.get("id"), type(node["children"])))
    else:
        for c in node["children"]:
            ok, e = validate_tree(c, depth + 1)
            errs.extend(e)
    return not errs, errs


def ws_probe(host, port, want=2, timeout=8):
    """最小 WebSocket 客户端：只握手 + 收 want 个帧。"""
    s = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(("GET /ws HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
               "Sec-WebSocket-Version: 13\r\n\r\n" % (host, port, key)).encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    head = buf.split(b"\r\n\r\n")[0].decode("utf-8", "replace")
    if "101" not in head.split("\r\n")[0]:
        s.close()
        return False, "握手未返回 101"
    expect = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    ).decode()
    if expect not in head:
        s.close()
        return False, "Sec-WebSocket-Accept 不正确"

    frames = []

    def read_exact(n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise IOError("eof")
            b += c
        return b

    t0 = time.time()
    try:
        while len(frames) < want and time.time() - t0 < timeout:
            hdr = read_exact(2)
            op = hdr[0] & 0x0F
            ln = hdr[1] & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", read_exact(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", read_exact(8))[0]
            frames.append((op, ln))
            read_exact(ln)
    except Exception as e:
        s.close()
        return False, "读帧失败：%r（已收 %d 帧）" % (e, len(frames))
    s.close()
    return any(op == 2 for op, _ in frames), "收到 %r" % (frames,)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8731)
    ap.add_argument("--json", default=None)
    args = ap.parse_args()
    base = "http://%s:%d" % (args.host, args.port)

    print("[1] HTTP 端点", flush=True)
    try:
        st, hdr, body = get(base, "/stats")
    except Exception as e:
        check("GET /stats 可达", False, repr(e))
        summarize(args.json)
        return
    stats = json.loads(body.decode("utf-8"))
    check("GET /stats 返回 JSON", isinstance(stats, dict), "keys=%d" % len(stats))
    check("GET / 状态页", get(base, "/")[0] == 200)

    st2, h2, jpg = get(base, "/snapshot.jpg")
    ok_jpeg = st2 == 200 and jpg[:3] == b"\xff\xd8\xff" and jpg[-2:] == b"\xff\xd9"
    check("GET /snapshot.jpg 是完整 JPEG", ok_jpeg,
          "status=%s bytes=%d head=%r" % (st2, len(jpg), jpg[:3]))
    fid = h2.get("X-Frame-Id")
    check("响应头带 X-Frame-Id", fid is not None, "frame_id=%s" % fid)

    # 路径穿越必须被拒
    try:
        st3, _, _ = get(base, "/frames/..%2f..%2f..%2fetc%2fpasswd")
    except urllib.error.HTTPError as e:
        st3 = e.code
    check("路径穿越被拒绝", st3 in (400, 403, 404), "status=%s" % st3)

    print("[2] 统计口径", flush=True)
    check("目标与实际 FPS 分开报告", "target_fps" in stats and "actual_fps" in stats,
          "target=%s actual=%.1f" % (stats.get("target_fps"), stats.get("actual_fps", 0)))
    check("丢帧有独立计数", "frames_dropped" in stats and "drop_rate_pct" in stats,
          "dropped=%s rate=%.2f%%" % (stats.get("frames_dropped"), stats.get("drop_rate_pct", 0)))
    check("采集延迟已记录", "capture_latency_ms" in stats and "capture_latency_ema_ms" in stats,
          "ema=%.2fms" % stats.get("capture_latency_ema_ms", 0))
    check("采集在运行", stats.get("capturing") is True, "state=%s" %
          (stats.get("state", {}).get("v") if isinstance(stats.get("state"), dict) else stats.get("state")))

    print("[3] 指定时间点回放", flush=True)
    ts = stats.get("t_mono_ns")
    if ts:
        try:
            st4, _, body4 = get(base, "/frame_at?t=%d" % ts)
            d4 = json.loads(body4.decode("utf-8"))
            check("/frame_at 命中一帧", st4 == 200 and "frame_id" in d4,
                  "frame_id=%s delta=%.1fms" % (d4.get("frame_id"), d4.get("delta_ms", 0)))
            st5, _, raw = get(base, "/frame_at?t=%d&raw=1" % ts)
            check("/frame_at&raw=1 直出 JPEG", st5 == 200 and raw[:3] == b"\xff\xd8\xff",
                  "bytes=%d" % len(raw))
        except Exception as e:
            check("/frame_at 可用", False, repr(e))
    else:
        check("/frame_at 可用", False, "stats 里没有 t_mono_ns")

    print("[4] WebSocket", flush=True)
    try:
        ok_ws, detail = ws_probe(args.host, args.port, want=2)
    except Exception as e:
        ok_ws, detail = False, repr(e)
    check("WS 握手 + 收到二进制帧", ok_ws, detail)

    print("[5] 控件树格式（本脚本存在的首要理由）", flush=True)
    try:
        st6, _, body6 = get(base, "/events?since=0")
        lines = [l for l in body6.decode("utf-8").splitlines() if l.strip()]
    except Exception as e:
        lines = []
        check("GET /events 可达", False, repr(e))
    trees = []
    for l in lines:
        try:
            d = json.loads(l)
        except Exception:
            continue
        if d.get("event_type") == "view_tree_snapshot" and d.get("tree"):
            trees.append(d)
    check("有控件树快照可校验", len(trees) > 0, "共 %d 份" % len(trees))

    all_ok = True
    worst = ""
    for d in trees[-3:]:
        try:
            t = json.loads(d["tree"])          # 断言 1：树本身必须是合法 JSON
        except Exception as e:
            all_ok = False
            worst = "tree 不是合法 JSON：%r" % e
            continue
        ok, errs = validate_tree(t)            # 断言 2：每个节点都有 children 且是 list
        if not ok:
            all_ok = False
            worst = "; ".join(errs[:3])
    check("每份控件树是合法 JSON 且 children 均为 list", all_ok, worst or "抽查 %d 份" % min(3, len(trees)))

    print("[6] 顶栏状态判据（跨节点拼接 + 子串匹配）", flush=True)
    HINTS = ["你现在在", "永恒挑战", "反物质宇宙", "已完成"]
    found = {}
    for d in trees:
        try:
            t = json.loads(d["tree"])
        except Exception:
            continue
        texts = []
        collect_text(t, texts)
        joined = "".join(texts)
        for h in HINTS:
            if h in joined:
                found.setdefault(h, joined[:120])
    check("控件树里能读到顶栏/挑战文本（子串匹配）", len(found) > 0,
          "命中 %s" % (list(found.keys()) or "无"))
    for k, v in found.items():
        print("        「%s」→ …%s…" % (k, v))

    summarize(args.json)


def summarize(json_out):
    total = len(RESULTS)
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print("\n===== 结论：%d/%d 通过 =====" % (passed, total), flush=True)
    if passed != total:
        for name, ok, detail in RESULTS:
            if not ok:
                print("  未通过：%s  %s" % (name, detail))
    if json_out:
        with open(json_out, "w", encoding="utf-8") as f:
            json.dump([{"name": n, "ok": o, "detail": d} for n, o, d in RESULTS],
                      f, ensure_ascii=False, indent=1)
    sys.exit(0 if passed == total else 2)


if __name__ == "__main__":
    main()
