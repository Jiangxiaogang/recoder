package com.example.recorder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

/**
 * 录制开始/停止提示音，播放 res/raw 下的 WAV 资源：
 * <ul>
 *   <li>start_ding.wav：一声稍长的「叮~」（1.2kHz 单频纯音，带衰减尾音）</li>
 *   <li>stop_dong.wav：两声较短的「咚咚」（380~420Hz，中间有间隔）</li>
 * </ul>
 */
final class SoundPlayer {

    private static final String TAG = "SoundPlayer";

    private SoundPool mSoundPool;
    private int mStartId;
    private int mStopId;
    private boolean mStartLoaded;
    private boolean mStopLoaded;

    SoundPlayer(Context context) {
        try {
            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            mSoundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                    if (status != 0) {
                        Log.w(TAG, "音效加载失败 sampleId=" + sampleId + " status=" + status);
                        return;
                    }
                    if (sampleId == mStartId) {
                        mStartLoaded = true;
                    } else if (sampleId == mStopId) {
                        mStopLoaded = true;
                    }
                }
            });
            mStartId = mSoundPool.load(context, R.raw.start_ding, 1);
            mStopId = mSoundPool.load(context, R.raw.stop_dong, 1);
        } catch (Throwable t) {
            Log.w(TAG, "初始化音效池失败", t);
            mSoundPool = null;
        }
    }

    void playStart() {
        play(mStartId, mStartLoaded, "开始");
    }

    void playStop() {
        play(mStopId, mStopLoaded, "停止");
    }

    private void play(int soundId, boolean loaded, String name) {
        if (mSoundPool == null || soundId == 0) {
            Log.w(TAG, "播放" + name + "音效失败：未初始化");
            return;
        }
        if (!loaded) {
            Log.w(TAG, "播放" + name + "音效失败：资源尚未加载完成");
            return;
        }
        try {
            mSoundPool.play(soundId, 1f, 1f, 1, 0, 1f);
            Log.i(TAG, "播放" + name + "音效");
        } catch (Throwable t) {
            Log.w(TAG, "播放" + name + "音效失败", t);
        }
    }

    void release() {
        if (mSoundPool != null) {
            try {
                mSoundPool.release();
            } catch (Throwable ignored) {
                // ignore
            }
            mSoundPool = null;
        }
    }
}
