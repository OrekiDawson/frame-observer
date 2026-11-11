# PROJECT PAUSED — frame-observer

- 暂停日期：2026-11-11（Asia/Hong_Kong）
- 项目状态：PAUSED（随 AD Mod 项目一并暂停；工具本身可独立使用）
- 当前轮次：R101（未宣称完成）
- 封存前 HEAD：a99f832fd5e1b2bfdf89216febbc4a17b070f80b
- 封存提交：`077057dd694030fabc90a854d6d71308fc65ab81`（2026-11-11 首次封存）；本文件已在同日规则重建提交中更新。
- 分支 / 远程：main → <https://github.com/OrekiDawson/frame-observer.git>

## 当前开发状态

- 本仓库是 Android 真实屏幕观测工具，不包含游戏业务逻辑，不注入点击，不修改游戏包。
- 工作区中的 AGENTS.md、README.md 与 `.editorconfig` 为待封存的文档与换行策略改动，本次原样提交。
- `savestates/` 只保存 README 与 manifest；观测样本默认不入库。

## 已知阻碍 / 使用前提

- 投影授权必须由人在设备上确认（MediaProjection），工具不提供远程触发接口。
- 冷启动后无障碍服务可能被系统清空，需要按 README 写回 `ObserverA11yService` 并复核 `/ui`。
- 项目暂停期间不保证继续维护；恢复使用时先核对设备、端口 8731、adb forward 与 `/stats`。

## 重要本地资产（保留在原位置）

| 资产 | 路径 | 说明 | SHA-256 |
| --- | --- | --- | --- |
| 已构建观测 APK | `OrekiFrameObserver.apk` | 836,132 字节 | `77e49a79e7f631abe38c988c486a0e8bf1fd479808ea72d6e1d55ddd90a9cbf4` |
| 源码 | `app/` | Android 工程源码 | — |
| 构建中间物 | `_build/`、`_buildlog/`、`_pulled/`、`_shots/` | 本地保留，不入库 | — |
| 观测样本入口 | `savestates/` | 只有 README 与 manifest 入库 | — |

## 未来恢复方式

1. `git checkout main && git pull --ff-only`
2. 先读 `README.md` 与 `AGENTS.md`，按其中授权链路启动观测。
3. 需要重新构建时使用本仓库 Gradle 配置；安装前核对 APK 哈希与设备。
4. 恢复 AD Mod 开发时再决定是否继续维护本工具；现有源码与历史记录原样保留。
