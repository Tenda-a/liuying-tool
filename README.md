# 流影下载器

一个使用原生 Java 编写的 Android 视频下载客户端。

## 当前能力

- 接收粘贴文本和 Android 系统分享的链接。
- 识别抖音、快手、小红书、B站、YouTube 及其官方短链域名。
- 解析短链重定向、Open Graph 视频、页面 `video` 标签和常见页面状态中的媒体地址。
- 使用应用内 WebView 登录;平台 Cookie 保存在应用沙箱内。
- 将可用媒体地址加入 Android 系统 `DownloadManager`,默认下载至 `Download/Liuying/`。
- 提供系统下载记录入口。
- 禁止应用进程使用明文 HTTP。

## 已知限制

- 抖音、快手和小红书的部分页面依赖动态签名或平台风控,登录后仍可能只能在浏览器中打开。
- YouTube 高清媒体通常采用音视频分离格式;当前版本未集成媒体合并器,无法保证下载高清格式。
- B站 DASH 音视频分离格式尚未支持合并;当前仅下载页面提供的单文件媒体地址。
- 平台接口和页面结构可能随时变化,解析功能不保证长期可用。

## 构建要求

- Android SDK(包含 Platform;构建工具可来自 SDK 或当前 `PATH`)
- JDK 17
- Bash
- `aapt2`、`zipalign`、`apksigner`,以及 `d8` 或 `dx`

脚本不依赖 Gradle,可在已配置上述工具的 Termux、Linux、macOS 或 Windows(Git Bash/WSL)环境中构建。它会自动选择 SDK 中最新的 Platform,并优先使用当前 `PATH` 中的构建工具:

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh
```

输出文件:`build/outputs/liuying-video.apk`。

构建脚本及 v1.0.0 Release APK 使用临时调试密钥签名,仅供测试。正式分发时请使用独立的发布密钥,并将密钥保存在仓库之外。

## 隐私与安全

- 登录 Cookie 仅保存在 Android 应用私有目录和 WebView 沙箱中。
- 请勿提交 Cookie 导出文件、签名密钥、下载内容或其他个人数据。
- 本项目不提供绕过平台访问控制、数字版权管理或付费限制的功能。

## 免责声明

使用者应遵守所在地区法律、目标平台服务条款及内容版权要求。本项目仅供学习和个人合法使用,不对第三方平台可用性或使用者行为负责。

## License

[MIT](LICENSE)
