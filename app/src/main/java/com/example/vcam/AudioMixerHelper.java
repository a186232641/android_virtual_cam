package com.example.vcam;

import java.util.concurrent.LinkedBlockingQueue;

import de.robv.android.xposed.XposedBridge;

/**
 * 音频混合辅助类
 * 将系统音频和麦克风音频混合输出
 */
public class AudioMixerHelper {
    private static final String TAG = "【VCAM】[AudioMixer]";
    
    private LinkedBlockingQueue<byte[]> systemAudioQueue;
    private LinkedBlockingQueue<byte[]> micAudioQueue;
    private LinkedBlockingQueue<byte[]> mixedAudioQueue;
    
    private float systemVolume = 1.0f;
    private float micVolume = 1.0f;
    
    private volatile boolean isMixing = false;
    private Thread mixerThread;
    
    public AudioMixerHelper() {
        systemAudioQueue = new LinkedBlockingQueue<>(50);
        micAudioQueue = new LinkedBlockingQueue<>(50);
        mixedAudioQueue = new LinkedBlockingQueue<>(50);
        
        startMixing();
    }
    
    private void startMixing() {
        isMixing = true;
        mixerThread = new Thread(() -> {
            while (isMixing) {
                try {
                    byte[] systemData = systemAudioQueue.poll();
                    byte[] micData = micAudioQueue.poll();
                    
                    if (systemData != null || micData != null) {
                        byte[] mixed = mixAudio(systemData, micData);
                        if (mixed != null) {
                            // 移除旧数据防止队列满
                            while (mixedAudioQueue.size() > 40) {
                                mixedAudioQueue.poll();
                            }
                            mixedAudioQueue.offer(mixed);
                        }
                    } else {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    XposedBridge.log(TAG + "混合错误: " + e.getMessage());
                }
            }
        }, "VCamAudioMixer");
        mixerThread.start();
    }
    
    /**
     * 添加系统音频数据
     */
    public void addSystemAudio(byte[] data, int length) {
        if (data == null || length <= 0) return;
        
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        
        // 移除旧数据防止队列满
        while (systemAudioQueue.size() > 40) {
            systemAudioQueue.poll();
        }
        systemAudioQueue.offer(copy);
    }
    
    /**
     * 添加麦克风音频数据 (mono -> stereo 转换)
     */
    public void addMicAudio(byte[] data, int length) {
        if (data == null || length <= 0) return;
        
        // Mono to Stereo 转换
        byte[] stereoData = new byte[length * 2];
        for (int i = 0; i < length; i += 2) {
            // 复制左声道
            stereoData[i * 2] = data[i];
            stereoData[i * 2 + 1] = data[i + 1];
            // 复制右声道
            stereoData[i * 2 + 2] = data[i];
            stereoData[i * 2 + 3] = data[i + 1];
        }
        
        // 移除旧数据防止队列满
        while (micAudioQueue.size() > 40) {
            micAudioQueue.poll();
        }
        micAudioQueue.offer(stereoData);
    }
    
    /**
     * 混合两个音频流
     */
    private byte[] mixAudio(byte[] systemData, byte[] micData) {
        if (systemData == null && micData == null) {
            return null;
        }
        
        // 只有一个源
        if (systemData == null) {
            return applyVolume(micData, micVolume);
        }
        if (micData == null) {
            return applyVolume(systemData, systemVolume);
        }
        
        // 混合两个源
        int length = Math.max(systemData.length, micData.length);
        byte[] mixed = new byte[length];
        
        for (int i = 0; i < length; i += 2) {
            short systemSample = 0;
            short micSample = 0;
            
            if (i + 1 < systemData.length) {
                systemSample = (short) ((systemData[i] & 0xFF) | (systemData[i + 1] << 8));
                systemSample = (short) (systemSample * systemVolume);
            }
            
            if (i + 1 < micData.length) {
                micSample = (short) ((micData[i] & 0xFF) | (micData[i + 1] << 8));
                micSample = (short) (micSample * micVolume);
            }
            
            // 混合并防止溢出
            int mixedSample = systemSample + micSample;
            mixedSample = Math.max(-32768, Math.min(32767, mixedSample));
            
            mixed[i] = (byte) (mixedSample & 0xFF);
            if (i + 1 < length) {
                mixed[i + 1] = (byte) ((mixedSample >> 8) & 0xFF);
            }
        }
        
        return mixed;
    }
    
    private byte[] applyVolume(byte[] data, float volume) {
        if (volume == 1.0f) return data;
        
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i += 2) {
            if (i + 1 < data.length) {
                short sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                sample = (short) (sample * volume);
                result[i] = (byte) (sample & 0xFF);
                result[i + 1] = (byte) ((sample >> 8) & 0xFF);
            }
        }
        return result;
    }
    
    /**
     * 获取混合后的音频数据
     */
    public byte[] getMixedData() {
        return mixedAudioQueue.poll();
    }
    
    /**
     * 设置系统音量 (0.0 - 1.0)
     */
    public void setSystemVolume(float volume) {
        this.systemVolume = Math.max(0f, Math.min(1f, volume));
    }
    
    /**
     * 设置麦克风音量 (0.0 - 1.0)
     */
    public void setMicVolume(float volume) {
        this.micVolume = Math.max(0f, Math.min(1f, volume));
    }
    
    /**
     * 停止混合
     */
    public void stop() {
        isMixing = false;
        if (mixerThread != null) {
            mixerThread.interrupt();
            mixerThread = null;
        }
        systemAudioQueue.clear();
        micAudioQueue.clear();
        mixedAudioQueue.clear();
    }
}
