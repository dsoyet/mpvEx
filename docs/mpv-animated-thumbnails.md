# Mpv 品牌与动态缩略图

## 品牌和安装身份

桌面和应用内品牌统一为 Mpv。Release applicationId 为 `app.mpv.personal`，namespace、Kotlin package 和源码目录继续使用 `app.marlboroadvance.mpvex`。FileProvider 与网络 Provider 已使用 `${applicationId}` / context.packageName；播放器结果 Intent 改用 BuildConfig.APPLICATION_ID。HTTP / 文件 / content 外部播放关联不改动。现有上游作者、链接、许可证和 About 署名保留，并添加 Fork 来源说明。

已目视检查原桌面图标，只含播放符号，没有 mpvEx 字样，因此保留。内部类名、主题名、旧 SharedPreferences 名、兼容 XML 根元素 `mpvExSettings`、技术名称 mpv.conf / input.conf 和原项目链接不做品牌替换。新应用有独立的数据、权限与缓存；可用原有 XML 功能导入设置，但 Android URI 授权可能需要重新选择目录。

## 动态缩略图设置

| 设置 | 默认 | 范围 |
| --- | --- | --- |
| 启用 | 开启 | 开 / 关 |
| 帧数 | 5 | 2–10 |
| 切换间隔 | 1500 ms | 500–3000 ms（界面提供 100 ms 间隔） |
| 起始位置 | 10% | 0–40% |
| 结束位置 | 90% | 60–100% |
| 跳过黑屏 | 开启 | 开 / 关 |

所有值通过现有 PreferenceStore 保存、设置搜索可检索、通用 XML 导入导出兼容。新增英语、简体中文资源，其余语言使用默认资源。静态 Fixed / Percentage / Smart 策略保持独立。

## 生成和显示

静态图优先。当前 LazyColumn / LazyVerticalGrid 的 visibleItemsInfo 与缩略图实际窗口相交共同决定可见性，且 Lifecycle 必须为 RESUMED。条目持续可见 400ms 后才请求帧；离屏、组件移除、后台或关闭设置，LaunchedEffect 会取消请求和切换循环。搜索、最近播放、播放列表和普通列表 / 网格均使用稳定条目键，避免排序、过滤和目录切换造成串图。

默认采样 10%、30%、50%、70%、90%；转换为秒，限制到 duration−0.1，去重。未知时长使用原静态图。动画最长边 256px，静态 1024px 不变。黑屏检测和确定性评分复用原组件，每个位置最多再试 +2%、−2%，找到非黑屏立即停止；全部黑屏保留最佳图，全失败跳过该帧。实际尝试过的时间戳跨整组去重，避免短视频重复 seek。

完成后按间隔直接切换 Bitmap，末帧回到首帧；只有一帧不启动定时循环。循环不会解码、检测黑屏或创建播放器。

## 并发、缓存和取消

动画生成最多 2 个并发序列，每个序列按时间位置串行取帧。引用计数去重保证同视频同配置只有一个共享任务，最后一个可见等待者离开即取消排队或生成。配置变化只取消过期请求，保留已注册的新配置请求。动画逐帧借用原静态仓库的 3 个解码许可；动画最多占 2 个，给静态请求保留容量，不为动画启动目录级预生成。

动画内存缓存独立、按 Bitmap 实际内存计费，最高 64 MiB，并限制条目数量以避免失败序列的元数据无限积累。LRU 驱逐只释放引用，不回收仍可能由 UI 使用的 Bitmap；尚未发布的取消工作和丢弃候选会回收。

磁盘位于私有 cacheDir/animated-thumbnails-v1，JPEG 序列加 count 标记。先写临时目录，再在同一文件系统原子重命名；只读取完整序列，损坏缓存会再生成。读取更新访问时间，启动及写入后按近似 LRU 裁剪至 256 MiB。文件 IO、解码、旋转和检测均在后台；旋转元数据每序列只读取一次。清理原缩略图按钮同时清理动画缓存，并更新 epoch，旧请求无法写入新缓存命名空间。

键包含路径 / URI、大小、修改时间、时长、版本、分辨率、帧数、起止百分比与黑屏开关。启用与切换间隔不影响帧内容，不强制重新解码。

## 验证和构建

沿用用户要求：只通过 GitHub Actions 执行 `./gradlew testStandardDebugUnitTest assembleStandardRelease -PtargetAbi=arm64-v8a`，不在本地编译。云端校验只有一个 arm64 APK、applicationId、应用标签及 Provider 和 Activity 声明。APK 输出为 `Mpv-版本-arm64-v8a.apk`，Artifact 为 `Mpv-Android`。签名分支、密钥处理、Secrets 和版本号不做修改。

测试涵盖静态既有策略、动态均匀取帧和候选边界、未知和短时长、缓存指纹与容量、引用计数和排队取消、配置切换、前后台可见性门控，以及循环顺序、单帧无定时器和循环取消。实际云端测试和 APK 结果见最终交付的 Actions 链接。

## 限制与设备验收

FastThumbnails 1.0.0 的批量 API 内部也是逐个 seek，所以第一版仍顺序调用现有 generateAsync，未建立持续解码器。最多每视频 10×3 个候选，帧间检查取消；已经进入原生同步解码的单次调用无法强制中断，完成后不会再处理剩余帧。旋转会产生小尺寸替代位图。

JVM 单测不等价于真机测试。没有设备时，无法确认最终滚动帧率、所有厂商 Bitmap / MediaStore 行为、实际后台切换与外部播放入口的交互效果。需要在设备上检查：默认轮播、快速滚动、排序 / 搜索、后台 / 返回、设置更改 / 重启、缓存清理和普通视频播放。上游更新检查链接继续保留原实现；个人 Fork 的后续发布与自动更新来源不在本次改动范围。

## 修改文件

- `.github/workflows/build.yml`
- `app/build.gradle.kts`
- `app/src/main/java/app/marlboroadvance/mpvex/di/DatabaseModule.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/AnimatedThumbnailConfig.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/AnimatedThumbnailDiskCache.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/AnimatedThumbnailPlayback.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/AnimatedThumbnailRepository.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/ThumbnailByteCache.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/ThumbnailRepository.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/domain/thumbnail/VisibleThumbnailRequests.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/preferences/AdvancedPreferences.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/preferences/SettingsManager.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/cards/AnimatedVideoThumbnail.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/cards/VideoCard.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/filesystem/FileSystemBrowserScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/folderlist/FolderListScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/playlist/PlaylistDetailScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/recentlyplayed/RecentlyPlayedScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/states/PermissionDeniedState.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/browser/videolist/VideoListScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/mediainfo/MediaInfoActivity.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/AboutScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/AdvancedPreferencesScreen.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/AnimatedThumbnailPreferences.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/SearchablePreference.kt`
- `app/src/main/res/values-zh-rCN/animated_thumbnail_strings.xml`
- `app/src/main/res/values/animated_thumbnail_strings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/app/marlboroadvance/mpvex/domain/thumbnail/AnimatedThumbnailConfigTest.kt`
- `app/src/test/java/app/marlboroadvance/mpvex/domain/thumbnail/AnimatedThumbnailPlaybackTest.kt`
- `app/src/test/java/app/marlboroadvance/mpvex/domain/thumbnail/ThumbnailByteCacheTest.kt`
- `app/src/test/java/app/marlboroadvance/mpvex/domain/thumbnail/VisibleThumbnailRequestsTest.kt`
- `docs/mpv-animated-thumbnails.md`
