# 电子书阅读器

无障碍本地电子书阅读器。个人制作使用，不收费、不出售。所有阅读数据只存本机，联网仅用于检查更新。

## 功能

- 格式：EPUB、Kindle 的 MOBI / AZW3 / AZW / PRC、PDF（文字版与扫描版）、TXT、FB2、HTML、RTF、DOCX
- PDF：文字版直接读文字层；扫描版在手机本地用 PP-OCRv5 识别（含整页转正、倾斜行摆正、两栏三栏阅读顺序、文字层坏页自动兜底）
- TXT 自动识别编码，GBK / BIG5 / UTF-8 / UTF-16 等不乱码
- 分页模式（左右翻页、双指翻页、点按分区、音量键、进度条跳页），连续滚动模式
- 书库、最近打开、书签、阅读进度、六种主题、字号行距页边距字体
- 全文无障碍：控件都有可朗读的名称，正文按段落成焦点，读屏可连续朗读
- 设置里含关于软件、检查更新、更新日志、退出软件
- 首次运行弹一次使用声明，清除数据后重弹

## 更新清单

`update_pro.json` 是本应用的版本清单，软件里"检查更新"读的就是它：

```json
{
  "package": "com.zz.reader.pro",
  "versionCode": 28,
  "versionName": "4.7",
  "apkUrl": "https://github.com/hzj1770niu/ebook-reader/releases/download/v4.7/ebook_v4.7.apk",
  "sha256": "…",
  "changelog": "…"
}
```

发新版：上传 APK 到 Releases，然后改这里的 versionCode / versionName / apkUrl / sha256 / changelog。

## 构建

需要 JDK 17、Android SDK 35、Gradle 8.9。

第三方二进制不入库（`app/libs/*.jar` 与 `app/src/main/jniLibs/` 里的 so）：

- PDFBox-Android：从 `com.tom_roush:pdfbox-android` 的 AAR 解出 `classes.jar` → `app/libs/pdfbox.jar`，AAR 内 `assets/` 并入 `app/src/main/assets/`，`jniLibs/` 并入 `app/src/main/jniLibs/`
- ONNX Runtime Android：同上方式解出 `onnxruntime.jar` 与 `libonnxruntime.so`、`libonnxruntime4j_jni.so`
- OCR 模型放在 `app/src/main/assets/ocr/`：`det5.onnx`、`rec5.onnx`、`cls.onnx`、`keys5.txt`（PP-OCRv5，来自 huggingface `bukuroo/PPOCRv5-ONNX`）

```
gradle assembleRelease
```

签名私钥不入库，自行生成并配置。
