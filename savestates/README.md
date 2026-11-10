# savestates — frame-observer 观测样本

frame-observer 是 Android 画面/控件树的**观测工具**，不运行游戏本体，因此**不提供游戏存档**；
本目录存放它产生的**观测样本**，用于复现 Android GUI 验收结论。

## 目录

| 目录 | 内容 |
|---|---|
| `captures/` | `/ui` 控件树、`/snapshot.jpg` 截图、`/stats` 快照（真实采集） |
| `fixtures/` | 已知结构的合成输入（用于解析器自测，**不是**真实游戏画面） |

## 说明

- 观测样本必须记录：采集时间、目标包名、设备/AVD、`frames_processed`、脚本命令。
- 真实画面截图属于可核对证据，不得用合成图冒充。
- 不直接运行游戏的项目不得伪造游戏存档凑数（本目录因此没有 `original/`、`mod/`）。
