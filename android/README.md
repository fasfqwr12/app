# File Shuttle - Android App

无线文件传输工具，一键启动服务，电脑浏览器访问即可传输文件。

## 功能

- 📱 一键启动 HTTP 文件服务器
- 🔗 显示二维码，电脑扫码即可访问
- 📁 浏览手机所有文件
- ⬇️ 下载文件到电脑（支持断点续传）
- ⬆️ 上传文件到手机
- 🖼️ 图片缩略图预览

## 使用方法

1. 安装 APK 并打开 App
2. 点击「启动服务」按钮
3. 电脑浏览器扫码或输入显示的地址
4. 在网页上操作文件

## 自动构建

本项目使用 GitHub Actions 自动构建 APK：

1. Push 代码到 main 分支
2. 等待 Actions 完成（约 3-5 分钟）
3. 在 Actions 页面下载 APK

## 手动构建

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease
```

APK 输出位置：`app/build/outputs/apk/`

## 技术栈

- Kotlin
- NanoHTTPD（轻量级 HTTP 服务器）
- ZXing（二维码生成）

## 权限说明

- `INTERNET` - 提供 HTTP 服务
- `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` - 读取文件
- `ACCESS_WIFI_STATE` - 获取 WiFi IP 地址
- `FOREGROUND_SERVICE` - 后台运行服务
