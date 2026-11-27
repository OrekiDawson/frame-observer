# frame-observer · Agent 工作规则

> 跨仓共性规则以 [Workspace AGENTS.md](https://github.com/OrekiDawson/AD-Oreki-Edition-Workspace/blob/main/AGENTS.md) 为唯一权威。启动先读该入口、本文件、当前任务与必要事实；涉及对应事项必须先读以下细则适用章节，不默认全文加载所有细则。共享重复条款不作为另一权威来源，最新用户授权与 Workspace 口径优先；根入口的按需加载及 PATH 约定覆盖旧措辞，不降低安全、预算、审查、CI、验收或发布门禁。根 AGENTS.md 目标 100 行、8 KiB，复杂例外说明；规则按主题编号、避免重复，临时任务进 Issue、事实进 FACTS、可机器检查约束进 CI。

## 跨仓统一核心规则入口（强制）

- 继承 Workspace 共性规则。历史暂停不自动解除；单次已授权 Android 只读观测按当前设备权限、Owner 和实际状态核验，不等于允许独立新开发。
- 详细口径：[执行细则 1](docs/agent-policy/执行细则-01.md)。



## 1. 先确认权限、预算与运行状态

- 仅在当前用户授权、设备许可、AVD Owner 与安全预算满足时工作；不擅自启动采集、AVD、构建、长跑或收费。
- 详细口径：[执行细则 2](docs/agent-policy/执行细则-02.md)。



## 2. 质量与 GitHub 合并门禁

- 当前 HEAD 的适用 CI、Kody、Ruleset、线程、授权门禁按 Workspace 与本仓细则核验；纯 AGENTS.md 官方 skipped 例外不适用于包含细则的 PR。
- 详细口径：[执行细则 3](docs/agent-policy/执行细则-03.md)。



## 3. 项目技术边界

- 只观测真实设备画面、帧率、MediaProjection、无障碍树、事件、logcat，不注入点击、不修改游戏 APK、不操纵存档。投影/无障碍由用户设备端明确授权，禁止绕过 FLAG_SECURE、静默授权或扩大白名单，敏感内容脱敏。保持 Kotlin、Android SDK 及现有接口兼容，实际截图/日志与设备、APK SHA-256、时间、操作状态关联；共享 AVD 独占。
- 详细口径：[执行细则 4](docs/agent-policy/执行细则-04.md)。



## 4. 工作方式与文档卫生

- Coreutils/Shell 优先，PATH 命令名（Windows pwsh）；按范围最小验证、完整日志、保护 WIP，不造证据。
- 详细口径：[执行细则 5](docs/agent-policy/执行细则-05.md)。



## 5. 任务编号唯一分配权

- 仅 ChatGPT 分配任务编号；继承现有任务，不重复开启 /goal 或重置计时。
- 详细口径：[执行细则 6](docs/agent-policy/执行细则-06.md)。


