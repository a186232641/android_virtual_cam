package com.example.vcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;

import de.robv.android.xposed.XposedBridge;

/**
 * 屏幕流管理器
 * 管理屏幕录制和输出到摄像头预览
 */
public class ScreenStreamManager {
    private static final String TAG = "【VCAM】[ScreenStream]";
    
    private static ScreenStreamManager instance;
    
    private Context context;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private boolean isStreaming = false;
    private boolean permissionGranted = false;
    
    // 保存权限数据，用于重新创建 MediaProjection
    private int savedResultCode = 0;
    private Intent savedResultData = null;
    
    // 当前输出的 Surface
    private Surface currentOutputSurface;
    private int currentWidth;
    private int currentHeight;
    
    // 音频捕获
    private ScreenCaptureHelper captureHelper;
    
    private Handler mainHandler;
    
    // MediaProjection 回调，监听权限失效
    private MediaProjection.Callback projectionCallback;
    
    private ScreenStreamManager() {
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized ScreenStreamManager getInstance() {
        if (instance == null) {
            instance = new ScreenStreamManager();
        }
        return instance;
    }
    
    /**
     * 初始化 (在获取到 Context 后调用)
     */
    public void init(Context ctx) {
        if (this.context != null) {
            return;
        }
        
        this.context = ctx.getApplicationContext();
        this.projectionManager = (MediaProjectionManager) 
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        // 获取屏幕参数
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        
        XposedBridge.log(TAG + "初始化完成，屏幕: " + screenWidth + "x" + screenHeight);
    }
    
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
     * 请求屏幕录制权限
     */
    public void requestPermission(Context activityContext) {
        if (permissionGranted && mediaProjection != null) {
            XposedBridge.log(TAG + "已有权限，无需重复请求");
            return;
        }
        
        XposedBridge.log(TAG + "请求屏幕录制权限...");
        
        // 直接在目标应用中请求权限
        // 使用 MediaProjectionManager 创建请求 Intent
        if (projectionManager == null) {
            XposedBridge.log(TAG + "projectionManager 为空");
            return;
        }
        
        try {
            // 创建权限请求 Intent
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            
            // 需要通过 Activity 来请求
            if (activityContext instanceof Activity) {
                Activity activity = (Activity) activityContext;
                
                // 保存回调，在 onActivityResult 中处理
                pendingActivity = activity;
                
                activity.startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);
                XposedBridge.log(TAG + "已发起权限请求");
            } else {
                XposedBridge.log(TAG + "Context 不是 Activity，无法请求权限");
                showToast("请在应用内打开相机以请求屏幕录制权限");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + "请求权限失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理权限请求结果 (需要在 Hook 的 Activity.onActivityResult 中调用)
     */
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_SCREEN_CAPTURE) {
            return;
        }
        
        XposedBridge.log(TAG + "收到权限结果: resultCode=" + resultCode);
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            savedResultCode = resultCode;
            savedResultData = new Intent(data);
            
            createMediaProjection();
            
            if (currentOutputSurface != null) {
                startStreamInternal();
            }
            
            showToast("屏幕录制已启动");
        } else {
            permissionGranted = false;
            XposedBridge.log(TAG + "用户拒绝了屏幕录制权限");
            showToast("屏幕录制权限被拒绝");
        }
        
        pendingActivity = null;
    }
    
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 19283;
    private Activity pendingActivity;
    
    /**
     * 开始屏幕流到指定 Surface
     */
    public void startStream(Surface surface, int width, int height, Activity activity) {
        this.currentOutputSurface = surface;
        this.currentWidth = width > 0 ? width : screenWidth;
        this.currentHeight = height > 0 ? height : screenHeight;
        
        XposedBridge.log(TAG + "startStream 调用，Surface: " + surface + 
            " 尺寸: " + currentWidth + "x" + currentHeight);
        
        // 检查 MediaProjection 是否有效，如果无效尝试重新创建
        if (mediaProjection == null && savedResultData != null) {
            XposedBridge.log(TAG + "MediaProjection 已失效，尝试重新创建...");
            createMediaProjection();
        }
        
        if (!permissionGranted || mediaProjection == null) {
            XposedBridge.log(TAG + "需要请求权限...");
            // 权限获取后会自动开始
            if (activity != null) {
                requestPermission(activity);
            } else if (context != null) {
                XposedBridge.log(TAG + "没有 Activity，无法请求权限");
            }
            return;
        }
        
        startStreamInternal();
    }
    
    /**
     * 开始屏幕流（无 Activity 参数的重载）
     */
    public void startStream(Surface surface, int width, int height) {
        startStream(surface, width, height, null);
    }
    
    /**
     * 创建 MediaProjection 并注册回调
     */
    private void createMediaProjection() {
        if (savedResultData == null || savedResultCode == 0) {
            XposedBridge.log(TAG + "没有保存的权限数据");
            return;
        }
        
        try {
            // 释放旧的 MediaProjection
            if (mediaProjection != null) {
                try {
                    mediaProjection.unregisterCallback(projectionCallback);
                    mediaProjection.stop();
                } catch (Exception e) {
                    // 忽略
                }
                mediaProjection = null;
            }
            
            // 创建新的 MediaProjection
            mediaProjection = projectionManager.getMediaProjection(savedResultCode, savedResultData);
            
            if (mediaProjection != null) {
                // 注册回调监听权限失效
                projectionCallback = new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        XposedBridge.log(TAG + "MediaProjection 已停止");
                        isStreaming = false;
                        // 不清除 savedResultData，以便可以重新创建
                    }
                };
                mediaProjection.registerCallback(projectionCallback, mainHandler);
                
                permissionGranted = true;
                XposedBridge.log(TAG + "MediaProjection 创建成功");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + "创建 MediaProjection 失败: " + e.getMessage());
            permissionGranted = false;
            mediaProjection = null;
        }
    }
    
    /**
     * 使用前台服务启动屏幕录制 (Android 10+)
     */
    private void startForegroundServiceCapture() {
        if (context == null || savedResultData == null) {
            XposedBridge.log(TAG + "无法启动前台服务：context 或权限数据为空");
            return;
        }
        
        XposedBridge.log(TAG + "启动前台服务，Surface: " + currentOutputSurface + 
            " 尺寸: " + currentWidth + "x" + currentHeight);
        
        // 设置服务回调
        ScreenCaptureService.setCaptureCallback(new ScreenCaptureService.CaptureCallback() {
            @Override
            public void onCaptureStarted() {
                permissionGranted = true;
                XposedBridge.log(TAG + "前台服务屏幕录制已启动，设置输出 Surface");
                
                // 服务启动后，设置输出 Surface
                ScreenCaptureService service = ScreenCaptureService.getInstance();
                if (service != null && currentOutputSurface != null) {
                    service.setOutputSurface(currentOutputSurface, currentWidth, currentHeight);
                    isStreaming = true;
                }
            }
            
            @Override
            public void onCaptureStopped() {
                isStreaming = false;
                XposedBridge.log(TAG + "前台服务屏幕录制已停止");
            }
            
            @Override
            public void onCaptureError(String error) {
                permissionGranted = false;
                XposedBridge.log(TAG + "前台服务错误: " + error);
            }
        });
        
        // 启动前台服务
        ScreenCaptureService.startCapture(
            context, 
            savedResultCode, 
            savedResultData,
            currentWidth > 0 ? currentWidth : screenWidth,
            currentHeight > 0 ? currentHeight : screenHeight
        );
        
        permissionGranted = true;
    }
    
    private void startStreamInternal() {
        if (isStreaming && virtualDisplay != null) {
            XposedBridge.log(TAG + "已在流式传输，更新 Surface");
            updateStream(currentOutputSurface, currentWidth, currentHeight);
            return;
        }
        
        if (currentOutputSurface == null || !currentOutputSurface.isValid()) {
            XposedBridge.log(TAG + "输出 Surface 为空或无效");
            return;
        }
        
        // 检查 MediaProjection 是否有效
        if (mediaProjection == null) {
            XposedBridge.log(TAG + "MediaProjection 无效，尝试重新创建");
            createMediaProjection();
            if (mediaProjection == null) {
                XposedBridge.log(TAG + "无法创建 MediaProjection");
                return;
            }
        }
        
        try {
            XposedBridge.log(TAG + "创建 VirtualDisplay: " + currentWidth + "x" + currentHeight + 
                " density=" + screenDensity);
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreen",
                currentWidth,
                currentHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                currentOutputSurface,
                new VirtualDisplay.Callback() {
                    @Override
                    public void onPaused() {
                        XposedBridge.log(TAG + "VirtualDisplay 暂停");
                    }
                    
                    @Override
                    public void onResumed() {
                        XposedBridge.log(TAG + "VirtualDisplay 恢复");
                    }
                    
                    @Override
                    public void onStopped() {
                        XposedBridge.log(TAG + "VirtualDisplay 停止");
                        isStreaming = false;
                    }
                },
                mainHandler
            );
            
            if (virtualDisplay != null) {
                isStreaming = true;
                XposedBridge.log(TAG + "屏幕流已启动成功！");
                showToast("屏幕录制已启动");
            } else {
                XposedBridge.log(TAG + "VirtualDisplay 创建返回 null");
            }
            
        } catch (SecurityException e) {
            XposedBridge.log(TAG + "权限失效: " + e.getMessage());
            permissionGranted = false;
            mediaProjection = null;
        } catch (Exception e) {
            XposedBridge.log(TAG + "启动屏幕流失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    
    /**
     * 更新输出 Surface
     */
    public void updateStream(Surface newSurface, int width, int height) {
        if (!isStreaming || virtualDisplay == null) {
            startStream(newSurface, width, height);
            return;
        }
        
        this.currentOutputSurface = newSurface;
        this.currentWidth = width;
        this.currentHeight = height;
        
        // 检查 MediaProjection 是否有效
        if (mediaProjection == null) {
            createMediaProjection();
            if (mediaProjection == null) {
                XposedBridge.log(TAG + "无法更新流，MediaProjection 无效");
                return;
            }
        }
        
        try {
            // 重新创建 VirtualDisplay
            virtualDisplay.release();
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreen",
                width,
                height,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newSurface,
                null,
                null
            );
            
            XposedBridge.log(TAG + "屏幕流已更新: " + width + "x" + height);
            
        } catch (SecurityException e) {
            XposedBridge.log(TAG + "更新流时权限失效，尝试重新创建");
            isStreaming = false;
            createMediaProjection();
            startStreamInternal();
        } catch (Exception e) {
            XposedBridge.log(TAG + "更新屏幕流失败: " + e.getMessage());
        }
    }
    
    /**
     * 停止屏幕流
     */
    public void stopStream() {
        isStreaming = false;
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        XposedBridge.log(TAG + "屏幕流已停止");
    }
    
    /**
     * 释放所有资源
     */
    public void release() {
        stopStream();
        
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
                mediaProjection.stop();
            } catch (Exception e) {
                // 忽略
            }
            mediaProjection = null;
        }
        
        permissionGranted = false;
        currentOutputSurface = null;
        // 注意：不清除 savedResultData，以便可以重新请求
        
        XposedBridge.log(TAG + "资源已释放");
    }
    
    /**
     * 完全释放，包括保存的权限数据
     */
    public void releaseAll() {
        release();
        savedResultCode = 0;
        savedResultData = null;
        XposedBridge.log(TAG + "所有资源已释放，包括权限数据");
    }
    
    /**
     * 检查是否有保存的权限数据
     */
    public boolean hasSavedPermission() {
        return savedResultData != null && savedResultCode != 0;
    }
    
    /**
     * 创建用于接收屏幕内容的 Surface
     */
    public Surface createOutputSurface(int width, int height) {
        SurfaceTexture surfaceTexture = new SurfaceTexture(100);
        surfaceTexture.setDefaultBufferSize(width, height);
        return new Surface(surfaceTexture);
    }
    
    public boolean isStreaming() {
        return isStreaming;
    }
    
    public boolean hasPermission() {
        return permissionGranted && mediaProjection != null;
    }
    
    public int getScreenWidth() {
        return screenWidth;
    }
    
    public int getScreenHeight() {
        return screenHeight;
    }
    
    private void showToast(String message) {
        if (context != null) {
            mainHandler.post(() -> {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    XposedBridge.log(TAG + "Toast 显示失败: " + e.getMessage());
                }
            });
        }
    }
}
