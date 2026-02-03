package com.example.vcam;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 屏幕模式 Hook
 * 将摄像头预览替换为实时屏幕内容
 */
public class ScreenModeHook implements IXposedHookLoadPackage {
    private static final String TAG = "【VCAM】[ScreenMode]";
    
    // 屏幕流管理器
    private ScreenStreamManager streamManager;
    
    // 上下文
    private Context appContext;
    private Handler mainHandler;
    
    // Camera1 相关
    private SurfaceTexture fakeSurfaceTexture;
    private SurfaceTexture originalTexture;  // 保存原始的 SurfaceTexture
    private Surface screenSurface;
    private Camera currentCamera;
    
    // Camera2 相关
    private Surface c2VirtualSurface;
    private SurfaceTexture c2VirtualTexture;
    private Surface c2PreviewSurface;  // 应用的预览 Surface，屏幕内容输出到这里
    private CameraDevice.StateCallback c2StateCallback;
    
    // 状态
    private boolean permissionRequested = false;
    private int previewWidth = 1280;
    private int previewHeight = 720;
    
    // 当前 Activity (用于 PiP)
    private Activity currentActivity;
    
    /**
     * 检查是否启用屏幕模式
     */
    public static boolean isScreenModeEnabled() {
        File screenModeFile = new File(
            Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/screen_mode.jpg"
        );
        return screenModeFile.exists();
    }
    
    /**
     * 检查是否启用自动 PiP 模式
     */
    private boolean isAutoPipEnabled() {
        File pipFile = new File(
            Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/auto_pip.jpg"
        );
        return pipFile.exists();
    }
    
    /**
     * 检查是否禁用
     */
    private boolean isDisabled() {
        File disableFile = new File(
            Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/disable.jpg"
        );
        return disableFile.exists();
    }
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只在屏幕模式启用时才 Hook
        if (!isScreenModeEnabled()) {
            XposedBridge.log(TAG + "屏幕模式未启用，跳过");
            return;
        }
        
        XposedBridge.log(TAG + "屏幕模式已启用，开始 Hook: " + lpparam.packageName);
        
        mainHandler = new Handler(Looper.getMainLooper());
        streamManager = ScreenStreamManager.getInstance();
        
        // Hook Application.onCreate 获取 Context
        hookApplicationCreate(lpparam);
        
        // Hook Activity 生命周期 (用于 PiP)
        hookActivityLifecycle(lpparam);
        
        // Hook Camera1 API
        hookCamera1(lpparam);
        
        // Hook Camera2 API
        hookCamera2(lpparam);
    }
    
    private void hookActivityLifecycle(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook Activity.onResume 获取当前 Activity
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onResume",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    currentActivity = (Activity) param.thisObject;
                    
                    // 如果启用了自动 PiP，设置 PiP 参数
                    if (isAutoPipEnabled() && streamManager.isStreaming()) {
                        PipModeHelper.enablePipSupport(currentActivity);
                    }
                }
            }
        );
        
        // Hook Activity.onUserLeaveHint (用户按 Home 键时触发)
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onUserLeaveHint",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    
                    // 如果正在屏幕录制且启用了自动 PiP，进入画中画模式
                    if (isAutoPipEnabled() && streamManager.isStreaming()) {
                        XposedBridge.log(TAG + "用户离开，尝试进入 PiP 模式");
                        PipModeHelper.enterPipMode(activity);
                    }
                }
            }
        );
        
        // Hook Activity.onPictureInPictureModeChanged
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onPictureInPictureModeChanged",
            boolean.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean inPipMode = (boolean) param.args[0];
                    XposedBridge.log(TAG + "PiP 模式变化: " + (inPipMode ? "进入" : "退出"));
                    
                    if (inPipMode) {
                        showToast("已进入画中画模式\n可以操作其他应用了");
                    }
                }
            }
        );
    }
    
    private void hookApplicationCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation",
            lpparam.classLoader,
            "callApplicationOnCreate",
            Application.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.args[0];
                    appContext = app.getApplicationContext();
                    
                    // 初始化屏幕流管理器
                    streamManager.init(appContext);
                    
                    XposedBridge.log(TAG + "获取到应用 Context: " + appContext.getPackageName());
                }
            }
        );
    }
    
    private void hookCamera1(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook setPreviewTexture
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera",
            lpparam.classLoader,
            "setPreviewTexture",
            SurfaceTexture.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || param.args[0] == null) return;
                    
                    // 保存原始的 SurfaceTexture，屏幕内容将输出到这里
                    originalTexture = (SurfaceTexture) param.args[0];
                    screenSurface = new Surface(originalTexture);
                    
                    // 创建假的 SurfaceTexture 给相机（相机输出到这里会被丢弃）
                    if (fakeSurfaceTexture == null) {
                        fakeSurfaceTexture = new SurfaceTexture(10);
                    }
                    
                    // 替换参数，让相机输出到假的 Surface
                    param.args[0] = fakeSurfaceTexture;
                    
                    currentCamera = (Camera) param.thisObject;
                    
                    XposedBridge.log(TAG + "[Camera1] setPreviewTexture 已拦截，屏幕将输出到原始 Surface");
                }
            }
        );
        
        // Hook setPreviewDisplay
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera",
            lpparam.classLoader,
            "setPreviewDisplay",
            SurfaceHolder.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || param.args[0] == null) return;
                    
                    SurfaceHolder holder = (SurfaceHolder) param.args[0];
                    // 保存原始 Surface，屏幕内容将输出到这里
                    screenSurface = holder.getSurface();
                    
                    // 创建假的 SurfaceTexture
                    if (fakeSurfaceTexture == null) {
                        fakeSurfaceTexture = new SurfaceTexture(11);
                    }
                    
                    currentCamera = (Camera) param.thisObject;
                    
                    // 使用假的 texture 替代
                    try {
                        currentCamera.setPreviewTexture(fakeSurfaceTexture);
                        param.setResult(null);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + "[Camera1] setPreviewDisplay 替换失败: " + e.getMessage());
                    }
                    
                    XposedBridge.log(TAG + "[Camera1] setPreviewDisplay 已拦截");
                }
            }
        );
        
        // Hook startPreview
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera",
            lpparam.classLoader,
            "startPreview",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isDisabled()) return;
                    
                    Camera camera = (Camera) param.thisObject;
                    
                    // 获取预览尺寸
                    try {
                        Camera.Parameters params = camera.getParameters();
                        Camera.Size size = params.getPreviewSize();
                        previewWidth = size.width;
                        previewHeight = size.height;
                        
                        XposedBridge.log(TAG + "[Camera1] 预览尺寸: " + previewWidth + "x" + previewHeight);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + "[Camera1] 获取预览尺寸失败: " + e.getMessage());
                    }
                    
                    // 请求屏幕录制权限并开始流
                    requestPermissionAndStartStream();
                }
            }
        );
    }

    
    private void hookCamera2(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook CameraManager.openCamera (Handler 版本)
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager",
            lpparam.classLoader,
            "openCamera",
            String.class,
            CameraDevice.StateCallback.class,
            Handler.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled()) return;
                    
                    c2StateCallback = (CameraDevice.StateCallback) param.args[1];
                    
                    XposedBridge.log(TAG + "[Camera2] openCamera 已拦截");
                }
            }
        );
        
        // Hook CameraManager.openCamera (Executor 版本, Android P+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraManager",
                lpparam.classLoader,
                "openCamera",
                String.class,
                Executor.class,
                CameraDevice.StateCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isDisabled()) return;
                        
                        c2StateCallback = (CameraDevice.StateCallback) param.args[2];
                        
                        XposedBridge.log(TAG + "[Camera2] openCamera (Executor) 已拦截");
                    }
                }
            );
        }
        
        // Hook CaptureRequest.Builder.addTarget - 捕获预览 Surface
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            Surface.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || param.args[0] == null) return;
                    
                    Surface surface = (Surface) param.args[0];
                    String surfaceInfo = surface.toString();
                    
                    // 保存预览 Surface（不是 ImageReader 的 Surface）
                    // ImageReader 的 Surface 通常显示为 "Surface(name=null)"
                    if (!surfaceInfo.contains("Surface(name=null)")) {
                        c2PreviewSurface = surface;
                        XposedBridge.log(TAG + "[Camera2] 捕获到预览 Surface: " + surfaceInfo);
                    }
                }
            }
        );
        
        // Hook CameraCaptureSession.setRepeatingRequest - 在这里启动屏幕流
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl",
            lpparam.classLoader,
            "setRepeatingRequest",
            CaptureRequest.class,
            CameraCaptureSession.CaptureCallback.class,
            Handler.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isDisabled()) return;
                    
                    XposedBridge.log(TAG + "[Camera2] setRepeatingRequest 已拦截");
                    
                    // 开始屏幕流
                    if (c2PreviewSurface != null) {
                        requestPermissionAndStartStream();
                    }
                }
            }
        );
        
        // Hook ImageReader.newInstance 获取分辨率
        XposedHelpers.findAndHookMethod(
            "android.media.ImageReader",
            lpparam.classLoader,
            "newInstance",
            int.class, int.class, int.class, int.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int width = (int) param.args[0];
                    int height = (int) param.args[1];
                    
                    // 只在合理范围内更新
                    if (width > 100 && height > 100) {
                        previewWidth = width;
                        previewHeight = height;
                        XposedBridge.log(TAG + "[Camera2] ImageReader 尺寸: " + previewWidth + "x" + previewHeight);
                    }
                }
            }
        );
    }
    
    private void hookCameraDeviceOpened(Class<?> callbackClass) {
        // 不再需要这个方法
    }
    
    private void hookCreateCaptureSession(Class<?> deviceClass) {
        // 不再需要这个方法
    }
    
    private void createVirtualSurface() {
        // 不再需要虚拟 Surface
    }
    
    private void requestPermissionAndStartStream() {
        if (appContext == null) {
            XposedBridge.log(TAG + "Context 未初始化");
            return;
        }
        
        // 确定输出 Surface（应用的预览 Surface）
        Surface outputSurface = null;
        if (screenSurface != null && screenSurface.isValid()) {
            outputSurface = screenSurface;
            XposedBridge.log(TAG + "使用 Camera1 的 screenSurface");
        } else if (c2PreviewSurface != null && c2PreviewSurface.isValid()) {
            outputSurface = c2PreviewSurface;
            XposedBridge.log(TAG + "使用 Camera2 的 c2PreviewSurface");
        }
        
        if (outputSurface == null) {
            XposedBridge.log(TAG + "没有可用的输出 Surface");
            return;
        }
        
        final Surface finalOutput = outputSurface;
        
        XposedBridge.log(TAG + "开始屏幕流，输出到: " + finalOutput.toString() + 
            " 尺寸: " + previewWidth + "x" + previewHeight);
        
        // 开始屏幕流
        streamManager.startStream(finalOutput, previewWidth, previewHeight);
        
        showToast("屏幕模式已启动\n" + previewWidth + "x" + previewHeight);
    }
    
    private void showToast(String message) {
        if (appContext != null && mainHandler != null) {
            // 检查是否禁用 Toast
            File noToastFile = new File(
                Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/no_toast.jpg"
            );
            if (noToastFile.exists()) return;
            
            mainHandler.post(() -> {
                try {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    XposedBridge.log(TAG + "Toast 显示失败: " + e.getMessage());
                }
            });
        }
    }
}
