package com.example.vcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import de.robv.android.xposed.XposedBridge;

/**
 * 前台服务
 * Android 10+ 屏幕录制需要在前台服务中运行
 */
public class ScreenCaptureService extends Service {
    private static final String TAG = "【VCAM】[CaptureService]";
    private static final String CHANNEL_ID = "vcam_screen_capture";
    private static final int NOTIFICATION_ID = 1001;
    
    public static final String ACTION_START = "com.example.vcam.START_CAPTURE";
    public static final String ACTION_STOP = "com.example.vcam.STOP_CAPTURE";
    
    private static ScreenCaptureService instance;
    
    public static ScreenCaptureService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        XposedBridge.log(TAG + "服务创建");
        
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if (ACTION_START.equals(action)) {
                startForegroundService();
                XposedBridge.log(TAG + "前台服务已启动");
            } else if (ACTION_STOP.equals(action)) {
                stopSelf();
            }
        }
        
        return START_STICKY;
    }
    
    private void startForegroundService() {
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "屏幕录制",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("虚拟摄像头屏幕录制服务");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Notification.Builder builder;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        // 停止按钮
        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        builder.setContentTitle("虚拟摄像头")
               .setContentText("屏幕录制中...")
               .setSmallIcon(android.R.drawable.ic_menu_camera)
               .setOngoing(true)
               .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent);
        
        return builder.build();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        
        // 停止屏幕流
        ScreenStreamManager.getInstance().stopStream();
        
        XposedBridge.log(TAG + "服务销毁");
    }
    
    /**
     * 启动前台服务
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.setAction(ACTION_START);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
    
    /**
     * 停止前台服务
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
}
