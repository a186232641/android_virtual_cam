package com.example.vcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
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
    
    // 当前输出的 Surface
    private Surface currentOutputSurface;
    private int currentWidth;
    private int currentHeight;
    
    // 音频捕获
    private ScreenCaptureHelper captureHelper;
    
    private Handler mainHandler;
    
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
        
        // 设置权限回调
        ScreenCaptureActivity.permissionResultListener = (resultCode, data) -> {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                permissionGranted = true;
                XposedBridge.log(TAG + "屏幕录制权限已获取");
                
                // 如果有等待的 Surface，立即开始流
                if (currentOutputSurface != null) {
                    startStreamInternal();
                }
                
                showToast("屏幕录制已启动");
            } else {
                permissionGranted = false;
                XposedBridge.log(TAG + "用户拒绝了屏幕录制权限");
                showToast("屏幕录制权限被拒绝");
            }
        };
        
        // 启动透明 Activity 请求权限
        try {
            Intent intent = new Intent(activityContext, ScreenCaptureActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityContext.startActivity(intent);
        } catch (Exception e) {
            XposedBridge.log(TAG + "启动权限请求 Activity 失败: " + e.getMessage());
            
            // 尝试直接使用 context
            try {
                Intent intent = new Intent(context, ScreenCaptureActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                XposedBridge.log(TAG + "备用方式也失败: " + e2.getMessage());
            }
        }
    }
    
    /**
     * 开始屏幕流到指定 Surface
     */
    public void startStream(Surface surface, int width, int height) {
        this.currentOutputSurface = surface;
        this.currentWidth = width > 0 ? width : screenWidth;
        this.currentHeight = height > 0 ? height : screenHeight;
        
        if (!permissionGranted || mediaProjection == null) {
            XposedBridge.log(TAG + "等待权限授予...");
            // 权限获取后会自动开始
            if (context != null) {
                requestPermission(context);
            }
            return;
        }
        
        startStreamInternal();
    }
    
    private void startStreamInternal() {
        if (isStreaming) {
            // 更新 Surface
            updateStream(currentOutputSurface, currentWidth, currentHeight);
            return;
        }
        
        if (currentOutputSurface == null) {
            XposedBridge.log(TAG + "输出 Surface 为空");
            return;
        }
        
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreen",
                currentWidth,
                currentHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                currentOutputSurface,
                null,
                null
            );
            
            isStreaming = true;
            XposedBridge.log(TAG + "屏幕流已启动: " + currentWidth + "x" + currentHeight);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "启动屏幕流失败: " + e.getMessage());
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
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        permissionGranted = false;
        currentOutputSurface = null;
        
        XposedBridge.log(TAG + "资源已释放");
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
