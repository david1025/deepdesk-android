# DeepSeek Android 原生客户端（第三方 BYOK）

依据《DeepSeek_Android_App_原生设计文档_V1.1.md》实现的 **Kotlin + Jetpack Compose** 原生 Android 应用。
无 WebView / Capacitor / React 主界面（AT-19）；聊天、深度思考、联网搜索、链接阅读、文件/图片、OCR、
DeepSeek ASR 语音输入与 TTS 朗读全部走 DeepSeek 官方全家桶 API。

> ⚠️ 本应用为独立第三方客户端，与 DeepSeek 官方无隶属关系。使用你自己的 API Key（BYOK），
> 费用由你的 DeepSeek 账户承担。当前仓库图标为占位图形，发行前须按
> [docs/asset-provenance.md](docs/asset-provenance.md) 核实官方资源。

---

## 工程结构（多模块）

```
app/                    # Application、MainActivity、导航、DI 组装、清理 Worker
core/
  common/               # AppError 统一错误模型、AppLog（脱敏）、TimeProvider、ApiKeyProvider
  model/                # 领域模型 + Messages/SSE/工具 API DTO（kotlinx.serialization）
  network/              # OkHttp/Retrofit、SseDecoder、MessageStreamClient、Files/Tools 客户端
  database/             # Room（会话/消息/块/附件/草稿/滚动锚点，schema 导出）
  datastore/            # Android Keystore AES-GCM 密钥存储、偏好 DataStore
  ui/                   # Material 3 主题（DeepSeek 蓝）、Markdown 原生渲染
feature/
  chat/                 # 消息树/分支、SSE Reducer、编排器、聊天 UI
  history/              # 会话抽屉、ZIP v3 导入导出
  attachments/          # Photo Picker/SAF/拍照、类型策略、上传、孤儿清理
  voice/                # AudioRecord→WAV→ASR、TTS→AudioTrack（焦点管理）
  settings/             # API Key（GET /models 验证）、主题/语言/音色、诊断页（Search/Fetch）
docs/                   # API 契约 / Demo 对齐清单 / 图标出处 / 验收计划
LICENSES/               # 开源许可说明
```

## 环境要求

- JDK 17+（本仓库用 JDK 21 验证）
- Android SDK：`platforms;android-35`、`build-tools;35.x`（`local.properties` 的 `sdk.dir` 指向 SDK）
- Android Studio Ladybug+ 或直接命令行 Gradle Wrapper

## 构建与测试

```bash
# 1. 配置 local.properties（本机已就绪）
sdk.dir=/path/to/Android/sdk

# 2. debug APK
./gradlew :app:assembleDebug        # 输出 app/build/outputs/apk/debug/

# 3. 单元测试（SSE/Reducer/请求构造/WAV/TTS 分段/ID）
./gradlew test

# 4. release APK（签名见下节）
./gradlew :app:assembleRelease      # 输出 app/build/outputs/apk/release/
```

## Windows 下 Android Studio 使用（设计文档交付要求）

1. 安装 Android Studio（自带 JDK 17+），`git clone` 或复制本目录。
2. `File → Open` 打开工程根目录；首次同步会自动下载 Gradle 8.9 与依赖。
3. 若无 `local.properties`：Studio 会自动生成；或手动写入 `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`。
4. 运行：选择 `app` 配置 + 真机/模拟器 → `Run ▶`；至少一台低端机与一台 Android 13/14+ 真机验证（AT-17）。
5. 调试建议：`Logcat` 过滤 `DSH/`（日志已脱敏，不含密钥与对话内容）。

## 发布签名与回滚

1. 生成密钥（勿入版本库）：
   ```bash
   keytool -genkeypair -v -keystore keystore/release.jks \
     -alias dsh-release -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 写入 `keystore/release-signing.properties`（已加入 .gitignore 语义，见下）：
   ```properties
   storeFile=release.jks
   storePassword=******
   keyAlias=dsh-release
   keyPassword=******
   ```
   `app/build.gradle.kts` 会自动读取并用于 release 签名；缺失时 release 落到 debug 签名（仅供本地验证）。
3. 发布前检查（AT-18/AT-20）：APK 无内置 Key（代码中无任何共享密钥）、调试关闭、
   权限最小化（仅 INTERNET + RECORD_AUDIO 运行时申请）、图标来源留档完成。
4. 回滚：保留每个 release 的 APK 与版本号（versionCode 递增）；出现线上问题按上一版本 APK 直接覆盖安装回滚。

## API Key 配置（BYOK）

设置 → API Key → 填写 `sk-…` → 保存并验证（`GET /models` 区分 401/402/429）。
密钥经 Android Keystore 主密钥 AES-GCM 加密后存于 `files/secure/api_key.bin`；
不进 Room/DataStore/日志/备份（`dataExtractionRules` 已禁用云备份与设备迁移）。

## 设计文档 → 实现 速查

| 文档条目 | 实现位置 |
|---|---|
| §5.2/5.3 Messages SSE | `core/network/SseDecoder`、`MessageStreamClient`、`feature/chat/engine/MessageEventReducer` |
| §5.4/3.4 ASR | `feature/voice`（`RecordingController`/`WavWriter`/`AsrExecutor`） |
| §5.5/3.5 TTS | `feature/voice/tts/TtsPlayer`（分段 ≤10,000 字符、AudioFocusRequest） |
| §5.6 文件策略 | `feature/attachments/AttachmentTypeResolver`、`core/network/FilesApiClient` |
| §5.7 独立 Search/Fetch | `feature/settings` 诊断页（用户聊天走模型内置工具） |
| §6 Room | `core/database`（6 实体、分支树、草稿、滚动锚点） |
| §4.3 BYOK | `core/datastore`（`CryptoManager`/`ApiKeyStore`） |
| §9 错误矩阵 | `core/common/AppError` + 各 ViewModel 映射 |
| §11 验收 | `docs/native-android-acceptance.md` |

## 已知差异与待联调（诚实声明）

- **自动标题 / 独立 Search-Fetch / OCR 字段**：按官方文档实现 + 宽松解析，精确字段名需真机联调（见 docs/deepseek-api-contract.md §8）。
- **ZIP v3**：官网 / chat-demo 导出使用 fragments（REQUEST/RESPONSE/THINK）；本客户端导入同时兼容旧 content/thinking 备份，导出为 fragments v3。
- **公式 / 图表**：行内与块级公式用 JLatexMath 渲染；Mermaid 经隐藏 WebView 导出为图片。
- **图标**：占位图形，非官方资源（见 docs/asset-provenance.md）。
- 真机验收（AT-01~AT-22 中需真机/API 的用例）尚未执行，需要 DeepSeek API Key 与实体设备。

## 许可

- 本工程代码：随仓库分发（内部项目）。
- 参考实现的交互与协议语义来自 deepseek-ai/chat-demo（MIT）——未复制其源码；许可与版权声明见 [LICENSES/README.md](LICENSES/README.md)。
- 依赖许可清单：AndroidX/Apache-2.0、OkHttp/Apache-2.0、kotlinx/Apache-2.0、mikepenz markdown renderer/Apache-2.0、Coil/Apache-2.0。
