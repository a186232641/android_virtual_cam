package com.example.vcam;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;

import de.robv.android.xposed.XposedBridge;

/**
 * 画中画模式辅助类
 * 让目标应用进入 PiP 模式，保持前台运行同时可以操作其他应用
 */
public class PipModeHelper {
    private static final String TAG = "【VCAM】[PiP]";
    
    /**
     * 检查设备是否支持画中画
     */
    public static boolean isPipSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }
    
    /**
     * 让 Activity 进入画中画模式
     */
    public static boolean enterPipMode(Activity activity) {
        if (activity == null) {
            XposedBridge.log(TAG + "Activity 为空");
            return false;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            XposedBridge.log(TAG + "系统版本不支持 PiP (需要 Android 8.0+)");
            return false;
        }
        
        try {
            // 构建 PiP 参数
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
            
            // 设置宽高比 (16:9 适合视频通话)
            builder.setAspectRatio(new Rational(16, 9));
            
            // Android 12+ 可以设置自动进入 PiP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true);
                builder.setSeamlessResizeEnabled(true);
            }
            
            // 进入画中画模式
            boolean success = activity.enterPictureInPictureMode(builder.build());
            
            XposedBridge.log(TAG + "进入 PiP 模式: " + (success ? "成功" : "失败"));
            return success;
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "进入 PiP 模式失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 设置 Activity 支持画中画
     */
    public static void enablePipSupport(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
            builder.setAspectRatio(new Rational(16, 9));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: 按 Home 键自动进入 PiP
                builder.setAutoEnterEnabled(true);
            }
            
            activity.setPictureInPictureParams(builder.build());
            XposedBridge.log(TAG + "已启用 PiP 支持");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "启用 PiP 支持失败: " + e.getMessage());
        }
    }
}
