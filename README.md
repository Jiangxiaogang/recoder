# 记录仪（Recorder）

一个运行在 **Android 5.1 (API 22)** 上的视频记录仪 APP：

- 启动 APP 后**手动**点击“开始录制”；
- 按 **POWER 键熄屏**后录制继续（也可以直接退回桌面）；
- 录制文件支持按 **1 / 3 / 5 / 10 / 30 / 60 分钟**自动切割（另附“不分割”选项，默认 5 分钟）；
- 分辨率和帧率默认选中**设备支持的最高档**；
- **摄像头、分辨率、帧率、视频方向**可在界面中选择（摄像头列表来自设备，切换后分辨率/帧率自动刷新）；
- 视频方向可**强制横屏 / 竖屏 / 自动跟随手机**（默认自动）；
- **蓝牙键盘 / 蓝牙耳机的播放暂停键（KEYCODE_MEDIA_PLAY_PAUSE）可一键开始/停止录制**；开始播放一声稍长的「叮~」、停止播放两声较短的「咚咚」，熄屏状态同样有效。

## 1. 目录结构

```
recoder/
├─ android.bat                    # 环境脚本（已有）
├─ settings.gradle
├─ build.gradle
├─ gradle.properties              # 含 android.overridePathCheck=true（路径含中文需要）
├─ local.properties
├─ keystore.properties            # release 签名配置（勿外传）
├─ keystore/recorder-release.jks  # release 签名密钥库
├─ release/                       # 打好的签名包
├─ app/
│  ├─ build.gradle                # minSdk 22 / targetSdk 28 / compileSdk 28
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/example/recorder/
│     │  ├─ MainActivity.java     # 参数选择与开始/停止
│     │  ├─ RecorderService.java  # 前台服务、通知、唤醒锁、离屏预览表面
│     │  ├─ RecorderEngine.java   # 相机 + MediaRecorder + 分段逻辑
│     │  ├─ PreviewSurface.java   # 1x1 悬浮 SurfaceView（离屏预览）
│     │  ├─ RecorderConfig.java   # 参数模型
│     │  ├─ CameraDevices.java    # 设备摄像头列表
│     │  └─ CameraCapabilities.java # 查询设备支持的分辨率/帧率
│     └─ res/…                    # 布局、文案、图标
└─ README.md
```

## 2. 构建

调试包：

```bat
cd /d E:\学习\android\recoder
android.bat gradle.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

Release 签名包：

```bat
android.bat gradle.bat assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`（另复制了一份到 `release\Recorder-1.0-release.apk`）

签名信息在根目录 `keystore.properties` 中配置（已加入 `.gitignore`）：

```
storeFile=keystore/recorder-release.jks
storePassword=recorder2026
keyAlias=recorder
keyPassword=recorder2026
```

- 密钥库：`keystore\recorder-release.jks`，RSA 2048，有效期 10000 天，DN=`CN=Recorder, OU=Dev, O=Recorder, L=Beijing, ST=Beijing, C=CN`
- 签名方案：v1(JAR) + v2，`apksigner verify` 通过
- 证书 SHA-256：`68609c269d6d1a159c60133552de81397c83a243aaa07d5f5e3ae4280a437556`

安装到设备（debug 与 release 签名不同，需先卸载旧包）：

```bat
android.bat adb uninstall com.example.recorder
android.bat adb install -r release\Recorder-1.0-release.apk
```

> 构建用的是环境里自带的 Gradle 8.0 + AGP 8.0.2 + JDK 17，不需要 Android Studio。

## 3. 使用

1. 打开“记录仪”，选择摄像头、分辨率、帧率、视频方向、分段时长（默认：后置摄像头 + 最高分辨率 + 最高帧率 + 自动方向 + 5 分钟分段）；
2. 点“开始录制”，首次运行会申请相机 / 麦克风 / 存储权限；
3. 录制中会出现常驻通知（含“停止”按钮）；
4. 按 **POWER 键熄屏**（或返回桌面），录制继续；
5. 再进入 APP 点“停止录制”，或直接在通知上点“停止”；
6. 蓝牙键盘/耳机的**播放暂停键**可随时开始/停止录制（开始双短音、停止单短音）。打开过一次 APP 后服务会进入待机，通知显示“待机中：按蓝牙播放/暂停键开始录制”；点通知里的“退出待机”会结束服务（按键失效，直到再次打开 APP）。

文件保存到：`/storage/emulated/0/DCIM/Recorder/yyyy-MM-dd/REC_yyyyMMdd_HHmmss_XX.mp4`
（按录制日期自动建立 `2026-11-06` 这样的子目录，跨天续录会写入新日期目录）

> 录制过程中文件名为 `..._XX.mp4.tmp`，正常结束（含分段到点）后立即改名为 `..._XX.mp4` 并通知媒体库；
> 因此相册/媒体库里不会出现半截文件。若录制异常中断（进程被杀等），会保留 `.tmp` 便于识别。

## 4. 关键实现说明

| 需求 | 实现方式 |
| --- | --- |
| 熄屏继续录制 | 相机与 MediaRecorder 全部由 **前台服务** 持有，不依赖 Activity 窗口；录制期间持有 `PARTIAL_WAKE_LOCK` 保持 CPU 运行 |
| 无窗口也能录 | `PreviewSurface` 通过 WindowManager 添加一个 1x1 的 `SurfaceView`（`TYPE_SYSTEM_OVERLAY`）作为相机预览目标，SurfaceFlinger 持续消费预览帧，即使屏幕关闭、Activity 不可见也不中断；若悬浮窗不可用则回退为 `SurfaceTexture` |
| 文件切割 | `MediaRecorder.setMaxDuration()` + `MEDIA_RECORDER_INFO_MAX_DURATION_REACHED` 回调；可选 1 / 3 / 5 / 10 / 30 / 60 分钟或不分割，默认 5 分钟。到点后系统自动停止当前文件，引擎关闭文件并立即开始下一段（大文件会持续切分） |
| 分辨率 / 帧率 | 分辨率只列**真正可录制**的尺寸：`CamcorderProfile` 枚举出的档位 + 相机 `getSupportedVideoSizes()` 声明给 MediaRecorder 的尺寸，并要求 `getSupportedPreviewSizes()` 也支持（引擎会让预览尺寸与录制尺寸一致）。帧率再按所选分辨率过滤：相机上报区间 ∩ 编码器 `VideoCapabilities.getSupportedFrameRatesFor(w,h)`，设备只上报自适应区间（如 7.5~30fps）时补充 30 / 24 / 15。切换分辨率/摄像头都会刷新帧率列表。编码参数用 `CamcorderProfile` 对齐 H.264 profile/level，避免 `start failed -19` |
| 摄像头选择 | 用 `Camera.getNumberOfCameras()` + `Camera.getCameraInfo()` 列出设备摄像头（后置/前置/外接），按 `Camera.open(id)` 打开所选摄像头，`CamcorderProfile` 与录制参数也都按该 id 查询 |
| 画面方向 | 用「视频方向」下拉控制：横屏 → `setOrientationHint(0°)`（文件为 1920×1080 等横向）；竖屏 → `setOrientationHint(90°，即传感器方向)`（横向缓冲 + 旋转标记，播放器显示为 1080×1920 等竖向）；自动 → 按**每段录制开始时**的屏幕方向计算 `hint = (传感器方向 - 屏幕旋转角度 + 360) % 360`，竖持正立、横持也正立 |
| 蓝牙按键启停 | 前台服务持有活跃 `MediaSession`，`onMediaButtonEvent` 接收 `KEYCODE_MEDIA_PLAY_PAUSE`（兼容 MEDIA_PLAY/PAUSE/HEADSETHOOK），500ms 去抖后切换录制；待机时服务常驻前台，通知显示“待机中” |
| 开始/停止提示音 | 播放内置 WAV：`res/raw/start.wav`（1.2kHz 单频「叮~」，380ms，带衰减尾音）和 `res/raw/stop.wav`（两声「咚咚」，各 110ms，间隔 70ms），用 `SoundPool` 播放；在引擎真正进入/退出 RECORDING 状态时触发。替换这两个 WAV 文件即可自定义提示音 |

- 编码：H.264 视频 + AAC 音频，容器 MP4；
- 码率：优先取 `CamcorderProfile` 的码率，按实际帧率等比例调整。

## 5. 已知限制 / 注意事项

1. **没有实时预览画面**：为了在熄屏、退到后台时都能稳定录制，界面只显示状态，不做取景预览。
2. **方向规则**：横屏/竖屏是强制的，只改变文件的旋转标记、不改缓冲区尺寸；若手持方向与所选方向不一致，画面内容会横躺或竖躺（例如竖着拿手机却选横屏）。「自动」按每段录制开始时的屏幕方向计算，录制中途转屏从下一段起生效。
3. **相邻分段之间有约 0.3 秒空档**：Android 5.1 的 MediaRecorder 不支持 `setNextOutputFile()`（API 26+ 才有），只能用“停止—重启”的方式续录。
4. **单次连续录制时长**受设备编码器限制，超长时间（数小时）建议定期停止再开始。
5. 部分国产 ROM 的省电策略会强制结束后台相机，若熄屏后录制中断，可在系统设置中把本应用加入“后台运行 / 自启动”白名单，并关闭对其的省电限制。
6. Android 6.0 及以上：`SYSTEM_ALERT_WINDOW` 需要用户手动开启（设置 → 应用 → 显示在其他应用上层），否则会自动退回 `SurfaceTexture` 方案，个别机型可能录像卡住；本 APP 的目标机型是 5.1，在该版本上安装即授予。
7. 屏幕熄屏后相机白平衡/曝光会保持熄屏前的状态，不会重新收敛。
8. 选择 60 分钟分段时，1080p 单个文件可能超过 4GB；若保存到 FAT32 格式的物理 SD 卡会写不进去，建议改用较短分段或保存在内置存储。
9. **硬件按键会与音乐 APP 抢占媒体键**：本服务保持活跃 MediaSession 时，播放/暂停键会触发录制而不是续播音乐；如需边听歌边用，建议后续改成“上一曲/下一曲”双键方案。
10. 手机重启后需要先打开一次 APP（服务才会进入待机），硬件按键才生效；如需开机自启，可后续增加 `BOOT_COMPLETED` 广播。

## 6. 排错

```bat
android.bat adb logcat -s RecorderEngine RecorderService PreviewSurface
```

- “打开相机失败”：相机被其它应用占用，或未授予相机权限；
- “启动录制失败，请检查分辨率/帧率是否被设备支持”：换一档分辨率/帧率再试；
- 录制文件为 0 字节时会自动删除并记录到 logcat；
- 若目录里残留 `*.mp4.tmp`，说明那次录制没有正常结束（文件可能不完整），确认后自行删除即可。

## 7. 真机实测结果

测试设备：**nubia NX529J，Android 5.1.1（API 22）**，1080×1920 屏。

| 测试项 | 结果 |
| --- | --- |
| 安装、启动、相机能力查询 | ✅ 正常，相机能力查询与分辨率下拉框均正常 |
| **摄像头下拉** | ✅ 列表来自 `Camera.getNumberOfCameras()`，界面显示「后置摄像头」；录制日志 `开始录制 camera=0 1920x1080@30fps`；录制中该下拉自动禁用。⚠️ 本测试机相机服务只上报 1 个摄像头（`dumpsys media.camera: Number of camera devices: 1`），因此未能实测前后摄切换 |
| 手动开始/停止 | ✅ 状态、计时、当前文件名、通知栏图标均正常 |
| **POWER 熄屏继续录制** | ✅ 熄屏约 40 秒，`dumpsys power` 为 Asleep/Display OFF，文件由 16MB 持续增长到 54MB，计时继续 |
| **1 分钟自动切割（边熄屏边录）** | ✅ 熄屏录制 2.5 分钟生成 3 个文件：`11:13:00_01`、`11:14:01_02`、`11:15:02_03`，前两段 MP4 时长分别为 **60.03s / 60.03s** |
| 分辨率列表 | ✅ 只列可录制尺寸：1920×1080 / 1280×720 / 800×480 / 720×480 / 640×480 / 640×360 / 480×360 / 480×320 / 352×288 / 320×240 / 176×144 / 160×120（之前的 576×432、1440×1080、1080×1080、1280×960 等预览专用尺寸已移除） |
| 列表尺寸实测可录 | ✅ 逐个录 5 秒并解析 MP4：800×480、640×480、640×360、480×320 均输出对应尺寸（avc1，≈30fps），无启动失败 |
| 分辨率设置 | ✅ 选 640×480 输出 tkhd=640×480；选 1920×1080 输出 tkhd=1920×1080，均正常录制 |
| 帧率设置 | ✅ 选 24fps 实测平均 **23.9fps**；选 15fps 实测 **14.86 / 14.79fps**，重启 APP 后选择可恢复 |
| **按日期分目录** | ✅ 文件写入 `/sdcard/DCIM/Recorder/2026-11-06/REC_20261106_112911_01.mp4`，界面同步显示当天目录 |
| **录制中先写 .tmp** | ✅ 录制中目录里是 `REC_..._01.mp4.tmp`；停止后变为 `REC_..._01.mp4`；1 分钟分段到点时前一段立即改名、下一段继续写 `.tmp` |
| **默认档位** | ✅ 清空数据后打开：分辨率 1920×1080（设备最高）、帧率 30 fps（设备最高）、视频方向 自动、分段 5 分钟 |
| **分段选项** | ✅ 1 / 3 / 5 / 10 / 30 / 60 分钟 + 不分割；选 10 分钟后 `setMaxDuration=600000` 正常开始录制 |
| **强制横屏** | ✅ 竖持手机录制，解析 MP4 视频轨旋转矩阵 **0°**，文件为 1920×1080 横向 |
| **强制竖屏** | ✅ 竖持手机录制，解析 MP4 视频轨旋转矩阵 **90°**，播放器显示为 1080×1920 竖向 |
| **自动方向** | ✅ 竖持开始录制 → 旋转矩阵 90°；中途转为横屏后，下一段（1 分钟分段）→ 旋转矩阵 0°，两段均为 1920×1080 缓冲 |
| **蓝牙播放暂停键（亮屏）** | ✅ `input keyevent 85` → 开始录制并播放「叮~」；再按一次 → 停止并播放「咚咚」，文件由 `.tmp` 改名为 `.mp4` |
| **蓝牙播放暂停键（熄屏）** | ✅ `mWakefulness=Asleep` 状态下按键成功开始录制（文件持续增长），再次按键成功停止 |
| **待机通知** | ✅ 停止后通知变为“待机中：按蓝牙播放/暂停键开始录制”，并带“退出待机”按钮 |
| 画面方向 | ✅ 视频轨旋转矩阵 90°，竖屏拍摄回放正立 |
| 停止后收尾 | ✅ 通知消失、唤醒锁释放、文件完整；全程 logcat 无异常 |

> 说明：测试期间临时改过系统的屏幕超时，已恢复为原来的 300000ms。测试录像仍保留在设备的
> `/sdcard/DCIM/Recorder/`，可自行删除。

Lint 检查：**No issues found**。
