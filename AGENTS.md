# frame-observer · Agent 工作规则

## 1. 先确认权限、预算与运行状态
> 启动/恢复读取本文件和 [Workspace AGENTS.md](https://github.com/OrekiDawson/AD-Oreki-Edition-Workspace/blob/main/AGENTS.md)，按当前任务加载对应专项细则。跨仓统一规则由 Workspace 唯一维护，不重复全文加载；本仓细则仍有强制性。最新用户授权、平台门禁及安全优先。

- 现有 PR 实质收口优先，六仓 OPEN PR 合计上限 5（含 Draft），ChatGPT 直接授权例外按 Workspace；不绕过 Kody、CI、Ruleset 与线程门禁。
- Coreutils 优先，能用可靠 Shell 工具完成不用 Node.js；确需 JS 业务/生态除外。PATH 工具直接用命令名；Windows 用 pwsh，不用绝对可执行路径或 powershell.exe，解析/版本冲突等必要场景例外。
- 完整日志落盘、失败先读根因、最小必要验证。每 3 分钟真实反馈，Lead 核验 Subagent 后接续独立任务，不争抢 Owner/资源；当前 Goal 不重开、不改编号、不重置计时。
- 根 AGENTS.md 目标 ≤100 行且 ≤8 KiB，连续章节编号；新增规则先合并旧条，事实归 FACTS、任务归 Issue、流程归专项/Skill、机器约束归 CI，README 面向读者。

- 本仓独立设备观测，不因其他项目复工自动获得新开发、付费、采集或设备操作权限。
- [授权与跨仓继承细则](docs/agent-policy/01-授权与继承.md)。

## 2. 质量与 GitHub 合并门禁
- 合并前检查实际 HEAD、完整 Diff、Reviews、评论、Threads、适用 Kody/Required Checks/Ruleset；纯 AGENTS.md 官方 skipped 例外按 Workspace。
- [审查与设备技术细则](docs/agent-policy/02-审查与设备观测.md)。

## 3. 项目技术边界
- 仅观测真实设备画面、帧率、MediaProjection、无障碍控件树、事件与 logcat；不注入点击、不修改 APK、不操纵存档。
- 屏幕投影和无障碍由用户在设备端明确授权；不得绕过 FLAG_SECURE、静默授权、扩采集范围；敏感内容脱敏。
- 保持 Kotlin、Android SDK 和既有 /stats、/snapshot.jpg、/ui、/events、/timeline、/logcat 等接口兼容。
- 截图、控件树、日志绑定设备、APK SHA-256、时间、操作前后状态；控件树存在不等于视觉可见。共享 AVD 独占，不强停/清除其他任务进程及存档。

## 4. 工作方式与文档卫生
- [工作方式和编号细则](docs/agent-policy/03-工作方式.md)；无授权不启动采集、AVD、长跑。真实 PASS/FAIL/BLOCKED/NOT RUN 分列。

## 5. 任务编号唯一分配权
- ChatGPT 唯一分配，单层 R整数，历史编号保持；本轮继续不创建编号。
