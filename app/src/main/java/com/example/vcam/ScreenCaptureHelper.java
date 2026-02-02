package com.example.vcam;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import de.robv.android.xposed.XposedBridge;

/**
 * 屏幕录制辅助类
 * 用于捕获实时屏幕画面并输出到指定 Surface
 */
public class ScreenCaptureHelper {
    private static final String TAG = "【VCAM】[ScreenCapture]";
    
    private Context context;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private Surface outputSurface;
    private boolean isCapturing = false;
    
    // 音频相关
    private AudioRecord audioRecord;
    private AudioRecord micRecord;
    private boolean isRecordingAudio = false;
    private AudioMixerHelper audioMixer;
    
    // 回调
    private OnCaptureStateListener listener;
    
    public interface OnCaptureStateListener {
        void onCaptureStarted();
        void onCaptureStopped();
        void onCaptureError(String error);
    }
    
    public ScreenCaptureHelper(Context context) {
        this.context = context;
        this.projectionManager = (MediaProjectionManager) 
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        // 获取屏幕参数
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        
        XposedBridge.log(TAG + "屏幕参数: " + screenWidth + "x" + screenHeight + " density=" + screenDensity);
    }
    
    public void setListener(OnCaptureStateListener listener) {
        this.listener = listener;
    }
    
    /**
     * 获取请求屏幕录制权限的 Intent
     */
    public Intent getScreenCaptureIntent() {
        return projectionManager.createScreenCaptureIntent();
    }
    
    /**
     * 处理权限请求结果
     */
    public boolean handleActivityResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            XposedBridge.log(TAG + "用户拒绝了屏幕录制权限");
            return false;
        }
        
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            XposedBridge.log(TAG + "无法获取 MediaProjection");
            return false;
        }
        
        XposedBridge.log(TAG + "成功获取 MediaProjection");
        return true;
    }
    
    /**
     * 设置输出 Surface 并开始捕获
     */
    public void startCapture(Surface surface, int width, int height) {
        if (mediaProjection == null) {
            XposedBridge.log(TAG + "MediaProjection 未初始化");
            if (listener != null) {
                listener.onCaptureError("请先授权屏幕录制权限");
            }
            return;
        }
        
        if (isCapturing) {
            XposedBridge.log(TAG + "已经在录制中");
            return;
        }
        
        this.outputSurface = surface;
        
        // 使用请求的分辨率，如果为0则使用屏幕分辨率
        int captureWidth = width > 0 ? width : screenWidth;
        int captureHeight = height > 0 ? height : screenHeight;
        
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreenCapture",
                captureWidth,
                captureHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            );
            
            isCapturing = true;
            XposedBridge.log(TAG + "开始屏幕捕获: " + captureWidth + "x" + captureHeight);
            
            if (listener != null) {
                listener.onCaptureStarted();
            }
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "创建 VirtualDisplay 失败: " + e.getMessage());
            if (listener != null) {
                listener.onCaptureError(e.getMessage());
            }
        }
    }
    
    /**
     * 开始音频捕获 (系统音频 + 麦克风)
     */
    @SuppressLint("MissingPermission")
    public void startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            XposedBridge.log(TAG + "系统音频捕获需要 Android 10+");
            // 仅启动麦克风录制
            startMicrophoneCapture();
            return;
        }
        
        if (mediaProjection == null) {
            XposedBridge.log(TAG + "MediaProjection 未初始化，无法捕获系统音频");
            startMicrophoneCapture();
            return;
        }
        
        try {
            // 系统音频配置 (Android 10+)
            AudioPlaybackCaptureConfiguration config = 
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2;
            
            audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build();
            
            XposedBridge.log(TAG + "系统音频捕获已配置");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "配置系统音频捕获失败: " + e.getMessage());
        }
        
        // 同时启动麦克风
        startMicrophoneCapture();
        
        // 启动音频混合
        startAudioMixing();
    }

    
    @SuppressLint("MissingPermission")
    private void startMicrophoneCapture() {
        try {
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2;
            
            micRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );
            
            XposedBridge.log(TAG + "麦克风捕获已配置");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "配置麦克风捕获失败: " + e.getMessage());
        }
    }
    
    private void startAudioMixing() {
        if (audioRecord == null && micRecord == null) {
            XposedBridge.log(TAG + "没有可用的音频源");
            return;
        }
        
        isRecordingAudio = true;
        audioMixer = new AudioMixerHelper();
        
        // 启动音频录制线程
        new Thread(() -> {
            if (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
            }
            if (micRecord != null && micRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                micRecord.startRecording();
            }
            
            byte[] systemBuffer = new byte[4096];
            byte[] micBuffer = new byte[2048]; // mono
            
            while (isRecordingAudio) {
                try {
                    // 读取系统音频
                    if (audioRecord != null) {
                        int systemRead = audioRecord.read(systemBuffer, 0, systemBuffer.length);
                        if (systemRead > 0) {
                            audioMixer.addSystemAudio(systemBuffer, systemRead);
                        }
                    }
                    
                    // 读取麦克风
                    if (micRecord != null) {
                        int micRead = micRecord.read(micBuffer, 0, micBuffer.length);
                        if (micRead > 0) {
                            audioMixer.addMicAudio(micBuffer, micRead);
                        }
                    }
                    
                } catch (Exception e) {
                    XposedBridge.log(TAG + "音频读取错误: " + e.getMessage());
                }
            }
        }, "VCamAudioCapture").start();
        
        XposedBridge.log(TAG + "音频混合已启动");
    }
    
    /**
     * 获取混合后的音频数据
     */
    public byte[] getMixedAudioData() {
        if (audioMixer != null) {
            return audioMixer.getMixedData();
        }
        return null;
    }
    
    /**
     * 停止捕获
     */
    public void stopCapture() {
        isCapturing = false;
        isRecordingAudio = false;
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                XposedBridge.log(TAG + "停止系统音频录制失败: " + e.getMessage());
            }
            audioRecord = null;
        }
        
        if (micRecord != null) {
            try {
                micRecord.stop();
                micRecord.release();
            } catch (Exception e) {
                XposedBridge.log(TAG + "停止麦克风录制失败: " + e.getMessage());
            }
            micRecord = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        XposedBridge.log(TAG + "屏幕捕获已停止");
        
        if (listener != null) {
            listener.onCaptureStopped();
        }
    }
    
    /**
     * 更新输出 Surface (当相机分辨率改变时)
     */
    public void updateOutputSurface(Surface newSurface, int width, int height) {
        if (!isCapturing) {
            return;
        }
        
        // 重新创建 VirtualDisplay
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        
        this.outputSurface = newSurface;
        
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VCamScreenCapture",
                width,
                height,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newSurface,
                null,
                null
            );
            
            XposedBridge.log(TAG + "更新输出 Surface: " + width + "x" + height);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + "更新 VirtualDisplay 失败: " + e.getMessage());
        }
    }
    
    public boolean isCapturing() {
        return isCapturing;
    }
    
    public int getScreenWidth() {
        return screenWidth;
    }
    
    public int getScreenHeight() {
        return screenHeight;
    }
}
