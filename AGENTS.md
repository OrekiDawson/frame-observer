# frame-observer · Agent 工作规则

本仓独立开发仍受实际用户授权约束；历史暂停不因其他项目恢复自动解除。已验收只读观测可按当前明确授权、设备许可、AVD Owner 和工具状态逐次使用，不擅自启动新功能、采集或付费任务。

## 1. 权限、预算与运行状态

[跨仓继承与授权细则](docs/Agent规则/01-总则与授权.md)
共性规则以 [Workspace AGENTS.md](https://github.com/OrekiDawson/AD-Oreki-Edition-Workspace/blob/main/AGENTS.md) 为权威；本仓专题细则同为强制约束。开始任务先核对当前授权、远端规则、HEAD、Owner 和任务；操作前阅读涉及的细则，不重复加载无关专题。最新用户授权、平台保护与安全门禁优先。

- 六仓 OPEN PR 合计 ≤5（含 Draft/预留），先收口已有 PR，再开展新增任务；ChatGPT 直接授权 PR 的例外依 Workspace §4.1，不豁免其他门禁。游戏业务优先，不抢其他 Owner。
- Lead 每三分钟真实汇报，Subagent 完成后及时接续安全独立任务。等待 CI/Kody 不等于全局停工；活跃会话按 Workspace 刷新规则和远端状态。
- 用户暂停、安全、权限、资源及预算硬门禁优先。全天执行，无峰谷或固定余额停工；周期预算 80% 预警、100% 或下一批超额停止新增收费。不得擅自充值、换账号或 Provider 绕过。
- Coreutils 优先（全局长期强制）：能用标准 Shell、Coreutils、find/grep/sed/awk/jq 可靠完成的任务不使用 Node.js；确需 JS 运行时或生态 API 等例外。PATH 中直接调用命令名，Windows 使用 pwsh，不用 powershell.exe 或冗长绝对可执行路径，除非 PATH 解析或版本冲突。
- R 编号由 ChatGPT 分配；不添子编号、不改历史编号；活跃 Goal 不重复 /goal 或重置计时。

## 2. 质量与 GitHub 合并门禁

[审查与合并细则](docs/Agent规则/02-质量审查与合并.md)
- Kody 仅 Balanced；付费授权、Provider、预算与并发按 Workspace 核实，按 HEAD/配置去重，不冒充 PASS。仅纯 AGENTS.md 官方确认规则文件 skipped 适用例外，不豁免 Required Checks。
- 按实际分支保护、Required Checks、Review Threads、审批、Owner 和安全门禁合规合并；禁止强推、越权、重复收费或虚构回执。规则 PR 完整继承并回读。

## 3. 项目技术边界

[观测技术细则](docs/Agent规则/03-项目技术边界.md)
- 仅观测真实设备画面、帧率、MediaProjection、无障碍控件树、事件、logcat；不注入点击、不修改游戏 APK、不操纵存档。
- 屏幕投影与无障碍由用户设备端授权；不绕过 FLAG_SECURE、静默授权或扩大采集白名单；敏感信息脱敏。
- 保持 Kotlin/Android SDK 及 /stats、/snapshot.jpg、/ui、/events、/timeline、/logcat 接口兼容；更新 [接口说明](docs/接口说明.md)。
- 证据绑定设备、APK SHA-256、时间和前后操作；控件树存在不等于视觉可见。共享 AVD 独占，不强停其他任务；暂停期间不启动采集、构建或长跑。

## 4. 工作方式与文档卫生

[工作方式细则](docs/Agent规则/04-工作方式.md)
- 关键命令完整日志落盘、真实退出码和失败根因优先；定向最小验证，避免重复构建/轮询。主工作树 Clean/WIP 保全、80 GiB 总量及回收遵循 Workspace，不删唯一证据、密钥或他人修改。
- 根 AGENTS.md 目标 ≤100 行、≤8 KiB。新约束按主题修改旧规则；事实、操作手册与当前任务分别放 FACTS、Skill/专项文档、Issue，中文优先。

## 5. 任务编号唯一分配权

[任务编号细则](docs/Agent规则/05-任务编号.md)
- 不擅自新增或更改 R 编号；按当前授权与任务书执行。
