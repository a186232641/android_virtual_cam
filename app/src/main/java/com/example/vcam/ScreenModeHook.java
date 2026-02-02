package com.example.vcam;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.List;
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
    private Surface screenSurface;
    private Camera currentCamera;
    
    // Camera2 相关
    private Surface c2VirtualSurface;
    private SurfaceTexture c2VirtualTexture;
    private Surface c2PreviewSurface;
    private CameraDevice.StateCallback c2StateCallback;
    
    // 状态
    private boolean permissionRequested = false;
    private int previewWidth = 1280;
    private int previewHeight = 720;
    
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
        
        // Hook Camera1 API
        hookCamera1(lpparam);
        
        // Hook Camera2 API
        hookCamera2(lpparam);
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
                    
                    SurfaceTexture originalTexture = (SurfaceTexture) param.args[0];
                    
                    // 创建假的 SurfaceTexture 给相机
                    if (fakeSurfaceTexture == null) {
                        fakeSurfaceTexture = new SurfaceTexture(10);
                    }
                    
                    // 创建 Surface 用于接收屏幕内容
                    screenSurface = new Surface(originalTexture);
                    
                    // 替换参数
                    param.args[0] = fakeSurfaceTexture;
                    
                    currentCamera = (Camera) param.thisObject;
                    
                    XposedBridge.log(TAG + "[Camera1] setPreviewTexture 已拦截");
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
                protected void beforeHookedMethod(MethodHookParam param) {
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
                    
                    // Hook StateCallback.onOpened
                    hookCameraDeviceOpened(c2StateCallback.getClass());
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
                        
                        hookCameraDeviceOpened(c2StateCallback.getClass());
                    }
                }
            );
        }
        
        // Hook CaptureRequest.Builder.addTarget
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            Surface.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || param.args[0] == null) return;
                    
                    Surface originalSurface = (Surface) param.args[0];
                    
                    // 保存原始预览 Surface
                    if (!originalSurface.toString().contains("Surface(name=null)")) {
                        c2PreviewSurface = originalSurface;
                    }
                    
                    // 替换为虚拟 Surface
                    if (c2VirtualSurface != null) {
                        param.args[0] = c2VirtualSurface;
                        XposedBridge.log(TAG + "[Camera2] addTarget 已替换");
                    }
                }
            }
        );
        
        // Hook CaptureRequest.Builder.build
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "build",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled()) return;
                    
                    // 开始屏幕流到预览 Surface
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
                protected void beforeHookedMethod(MethodHookParam param) {
                    previewWidth = (int) param.args[0];
                    previewHeight = (int) param.args[1];
                    
                    XposedBridge.log(TAG + "[Camera2] ImageReader 尺寸: " + previewWidth + "x" + previewHeight);
                    
                    showToast("屏幕模式\n分辨率: " + previewWidth + "x" + previewHeight);
                }
            }
        );
    }
    
    private void hookCameraDeviceOpened(Class<?> callbackClass) {
        XposedHelpers.findAndHookMethod(
            callbackClass,
            "onOpened",
            CameraDevice.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled()) return;
                    
                    // 创建虚拟 Surface
                    createVirtualSurface();
                    
                    CameraDevice device = (CameraDevice) param.args[0];
                    
                    XposedBridge.log(TAG + "[Camera2] onOpened 已拦截");
                    
                    // Hook createCaptureSession
                    hookCreateCaptureSession(device.getClass());
                }
            }
        );
    }
    
    private void hookCreateCaptureSession(Class<?> deviceClass) {
        // Hook createCaptureSession (List 版本)
        XposedHelpers.findAndHookMethod(
            deviceClass,
            "createCaptureSession",
            List.class,
            CameraCaptureSession.StateCallback.class,
            Handler.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || c2VirtualSurface == null) return;
                    
                    // 替换 Surface 列表
                    param.args[0] = Arrays.asList(c2VirtualSurface);
                    
                    XposedBridge.log(TAG + "[Camera2] createCaptureSession 已替换");
                }
            }
        );
        
        // Hook createCaptureSession (SessionConfiguration 版本, Android P+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(
                    deviceClass,
                    "createCaptureSession",
                    SessionConfiguration.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isDisabled() || c2VirtualSurface == null) return;
                            
                            SessionConfiguration config = (SessionConfiguration) param.args[0];
                            OutputConfiguration outputConfig = new OutputConfiguration(c2VirtualSurface);
                            
                            SessionConfiguration newConfig = new SessionConfiguration(
                                config.getSessionType(),
                                Arrays.asList(outputConfig),
                                config.getExecutor(),
                                config.getStateCallback()
                            );
                            
                            param.args[0] = newConfig;
                            
                            XposedBridge.log(TAG + "[Camera2] createCaptureSession (SessionConfig) 已替换");
                        }
                    }
                );
            } catch (Exception e) {
                XposedBridge.log(TAG + "Hook SessionConfiguration 失败: " + e.getMessage());
            }
        }
    }
    
    private void createVirtualSurface() {
        if (c2VirtualTexture != null) {
            c2VirtualTexture.release();
        }
        if (c2VirtualSurface != null) {
            c2VirtualSurface.release();
        }
        
        c2VirtualTexture = new SurfaceTexture(15);
        c2VirtualSurface = new Surface(c2VirtualTexture);
        
        XposedBridge.log(TAG + "虚拟 Surface 已创建");
    }
    
    private void requestPermissionAndStartStream() {
        if (appContext == null) {
            XposedBridge.log(TAG + "Context 未初始化");
            return;
        }
        
        // 确定输出 Surface
        Surface outputSurface = null;
        if (screenSurface != null) {
            outputSurface = screenSurface;
        } else if (c2PreviewSurface != null) {
            outputSurface = c2PreviewSurface;
        }
        
        if (outputSurface == null) {
            XposedBridge.log(TAG + "没有可用的输出 Surface");
            return;
        }
        
        final Surface finalOutput = outputSurface;
        
        // 启动前台服务 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ScreenCaptureService.start(appContext);
        }
        
        // 开始屏幕流
        streamManager.startStream(finalOutput, previewWidth, previewHeight);
        
        showToast("屏幕录制模式已启动");
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
