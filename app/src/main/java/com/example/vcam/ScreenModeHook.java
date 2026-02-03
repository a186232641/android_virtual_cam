package com.example.vcam;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 屏幕模式 Hook - 简化版
 * 将摄像头预览替换为实时屏幕内容
 */
public class ScreenModeHook implements IXposedHookLoadPackage {
    private static final String TAG = "【VCAM】[ScreenMode]";
    private static final int REQUEST_CODE = 19283;
    
    // 上下文
    private Context appContext;
    private Handler mainHandler;
    private Activity currentActivity;
    
    // Camera1 相关
    private SurfaceTexture originalSurfaceTexture;  // 原始的 SurfaceTexture
    private SurfaceTexture fakeSurfaceTexture;      // 假的给相机用
    private SurfaceHolder originalHolder;            // 原始的 SurfaceHolder
    private Surface outputSurface;                   // 输出屏幕内容的 Surface
    
    // 屏幕录制相关
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    
    private int screenWidth = 1280;
    private int screenHeight = 720;
    private int screenDensity = 320;
    
    private boolean permissionGranted = false;
    private int savedResultCode = 0;
    private Intent savedResultData = null;
    
    public static boolean isScreenModeEnabled() {
        File f = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/screen_mode.jpg");
        return f.exists();
    }
    
    private boolean isDisabled() {
        File f = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/disable.jpg");
        return f.exists();
    }
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!isScreenModeEnabled()) {
            return;
        }
        
        XposedBridge.log(TAG + "屏幕模式已启用: " + lpparam.packageName);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Hook Application 获取 Context
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader,
            "callApplicationOnCreate", Application.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.args[0];
                    appContext = app.getApplicationContext();
                    
                    projectionManager = (MediaProjectionManager) 
                        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    
                    WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                    DisplayMetrics metrics = new DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                    screenWidth = metrics.widthPixels;
                    screenHeight = metrics.heightPixels;
                    screenDensity = metrics.densityDpi;
                    
                    XposedBridge.log(TAG + "Context 已获取: " + appContext.getPackageName());
                }
            }
        );

        // Hook Activity.onResume 获取当前 Activity
        XposedHelpers.findAndHookMethod(
            "android.app.Activity", lpparam.classLoader, "onResume",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    currentActivity = (Activity) param.thisObject;
                }
            }
        );
        
        // Hook Activity.onActivityResult 处理权限结果
        XposedHelpers.findAndHookMethod(
            "android.app.Activity", lpparam.classLoader,
            "onActivityResult", int.class, int.class, Intent.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    
                    if (requestCode == REQUEST_CODE) {
                        XposedBridge.log(TAG + "收到权限结果: " + resultCode);
                        if (resultCode == Activity.RESULT_OK && data != null) {
                            savedResultCode = resultCode;
                            savedResultData = new Intent(data);
                            permissionGranted = true;
                            
                            // 创建 MediaProjection 并开始
                            createProjectionAndStart();
                        } else {
                            showToast("屏幕录制权限被拒绝");
                        }
                    }
                }
            }
        );
        
        // ========== Camera1 Hook ==========
        
        // Hook setPreviewTexture
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", lpparam.classLoader,
            "setPreviewTexture", SurfaceTexture.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || param.args[0] == null) return;
                    
                    // 保存原始 SurfaceTexture
                    originalSurfaceTexture = (SurfaceTexture) param.args[0];
                    
                    // 创建假的 SurfaceTexture 给相机
                    if (fakeSurfaceTexture != null) {
                        fakeSurfaceTexture.release();
                    }
                    fakeSurfaceTexture = new SurfaceTexture(10);
                    
                    // 替换参数
                    param.args[0] = fakeSurfaceTexture;
                    
                    XposedBridge.log(TAG + "[C1] setPreviewTexture 已拦截");
                }
            }
        );
        
        // Hook setPreviewDisplay
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", lpparam.classLoader,
            "setPreviewDisplay", SurfaceHolder.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDisabled() || param.args[0] == null) return;
                    
                    // 保存原始 SurfaceHolder
                    originalHolder = (SurfaceHolder) param.args[0];
                    
                    // 创建假的 SurfaceTexture
                    if (fakeSurfaceTexture != null) {
                        fakeSurfaceTexture.release();
                    }
                    fakeSurfaceTexture = new SurfaceTexture(11);
                    
                    // 让相机使用假的 texture
                    Camera camera = (Camera) param.thisObject;
                    try {
                        camera.setPreviewTexture(fakeSurfaceTexture);
                        param.setResult(null);  // 阻止原方法执行
                    } catch (Exception e) {
                        XposedBridge.log(TAG + "[C1] setPreviewDisplay 替换失败: " + e);
                    }
                    
                    XposedBridge.log(TAG + "[C1] setPreviewDisplay 已拦截");
                }
            }
        );
        
        // Hook startPreview - 在这里启动屏幕录制
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", lpparam.classLoader, "startPreview",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isDisabled()) return;
                    
                    Camera camera = (Camera) param.thisObject;
                    
                    // 获取预览尺寸
                    try {
                        Camera.Parameters params = camera.getParameters();
                        Camera.Size size = params.getPreviewSize();
                        screenWidth = size.width;
                        screenHeight = size.height;
                        XposedBridge.log(TAG + "[C1] 预览尺寸: " + screenWidth + "x" + screenHeight);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + "[C1] 获取尺寸失败: " + e);
                    }
                    
                    // 创建输出 Surface
                    createOutputSurface();
                    
                    // 开始屏幕录制
                    startScreenCapture();
                }
            }
        );
    }

    /**
     * 创建输出 Surface
     */
    private void createOutputSurface() {
        // 优先使用 SurfaceHolder
        if (originalHolder != null && originalHolder.getSurface() != null && originalHolder.getSurface().isValid()) {
            outputSurface = originalHolder.getSurface();
            XposedBridge.log(TAG + "使用 SurfaceHolder 的 Surface");
        }
        // 其次使用 SurfaceTexture
        else if (originalSurfaceTexture != null) {
            // 只有从 SurfaceTexture 创建的 Surface 才需要管理
            if (outputSurface != null) {
                outputSurface.release();
            }
            outputSurface = new Surface(originalSurfaceTexture);
            XposedBridge.log(TAG + "使用 SurfaceTexture 创建的 Surface");
        }
        
        if (outputSurface != null) {
            XposedBridge.log(TAG + "输出 Surface 已创建: " + outputSurface + " valid=" + outputSurface.isValid());
        } else {
            XposedBridge.log(TAG + "无法创建输出 Surface");
        }
    }
    
    /**
     * 开始屏幕录制
     */
    private void startScreenCapture() {
        if (outputSurface == null || !outputSurface.isValid()) {
            XposedBridge.log(TAG + "输出 Surface 无效");
            return;
        }
        
        // 如果已经有权限，直接开始
        if (permissionGranted && savedResultData != null) {
            createProjectionAndStart();
            return;
        }
        
        // 请求权限
        if (currentActivity != null && projectionManager != null) {
            XposedBridge.log(TAG + "请求屏幕录制权限...");
            try {
                Intent intent = projectionManager.createScreenCaptureIntent();
                currentActivity.startActivityForResult(intent, REQUEST_CODE);
            } catch (Exception e) {
                XposedBridge.log(TAG + "请求权限失败: " + e);
            }
        } else {
            XposedBridge.log(TAG + "无法请求权限: activity=" + currentActivity + " pm=" + projectionManager);
        }
    }
    
    /**
     * 创建 MediaProjection 并开始录制
     */
    private void createProjectionAndStart() {
        if (savedResultData == null || projectionManager == null) {
            XposedBridge.log(TAG + "权限数据为空");
            return;
        }
        
        if (outputSurface == null || !outputSurface.isValid()) {
            XposedBridge.log(TAG + "输出 Surface 无效，重新创建");
            createOutputSurface();
            if (outputSurface == null || !outputSurface.isValid()) {
                XposedBridge.log(TAG + "仍然无法创建有效的 Surface");
                return;
            }
        }
        
        try {
            // 释放旧的
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            
            // 创建新的 MediaProjection
            mediaProjection = projectionManager.getMediaProjection(savedResultCode, savedResultData);
            
            if (mediaProjection == null) {
                XposedBridge.log(TAG + "getMediaProjection 返回 null");
                return;
            }
            
            XposedBridge.log(TAG + "MediaProjection 创建成功");
            
            // 创建 VirtualDisplay
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreen",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                outputSurface,
                null,
                mainHandler
            );
            
            if (virtualDisplay != null) {
                XposedBridge.log(TAG + "VirtualDisplay 创建成功: " + screenWidth + "x" + screenHeight);
                showToast("屏幕录制已启动");
            } else {
                XposedBridge.log(TAG + "VirtualDisplay 创建失败");
            }
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "创建失败: " + e);
            e.printStackTrace();
        }
    }
    
    private void showToast(String msg) {
        if (appContext != null && mainHandler != null) {
            File f = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/no_toast.jpg");
            if (f.exists()) return;
            
            mainHandler.post(() -> {
                try {
                    Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {}
            });
        }
    }
}
