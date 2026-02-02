# VCAM 屏幕录制模式

将摄像头画面替换为实时手机屏幕内容 + 混合音频（系统声音 + 麦克风）

## 工作原理

```
┌─────────────────────────────────────────────────────────────┐
│              目标应用进程 (被 LSPatch 注入后)                  │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              ScreenModeHook (Xposed 模块)             │   │
│  │                                                       │   │
│  │  1. Hook Camera1/Camera2 API                         │   │
│  │  2. 请求 MediaProjection 权限                         │   │
│  │  3. 创建 VirtualDisplay 捕获屏幕                      │   │
│  │  4. 将屏幕内容输出到摄像头预览 Surface                  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## 构建步骤 (Linux)

### 1. 环境准备

```bash
# 安装 JDK 11+
sudo apt install openjdk-11-jdk

# 设置 Android SDK 路径
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

### 2. 构建 APK

```bash
# 添加执行权限
chmod +x build_linux.sh

# 运行构建脚本
./build_linux.sh

# 或者手动构建
./gradlew assembleRelease
```

输出文件: `output/vcam-screen-mode.apk`

## 使用 LSPatch 注入

### 方式一: 使用 LSPatch 应用 (推荐)

1. 在手机上安装 [LSPatch](https://github.com/LSPosed/LSPatch/releases)
2. 安装 `vcam-screen-mode.apk` 作为模块
3. 打开 LSPatch -> 管理 -> 新建补丁
4. 选择目标应用的 APK 文件
5. 在模块列表中勾选 VCAM
6. 点击 "开始打补丁"
7. 安装生成的补丁 APK

### 方式二: 使用命令行 (需要 Java 环境)

```bash
# 下载 LSPatch jar
wget https://github.com/LSPosed/LSPatch/releases/latest/download/lspatch.jar

# 打补丁
java -jar lspatch.jar target-app.apk -m vcam-screen-mode.apk -o output/
```

## 配置选项

在 `/sdcard/DCIM/Camera1/` 目录下创建以下文件来控制行为:

| 文件名 | 作用 |
|--------|------|
| `screen_mode.jpg` | 启用屏幕录制模式 (必须) |
| `disable.jpg` | 临时禁用所有替换 |
| `no_toast.jpg` | 禁用 Toast 提示 |

## 使用流程

1. 在 VCAM 应用中开启 "屏幕录制模式" 开关
2. 打开被注入的目标应用
3. 当应用打开摄像头时，会弹出屏幕录制权限请求
4. 允许后，摄像头画面将显示手机屏幕内容

## 音频说明

- **Android 10+**: 支持捕获系统音频 + 麦克风混合
- **Android 10 以下**: 仅支持麦克风音频

## 注意事项

1. **权限请求**: 每次打开目标应用都需要授权屏幕录制
2. **分辨率**: 屏幕内容会自动缩放到摄像头预览分辨率
3. **性能**: 屏幕录制会增加 CPU/GPU 负载
4. **兼容性**: 部分应用可能有额外的摄像头检测，需要测试

## 文件结构

```
app/src/main/java/com/example/vcam/
├── HookMain.java           # 原有视频文件模式 Hook
├── ScreenModeHook.java     # 屏幕录制模式 Hook (新增)
├── ScreenStreamManager.java # 屏幕流管理器 (新增)
├── ScreenCaptureActivity.java # 权限请求 Activity (新增)
├── ScreenCaptureHelper.java # 屏幕捕获辅助类 (新增)
├── AudioMixerHelper.java   # 音频混合器 (新增)
├── MainActivity.java       # 配置界面
└── VideoToFrames.java      # 视频解码器 (原有)
```

## 故障排除

### 屏幕录制权限被拒绝
- 确保目标应用有 `android.permission.RECORD_AUDIO` 权限
- 部分系统可能需要在设置中手动授权

### 画面黑屏
- 检查是否正确授权了屏幕录制权限
- 查看 Xposed 日志中的 `【VCAM】` 标签

### 画面卡顿
- 降低目标应用的摄像头分辨率设置
- 关闭其他后台应用释放资源
