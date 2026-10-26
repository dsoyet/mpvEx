# 可配置视频缩略图

## 实现

高级设置新增独立的视频缩略图区域，默认模式为 Percentage、位置 30%。Fixed 支持 0–600 秒；Percentage 和 Smart 共用 0–95%、步长 5% 的位置。Smart 支持关闭黑屏检测及 1–5 次尝试。所有设置通过现有 PreferenceStore 保存，沿用 SettingsManager 的通用 XML 导入导出，不需要变更配置格式。

Smart 按百分比偏移 `0, +10, -10, +20, +30` 顺序生成候选，限制百分比和有效视频时间，去重后截取最大尝试次数。已知时长使用 `duration / 1000.0` 秒，上界为 `max(0, duration - 0.1)`。未知时长时，Fixed 尝试配置秒数，其余模式回退第 0 秒。

FastThumbnails 保持首选，候选逐一解码；首张非黑屏帧立即返回。每张最多采样 32×32 个网格像素，使用 RGB 加权亮度。低于 20 的像素比例至少 95%，且平均亮度不超过 15 时判定为黑屏。全黑时优先选择暗像素比例较低、其次平均亮度较高的帧；只有所有候选解码均失败才调用原有 MediaStore / ThumbnailUtils 回退。系统回退 API 无法保证指定取帧时间。

## 缓存与并发

每个请求捕获不可变的 ThumbnailConfig。内存、磁盘和共享任务键都包含 `v2`、模式、秒数、百分比、黑屏开关及最大尝试次数；磁盘仍保留 1024 像素尺寸标识，内存仍包含显示宽高。视频标识还包含路径（空时使用 URI）、大小、修改时间和时长，避免不同文件元数据相同导致串图。

同一视频、配置和磁盘尺寸通过原子注册共享一个 Deferred；不同显示尺寸共享解码。取消单个等待者不会取消其他等待者，配置变化会取消过期共享任务。全局最多同时解码 3 个视频，各 Smart 候选始终串行。丢弃的候选 Bitmap 会回收，已交给 UI 或缓存的 Bitmap 不会提前回收。

配置变化通过 StateFlow 通知卡片和三个文件夹预生成入口，刷新显示键并重新启动预生成。旧配置的文件和内存键独立，即使旧任务完成也无法覆盖新配置结果。JPG 先写临时文件，再重命名；磁盘缓存启动时和写入后在后台裁剪到 256 MiB，旧版缓存纳入同一容量上限。

## 修改文件

- `.github/workflows/build.yml`：PR、手动、默认分支 master 推送触发；执行单元测试和 assembleStandardRelease，按实际 APK 输出上传 unsigned Artifacts，缺少 APK 时失败。
- `app/build.gradle.kts`：仅新增 JUnit 测试依赖。
- `app/src/main/java/app/marlboroadvance/mpvex/di/DatabaseModule.kt`：注入 AdvancedPreferences。
- 同目录前缀下 `domain/thumbnail/ThumbnailRepository.kt`：生成、回退、缓存和配置更新联动。
- `domain/thumbnail/ThumbnailConfig.kt`：类型安全模式、参数规范化、候选时间和缓存指纹。
- `domain/thumbnail/LumaStatistics.kt`：纯 Kotlin 采样、评分与候选选择。
- `domain/thumbnail/DarkFrameDetector.kt`：Bitmap 适配和无效位图容错。
- `domain/thumbnail/ThumbnailRequests.kt`：共享任务、取消与配置隔离。
- `preferences/AdvancedPreferences.kt`：持久化设置及配置流。
- `ui/preferences/AdvancedPreferencesScreen.kt`：动态设置控件，复用已有清理按钮。
- `ui/preferences/SearchablePreference.kt`：新增设置的搜索索引。
- `ui/browser/cards/VideoCard.kt`：列表、网格缩略图订阅配置。
- `ui/browser/videolist/VideoListScreen.kt`、`ui/browser/recentlyplayed/RecentlyPlayedScreen.kt`、`ui/browser/filesystem/FileSystemBrowserScreen.kt`：预生成订阅配置。
- `app/src/main/res/values/thumbnail_strings.xml`、`app/src/main/res/values-zh-rCN/thumbnail_strings.xml`：英语及简体中文资源。
- `app/src/test/java/app/marlboroadvance/mpvex/domain/thumbnail/`：ThumbnailConfigTest、DarkFrameDetectorTest、ThumbnailGenerationTest。
- `docs/video-thumbnails.md`：实现与验证记录。

## 验证

使用项目 Gradle Wrapper、独立缓存中的 JDK 17 和 Android SDK 37。初次命令因缺失 Java、随后因缺失 SDK 失败；补齐工具后重新执行。未修改 applicationId、Android/ABI 目标、签名和版本策略。

```powershell
.\gradlew.bat testStandardDebugUnitTest assembleStandardRelease --console=plain
git diff --check
```

最终结果：`BUILD SUCCESSFUL in 5m 41s`，87 个任务执行完成；19 个单元测试全部通过（ThumbnailConfigTest 5 个、DarkFrameDetectorTest 5 个、ThumbnailGenerationTest 9 个），无失败、错误或跳过。Release 必要 lint 检查通过；`git diff --check` 通过。

成功生成以下 unsigned APK，目录为 `app/build/outputs/apk/standard/release/`：

| 文件 | 字节数 |
| --- | ---: |
| app-standard-arm64-v8a-release-unsigned.apk | 28261622 |
| app-standard-armeabi-v7a-release-unsigned.apk | 26984416 |
| app-standard-x86-release-unsigned.apk | 29854276 |
| app-standard-x86_64-release-unsigned.apk | 29890711 |
| app-standard-universal-release-unsigned.apk | 90569626 |

构建提示本机没有剥离预编译原生库的调试符号，已按原样打包；没有影响构建成功。输出 metadata 确认 applicationId 仍为 `app.marlboroadvance.mpvex`，versionName 仍为 `1.3.1`。

尚需真机确认的交互包括：三种模式取帧效果、重启后设置保留、XML 导入导出、浏览文件夹时切换配置，以及原有视频播放体验。JVM 测试覆盖纯 Kotlin 采样核心，无法替代 Android Bitmap / 原生解码器和 MediaStore 的设备测试。GitHub Actions 配置的远端运行需要提交推送后触发。


## GitHub Actions 可安装构建

后续按要求停止本地编译，改由 `.github/workflows/build.yml` 在 GitHub-hosted Ubuntu runner 上构建。工作流保留 `assembleStandardRelease`，增加项目既有的 `assembleStandardDebug`：该构建自动使用 Android Debug 签名，包名沿用已有的 `.debug` 后缀，可与正式版共存，不修改 applicationId 或 Release 签名策略。

云端使用 Android SDK 的 `apksigner verify` 校验每个 Debug APK，仅在签名有效后上传 `mpvEx-installable-debug-<SHA>` Artifact。安装时应下载这个 Artifact 并解压选择对应 ABI（不确定时用 universal）；`mpvEx-standard-release-unsigned-<SHA>` 仍是未签名产物，不能直接安装。

Debug 构建用于测试；不同 runner 自动生成的 Debug 证书可能不同，后续跨构建覆盖安装可能需要先卸载旧测试版。密钥和 keystore 不上传为 Artifact。
