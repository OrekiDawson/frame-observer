package com.oreki.frameobserver.a11y;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 无障碍绑定守卫。
 *
 * <p><b>为什么需要这个类</b>：2026-10-31 实测发现，
 * {@code adb shell am force-stop com.oreki.frameobserver} 会把
 * {@link Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES} 清成 {@code null}、
 * {@link Settings.Secure#ACCESSIBILITY_ENABLED} 变成 {@code 0}。
 * 之后 {@code /ui} 永远返回 503「无障碍服务未连接」，整条 UI 观测链断掉，
 * 只能人工进设置里重新打开——这正好违背「观测应当可以自己恢复」的诉求。
 *
 * <p>本类在启动时检测并尝试写回绑定；写不进去（未授予
 * {@code WRITE_SECURE_SETTINGS}）就把状态如实报给 {@code /stats}，
 * 由调用方决定补哪一步，而不是让 {@code /ui} 只回一句无法行动的 503。
 *
 * <p><b>边界</b>：这里只处理「无障碍绑定」这一件纯本地的事。
 * 屏幕投影（MediaProjection）的授权**不在此列**——那必须由人在设备上确认，
 * 本类不会、也不应该尝试绕过。
 */
public final class A11yBinder {

    private static final String TAG = "OrekiA11y";

    /** 无障碍服务的完整组件名，写 Settings 时必须与清单里声明的一致。 */
    public static final String SERVICE =
            "com.oreki.frameobserver/com.oreki.frameobserver.a11y.ObserverA11yService";

    private A11yBinder() {
    }

    /**
     * 当前绑定状态。
     *
     * @return {@code connected}   服务实例在，{@code /ui} 可用
     *         {@code notconnected} 设置里已启用，但系统还没把实例连上（正常过渡态）
     *         {@code missing}      设置里没包含本服务（force-stop 后的典型状态）
     *         {@code disabled}     无障碍总开关关着
     *         {@code unknown}      读不到（权限或系统异常）
     */
    public static String state(Context ctx) {
        if (ObserverA11yService.get() != null) {
            return "connected";
        }
        if (ctx == null) {
            return "unknown";
        }
        try {
            int enabled = Settings.Secure.getInt(
                    ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled == 0) {
                return "disabled";
            }
            String list = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || !list.contains(SERVICE)) {
                return "missing";
            }
            return "notconnected";
        } catch (Throwable t) {
            Log.w(TAG, "读无障碍状态失败: " + t);
            return "unknown";
        }
    }

    /**
     * 尝试把绑定写回去。
     *
     * <p>只在确实缺失时写，避免每次调用都触发系统重新绑定（那会把已连上的服务踢掉）。
     *
     * @return true 表示写成功（服务可能还在异步连接中，需再查 {@link #state}）
     */
    public static boolean ensureBound(Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (ObserverA11yService.get() != null) {
            return true;
        }
        String st = state(ctx);
        if ("notconnected".equals(st)) {
            // 已启用、系统正在绑，这时再写一遍反而会打断绑定。
            return false;
        }
        try {
            String cur = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String next;
            if (cur == null || cur.isEmpty()) {
                next = SERVICE;
            } else if (cur.contains(SERVICE)) {
                next = cur;
            } else {
                next = cur + ":" + SERVICE;
            }
            Settings.Secure.putString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(
                    ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "已写回无障碍绑定: " + next);
            return true;
        } catch (Throwable t) {
            // 没有 WRITE_SECURE_SETTINGS 时会走到这里（SecurityException）。
            // 这不是程序缺陷，是权限边界；把命令交给调用方去补。
            Log.w(TAG, "写回无障碍绑定失败（缺 WRITE_SECURE_SETTINGS）: " + t);
            return false;
        }
    }

    /**
     * 给调用方的修复命令，直接贴进 {@code /ui} 的错误体里。
     *
     * <p>把命令写进响应而不是写进文档，是因为出问题时人正在看响应。
     */
    public static String fixCommands() {
        return "adb shell pm grant com.oreki.frameobserver android.permission.WRITE_SECURE_SETTINGS && "
                + "adb shell settings put secure enabled_accessibility_services " + SERVICE + " && "
                + "adb shell settings put secure accessibility_enabled 1";
    }
}
