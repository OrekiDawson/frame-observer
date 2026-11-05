package com.oreki.frameobserver.a11y

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 无障碍绑定守卫。
 *
 * **为什么需要这个类**：实测发现 `adb shell am force-stop com.oreki.frameobserver`
 * 会把 `ENABLED_ACCESSIBILITY_SERVICES` 清成 null、`ACCESSIBILITY_ENABLED` 变成 0。
 * 之后 `/ui` 永远返回 503「无障碍服务未连接」，整条 UI 观测链断掉，
 * 只能人工进设置里重新打开 —— 这违背「观测应当可以自己恢复」的诉求。
 *
 * 本类在启动时检测并尝试写回绑定；写不进去（未授予 `WRITE_SECURE_SETTINGS`）就把状态
 * 如实报给 `/stats`，由调用方决定补哪一步，而不是让 `/ui` 只回一句无法行动的 503。
 *
 * **边界**：只处理「无障碍绑定」这一件纯本地的事。屏幕投影（MediaProjection）的授权
 * **不在此列** —— 那必须由人在设备上确认，本类不会、也不应该尝试绕过。
 */
object A11yBinder {

    private const val TAG = "OrekiA11y"

    /** 无障碍服务的完整组件名，写 Settings 时必须与清单里声明的一致。 */
    const val SERVICE: String =
        "com.oreki.frameobserver/com.oreki.frameobserver.a11y.ObserverA11yService"

    /**
     * 当前绑定状态。
     *
     * - `connected`：服务实例在，`/ui` 可用
     * - `notconnected`：设置里已启用，但系统还没把实例连上（正常过渡态）
     * - `missing`：设置里没包含本服务（force-stop 后的典型状态）
     * - `disabled`：无障碍总开关关着
     * - `unknown`：读不到（权限或系统异常）
     */
    @JvmStatic
    fun state(ctx: Context?): String {
        if (ObserverA11yService.get() != null) return "connected"
        if (ctx == null) return "unknown"
        return try {
            val enabled = Settings.Secure.getInt(
                ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
            )
            if (enabled == 0) {
                "disabled"
            } else {
                val list = Settings.Secure.getString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                when {
                    list == null || !list.contains(SERVICE) -> "missing"
                    else -> "notconnected"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读无障碍状态失败: $t")
            "unknown"
        }
    }

    /**
     * 尝试把绑定写回去。
     *
     * 只在确实缺失时写，避免每次调用都触发系统重新绑定（那会把已连上的服务踢掉）。
     *
     * @return true 表示写成功（服务可能还在异步连接中，需再查 [state]）
     */
    @JvmStatic
    fun ensureBound(ctx: Context?): Boolean {
        if (ctx == null) return false
        if (ObserverA11yService.get() != null) return true
        val st = state(ctx)
        if ("notconnected" == st) {
            // 已启用、系统正在绑，这时再写一遍反而会打断绑定。
            return false
        }
        return try {
            val cur = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val next = when {
                cur.isNullOrEmpty() -> SERVICE
                cur.contains(SERVICE) -> cur
                else -> "$cur:$SERVICE"
            }
            Settings.Secure.putString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next,
            )
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "已写回无障碍绑定: $next")
            true
        } catch (t: Throwable) {
            // 没有 WRITE_SECURE_SETTINGS 时会走到这里（SecurityException）。
            // 这不是程序缺陷，是权限边界；把命令交给调用方去补。
            Log.w(TAG, "写回无障碍绑定失败（缺 WRITE_SECURE_SETTINGS）: $t")
            false
        }
    }

    /**
     * 给调用方的修复命令，直接贴进 `/ui` 的错误体里。
     *
     * 把命令写进响应而不是写进文档，是因为出问题时人正在看响应。
     */
    @JvmStatic
    fun fixCommands(): String =
        "adb shell pm grant com.oreki.frameobserver android.permission.WRITE_SECURE_SETTINGS && " +
            "adb shell settings put secure enabled_accessibility_services $SERVICE && " +
            "adb shell settings put secure accessibility_enabled 1"
}
