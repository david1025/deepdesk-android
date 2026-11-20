# 第三方许可清单

本工程在设计与交互上参考了以下项目（未复制其源码）：

## deepseek-ai/chat-demo

- 许可：MIT License
- 版权：Copyright (c) 2026 deepseek-ai
- 用途：页面交互、工具事件语义、历史 ZIP 行为规范的参考基线（设计文档 §1.1）
- 原文：https://github.com/deepseek-ai/chat-demo/blob/main/LICENSE

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 主要依赖

| 依赖 | 许可 |
|---|---|
| AndroidX（Compose/Room/DataStore/WorkManager 等） | Apache License 2.0 |
| Kotlin / kotlinx.serialization / kotlinx.coroutines | Apache License 2.0 |
| OkHttp / Okio / Retrofit / MockWebServer | Apache License 2.0 |
| Coil | Apache License 2.0 |
| multiplatform-markdown-renderer (mikepenz) | Apache License 2.0 |
| JLatexMath-Android (`ru.noties:jlatexmath-android`) | GPL-2.0 |
| mermaid (运行时 CDN `mermaid@10.9.1`) | MIT License |

## JLatexMath-Android

- 许可：GNU General Public License, version 2
- 版权：Copyright (c) Dimitry Ivanov / original JLaTeXMath authors
- 用途：聊天正文数学公式渲染（与官方 App 同源库）
- 本地副本：`core/ui/libs/jlatexmath-android-0.2.0.aar`
- 原文：https://github.com/noties/jlatexmath-android/blob/master/LICENSE

本工程以动态依赖方式使用该库，未修改其源码。分发本应用时需一并遵守 GPL-2.0 对对应二进制的要求。

## mermaid

- 许可：MIT License
- 用途：隐藏 WebView 将 mermaid 源码渲染为 PNG（脚本从 jsDelivr 加载，不内置官方 App 的 generator.js）
- 原文：https://github.com/mermaid-js/mermaid/blob/develop/LICENSE

完整机器清单可在构建后于 `app/build/outputs/` 通过第三方依赖扫描工具生成。
