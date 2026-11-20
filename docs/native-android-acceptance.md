# 原生 Android 验收计划与执行状态（A9）

> 验收基线：设计文档 §11（AT-01~AT-22）。自动化用例见各模块 `src/test`；
> 真机用例需在 Android 真机（至少一台低端机 + 一台 Android 13/14+）执行。

## 已自动化（./gradlew test 可复跑）

| 对应验收项 | 测试 | 覆盖 |
|---|---|---|
| AT-22 SSE 断包/心跳/跨字节 | `SseDecoderTest`（core/network） | CRLF/CR 混排、注释心跳、多行 data、flush、未知事件降级 |
| AT-03/06/14/22 事件语义 | `MessageEventReducerTest`（feature/chat） | 文本/思考增量+签名、工具块、引用、错误码、refusal、interrupted 保留已收内容 |
| §5.2 请求契约 | `RequestFactoryTest` | 模型/系统提示/附件块数组/tools/title 轮次/重新生成不追加空消息 |
| AT-08/09 音频编码 | `WavEncoderTest`（feature/voice） | WAV 头字段/时长、降采样、振幅 |
| §3.5 TTS 分段 | `TtsSegmenterTest` | ≤10,000 计费字符、段落/句子切分、内容守恒 |
| ID 有序性（ZIP 树约束） | `TimeProviderTest` | 父 ID < 子 ID、ISO-Z 格式 |

## 需 MockWebServer / 真机联调的用例（占位待补）

| 验收项 | 内容 | 依赖 |
|---|---|---|
| AT-01 | Key 校验（正确/401）、密钥不在日志与备份 | 真机 + 官方 API |
| AT-02/03/04 | 20 轮对话、思考+搜索双开、URL 阅读 | 官方 API 联调 |
| AT-05/12 | 图片/PDF 上传与问答、OCR | 官方 API 联调 |
| AT-07 | 官方 Demo 导出 ZIP 导入 | 官方样本归档 |
| AT-08~10 | 录音→ASR、120s 上限、权限拒绝/切后台/来电 | 真机麦克风 |
| AT-11 | TTS 音色切换、停止无残留 | 真机音频 |
| AT-13/15 | 独立 Search/Fetch、飞行模式/429/402/5xx | 诊断页 + 网络控制 |
| AT-16/21 | 冷启动恢复、Room 升级、分支切换 | 真机 + schema 迁移 |
| AT-17/18/20 | 设备兼容、Release 安全（无内置 Key、签名）、图标密度 | 真机 + release 构建 |

## 构建产物验收（2026-11-18 本机验证）

- [x] `./gradlew :app:assembleDebug` → debug APK（约 20.6 MB）
- [x] `./gradlew :app:assembleRelease` → 签名 release APK（约 2.56 MB；keystore/release.jks + release-signing.properties）
- [x] `./gradlew test` → 30 个单测全部通过（SSE 8 / Reducer 8 / 请求构造 5 / WAV 3 / TTS 分段 4 / ID 2）
- [x] release 开启 minify+shrinkResources，`-assumenosideeffects` 剥离 debug 日志
- [x] AT-18 自动化部分：双 APK 二进制扫描无内置 API Key；apksigner 验签通过（CN=DSH Android Release）
