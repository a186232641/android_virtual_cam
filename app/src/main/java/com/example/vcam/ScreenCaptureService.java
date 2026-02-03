package com.example.vcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import de.robv.android.xposed.XposedBridge;

/**
 * 屏幕录制前台服务
 * Android 10+ 要求 MediaProjection 必须在前台服务中运行
 */
public class ScreenCaptureService extends Service {
    private static final String TAG = "【VCAM】[CaptureService]";
    private static final String CHANNEL_ID = "vcam_screen_capture";
    private static final int NOTIFICATION_ID = 1001;
    
    // 静态实例，供外部访问
    private static ScreenCaptureService instance;
    
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    
    private int resultCode;
    private Intent resultData;
    
    private Surface outputSurface;
    private int captureWidth;
    private int captureHeight;
    private int screenDensity;
    
    private boolean isCapturing = false;
    private Handler mainHandler;
    
    // 回调接口
    public interface CaptureCallback {
        void onCaptureStarted();
        void onCaptureStopped();
        void onCaptureError(String error);
    }
    
    private static CaptureCallback captureCallback;
    
    public static void setCaptureCallback(CaptureCallback callback) {
        captureCallback = callback;
    }
    
    public static ScreenCaptureService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        
        // 获取屏幕密度
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenDensity = metrics.densityDpi;
        
        XposedBridge.log(TAG + "服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        
        String action = intent.getAction();
        
        if ("START_CAPTURE".equals(action)) {
            resultCode = intent.getIntExtra("resultCode", 0);
            resultData = intent.getParcelableExtra("resultData");
            captureWidth = intent.getIntExtra("width", 1280);
            captureHeight = intent.getIntExtra("height", 720);
            
            // 启动前台服务
            startForegroundService();
            
            // 开始屏幕录制
            startCapture();
            
        } else if ("STOP_CAPTURE".equals(action)) {
            stopCapture();
            stopSelf();
        } else if ("UPDATE_SURFACE".equals(action)) {
            // 更新输出 Surface (通过静态方法设置)
            if (outputSurface != null && isCapturing) {
                updateCapture();
            }
        }
        
        return START_STICKY;
    }
    
    private void startForegroundService() {
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        Notification notification = builder
            .setContentTitle("虚拟摄像头")
            .setContentText("屏幕录制模式运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        XposedBridge.log(TAG + "前台服务已启动");
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "屏幕录制服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("虚拟摄像头屏幕录制模式");
            channel.enableLights(false);
            channel.enableVibration(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private void startCapture() {
        if (resultCode == 0 || resultData == null) {
            XposedBridge.log(TAG + "无效的权限数据");
            notifyError("无效的权限数据");
            return;
        }
        
        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            
            if (mediaProjection == null) {
                XposedBridge.log(TAG + "无法创建 MediaProjection");
                notifyError("无法创建 MediaProjection");
                return;
            }
            
            // 注册回调
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    XposedBridge.log(TAG + "MediaProjection 已停止");
                    isCapturing = false;
                    if (captureCallback != null) {
                        mainHandler.post(() -> captureCallback.onCaptureStopped());
                    }
                }
            }, mainHandler);
            
            // 如果有输出 Surface，立即开始
            if (outputSurface != null) {
                createVirtualDisplay();
            }
            
            isCapturing = true;
            XposedBridge.log(TAG + "屏幕录制已启动");
            
            if (captureCallback != null) {
                mainHandler.post(() -> captureCallback.onCaptureStarted());
            }
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "启动屏幕录制失败: " + e.getMessage());
            notifyError(e.getMessage());
        }
    }

    private void createVirtualDisplay() {
        if (mediaProjection == null || outputSurface == null) {
            return;
        }
        
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreenCapture",
                captureWidth,
                captureHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                outputSurface,
                null,
                mainHandler
            );
            
            XposedBridge.log(TAG + "VirtualDisplay 已创建: " + captureWidth + "x" + captureHeight);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "创建 VirtualDisplay 失败: " + e.getMessage());
        }
    }
    
    private void updateCapture() {
        if (!isCapturing || mediaProjection == null) {
            return;
        }
        createVirtualDisplay();
    }
    
    private void stopCapture() {
        isCapturing = false;
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        XposedBridge.log(TAG + "屏幕录制已停止");
        
        if (captureCallback != null) {
            mainHandler.post(() -> captureCallback.onCaptureStopped());
        }
    }
    
    /**
     * 设置输出 Surface
     */
    public void setOutputSurface(Surface surface, int width, int height) {
        this.outputSurface = surface;
        this.captureWidth = width;
        this.captureHeight = height;
        
        if (isCapturing && mediaProjection != null) {
            createVirtualDisplay();
        }
    }
    
    /**
     * 检查是否正在录制
     */
    public boolean isCapturing() {
        return isCapturing;
    }
    
    /**
     * 获取 MediaProjection
     */
    public MediaProjection getMediaProjection() {
        return mediaProjection;
    }
    
    private void notifyError(String error) {
        if (captureCallback != null) {
            mainHandler.post(() -> captureCallback.onCaptureError(error));
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        stopCapture();
        instance = null;
        XposedBridge.log(TAG + "服务已销毁");
        super.onDestroy();
    }
    
    /**
     * 启动屏幕录制服务的静态方法
     */
    public static void startCapture(Context context, int resultCode, Intent resultData, 
                                     int width, int height) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.setAction("START_CAPTURE");
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("resultData", resultData);
        intent.putExtra("width", width);
        intent.putExtra("height", height);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
    
    /**
     * 停止屏幕录制服务的静态方法
     */
    public static void stopCapture(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.setAction("STOP_CAPTURE");
        context.startService(intent);
    }
}
