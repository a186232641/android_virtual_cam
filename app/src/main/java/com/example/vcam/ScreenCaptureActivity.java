package com.example.vcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;

/**
 * 透明 Activity，用于请求屏幕录制权限
 * 权限获取后立即关闭
 */
public class ScreenCaptureActivity extends Activity {
    private static final String TAG = "【VCAM】[CaptureActivity]";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    
    // 静态回调，用于将结果传递给 HookMain
    public static OnPermissionResultListener permissionResultListener;
    
    public interface OnPermissionResultListener {
        void onPermissionResult(int resultCode, Intent data);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        XposedBridge.log(TAG + "请求屏幕录制权限");
        
        // 请求屏幕录制权限
        try {
            android.media.projection.MediaProjectionManager projectionManager = 
                (android.media.projection.MediaProjectionManager) 
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "请求权限失败: " + e.getMessage());
            finish();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            XposedBridge.log(TAG + "权限结果: " + (resultCode == RESULT_OK ? "允许" : "拒绝"));
            
            if (permissionResultListener != null) {
                // 克隆 Intent 数据，确保可以保存
                Intent clonedData = null;
                if (data != null) {
                    clonedData = new Intent(data);
                }
                permissionResultListener.onPermissionResult(resultCode, clonedData);
            }
        }
        
        // 关闭透明 Activity
        finish();
    }
}
