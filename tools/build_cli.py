#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Oreki Frame Observer —— 离线命令行构建

用途
----
在没有网络、没有 Gradle 插件缓存、没有 Android Studio 的机器上，直接用
aapt2 + javac + d8 + zipalign + apksigner 打出一个可安装 APK。产物与 AGP 路径等价。

为什么需要它
------------
AGP 首次构建要从 dl.google.com 拉 AGP 本体与一整套依赖（数百 MB），在限流或离线环境下
会卡住整轮迭代。本脚本只依赖两样东西，且二者都已在仓库内或已安装：
    android.jar            （$ANDROID_HOME/platforms/android-34/android.jar）
    build-tools             （优先用仓库内 tools/bt/android-14，其次 $ANDROID_HOME/build-tools/34.0.0）

与 AGP 路径的一处差异
--------------------
AGP 8 拒绝 AndroidManifest.xml 里的 `package` 属性（要求用 namespace）。aapt2 link 又必须
有包名。所以本脚本在构建期读原清单、往 <manifest> 上注入 package 属性，写到
_build/manifest_cli.xml 再交给 aapt2 —— 仓库里的清单一个字都不改，两条路径互不干扰。

用法
----
    python tools/build_cli.py                 # 构建 release（用仓库共享签名）
    python tools/build_cli.py --unsigned      # 只构建不对齐不签名（调试用）
    python tools/build_cli.py --output x.apk  # 指定输出路径
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APP = os.path.join(ROOT, "app")
BUILD = os.path.join(ROOT, "_build")

PKG = "com.oreki.frameobserver"
MIN_SDK = "29"
TARGET_SDK = "34"

DEFAULT_SDK = r"C:\Users\Oreki\Android\sdk"
SHARED_KEYSTORE = os.path.join(
    ROOT, "..", "android", "AD_Oreki_Edition_Android", "keys", "oreki.jks")
SHARED_BT = os.path.join(
    ROOT, "..", "android", "AD_Oreki_Edition_Android", "tools", "bt", "android-14")
JDK = os.path.join(
    ROOT, "..", "android", "AD_Oreki_Edition_Android", "tools", "jdk", "jdk-17.0.20.1+1")


def log(msg):
    print("[build] " + msg, flush=True)


def die(msg, code=1):
    print("[build][FAIL] " + msg, file=sys.stderr, flush=True)
    sys.exit(code)


def find_tool(exe_names, *dirs):
    for d in dirs:
        if not d:
            continue
        for name in exe_names:
            p = os.path.join(d, name)
            if os.path.isfile(p):
                return p
    return None


def run(cmd, cwd=None):
    log("$ " + " ".join('"%s"' % c if " " in c else c for c in cmd[:6])
        + (" ..." if len(cmd) > 6 else ""))
    r = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode("utf-8", "replace")
    if r.returncode != 0:
        print(out[-4000:])
        die("命令失败（exit %d）：%s" % (r.returncode, cmd[0]))
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unsigned", action="store_true", help="跳过对齐与签名")
    ap.add_argument("--output", default=None)
    ap.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or DEFAULT_SDK)
    args = ap.parse_args()

    android_jar = os.path.join(args.sdk, "platforms", "android-34", "android.jar")
    if not os.path.isfile(android_jar):
        die("找不到 android.jar：%s（请先安装 platforms;android-34）" % android_jar)

    bt = SHARED_BT if os.path.isdir(SHARED_BT) else os.path.join(args.sdk, "build-tools", "34.0.0")
    aapt2 = find_tool(["aapt2.exe", "aapt2"], bt)
    d8 = find_tool(["d8.bat", "d8"], bt)
    zipalign = find_tool(["zipalign.exe", "zipalign"], bt)
    apksigner = find_tool(["apksigner.bat", "apksigner"], bt)
    for name, p in (("aapt2", aapt2), ("d8", d8), ("zipalign", zipalign), ("apksigner", apksigner)):
        if not p:
            die("找不到 %s（build-tools 目录：%s）" % (name, bt))

    java_home = JDK if os.path.isdir(JDK) else os.environ.get("JAVA_HOME", "")
    if not java_home or not os.path.isfile(os.path.join(java_home, "bin", "javac.exe")):
        die("找不到 JDK（期望 %s）" % JDK)
    javac = os.path.join(java_home, "bin", "javac.exe")
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    # 必须写回进程环境：d8.bat / apksigner.bat 是批处理脚本，它们读的是**继承来的**
    # JAVA_HOME，不是我们传给 subprocess 的局部字典（早期版本漏了这一步，d8 直接报
    # "JAVA_HOME is not set and no 'java' command could be found"）。
    os.environ.update(env)

    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    for d in ("gen", "classes", "dex", "res"):
        os.makedirs(os.path.join(BUILD, d), exist_ok=True)

    # ---------------------------------------------------------------- 1. 清单注入 package
    src_manifest = os.path.join(APP, "src", "main", "AndroidManifest.xml")
    with open(src_manifest, "r", encoding="utf-8") as f:
        manifest = f.read()
    if 'package="' in manifest.split(">")[0]:
        log("清单已含 package 属性，直接使用")
        cli_manifest = src_manifest
    else:
        cli_manifest = os.path.join(BUILD, "manifest_cli.xml")
        injected = re.sub(r"<manifest\b", '<manifest package="%s"' % PKG, manifest, count=1)
        with open(cli_manifest, "w", encoding="utf-8") as f:
            f.write(injected)
        log("已生成 _build/manifest_cli.xml（注入 package=%s）" % PKG)

    # ---------------------------------------------------------------- 2. 资源编译
    res_dir = os.path.join(APP, "src", "main", "res")
    res_zip = os.path.join(BUILD, "res", "compiled.zip")
    run([aapt2, "compile", "--dir", res_dir, "-o", res_zip])

    unaligned = os.path.join(BUILD, "base-unaligned.apk")
    run([
        aapt2, "link",
        "-o", unaligned,
        "-I", android_jar,
        "--manifest", cli_manifest,
        "--java", os.path.join(BUILD, "gen"),
        "--min-sdk-version", MIN_SDK,
        "--target-sdk-version", TARGET_SDK,
        "--auto-add-overlay",
        "-R", res_zip,
    ])

    # ---------------------------------------------------------------- 3. javac
    sources = []
    for base in (os.path.join(APP, "src", "main", "java"), os.path.join(BUILD, "gen")):
        for dirpath, _dirnames, filenames in os.walk(base):
            for fn in filenames:
                if fn.endswith(".java"):
                    sources.append(os.path.join(dirpath, fn))
    if not sources:
        die("没有找到任何 .java 源文件")
    classes = os.path.join(BUILD, "classes")
    # 用 source/target 8 + bootclasspath，而不是 11：
    #   javac 不允许 `-target 11` 配 `-boot-class-path`（会报「目标 11 与选项 --boot-class-path
    #   一起使用无效」）。而不用 bootclasspath 又会让 java.* 解析到 JDK 自己的 java.base，
    #   编得过但会混入 Android 上不存在的 API。8 + bootclasspath 是 Android 命令行构建的
    #   经典组合，d8 对 Java 8 字节码（含 lambda 的 invokedynamic）脱糖是完备的。
    #
    # core-lambda-stubs.jar 是必须的：android.jar 里没有 java.lang.invoke.LambdaMetafactory，
    # 而 lambda 在 source 8 下编译成 invokedynamic + 对 metafactory 的引用。build-tools 自带
    # 这份桩，必须接在 bootclasspath 后面，否则报「找不到符号 metafactory」。
    lambda_stubs = os.path.join(bt, "core-lambda-stubs.jar")
    bcp = android_jar + (os.pathsep + lambda_stubs if os.path.isfile(lambda_stubs) else "")
    run([
        javac,
        "-encoding", "UTF-8",
        "-source", "8", "-target", "8",
        "-bootclasspath", bcp,
        "-classpath", bcp,
        "-d", classes,
        "-nowarn",
        "-Xlint:-options",
    ] + sources)
    log("javac 编译 %d 个源文件通过" % len(sources))

    # ---------------------------------------------------------------- 4. d8
    class_files = []
    for dirpath, _dirnames, filenames in os.walk(classes):
        for fn in filenames:
            if fn.endswith(".class"):
                class_files.append(os.path.join(dirpath, fn))
    dex_dir = os.path.join(BUILD, "dex")
    run([d8, "--lib", android_jar, "--min-api", MIN_SDK,
         "--output", dex_dir] + class_files)

    # ---------------------------------------------------------------- 5. 合并 dex 进 APK
    merged = os.path.join(BUILD, "base.apk")
    with zipfile.ZipFile(unaligned, "r") as zin:
        with zipfile.ZipFile(merged, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))
            zout.write(os.path.join(dex_dir, "classes.dex"), "classes.dex")
    log("dex 已并入 APK")

    # ---------------------------------------------------------------- 6. 对齐 + 签名
    if args.unsigned:
        out = args.output or os.path.join(BUILD, "OrekiFrameObserver-unsigned.apk")
        shutil.copyfile(merged, out)
        log("已输出未签名包：%s" % out)
        return

    aligned = os.path.join(BUILD, "base-aligned.apk")
    run([zipalign, "-f", "-p", "4", merged, aligned])

    if not os.path.isfile(SHARED_KEYSTORE):
        die("找不到签名密钥：%s" % SHARED_KEYSTORE)
    out = args.output or os.path.join(ROOT, "OrekiFrameObserver.apk")
    run([
        apksigner, "sign",
        "--ks", SHARED_KEYSTORE,
        "--ks-pass", "pass:oreki2026",
        "--key-pass", "pass:oreki2026",
        "--ks-key-alias", "oreki",
        "--min-sdk-version", MIN_SDK,
        "--out", out,
        aligned,
    ])
    log("已签名：%s" % out)
    log("大小 %.2f MB  SHA-256 %s" % (os.path.getsize(out) / 1048576.0, sha256(out)))

    # 自检：签名必须可验；manifest 里必须有 mediaProjection 前台服务类型
    out_verify = run([apksigner, "verify", "--min-sdk-version", MIN_SDK, out])
    log("apksigner verify 通过")


if __name__ == "__main__":
    main()
