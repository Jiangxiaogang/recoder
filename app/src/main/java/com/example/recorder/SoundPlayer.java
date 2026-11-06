package com.example.recorder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * 录制开始/停止提示音，播放 res/raw 下的 WAV 资源（SoundPool 低延迟通道）：
 * <ul>
 *   <li>start.wav：一声稍长的「叮~」（1.2kHz 单频纯音，带衰减尾音）</li>
 *   <li>stop.wav：两声较短的「咚咚」（380~420Hz，中间有间隔）</li>
 * </ul>
 * SoundPool 没有播放完成回调，因此启动录制前会按 WAV 文件本身的时长等待，
 * 保证「叮~」播完再开相机/录音（时长来自文件，不写死）。
 */
final class SoundPlayer {

    private static final String TAG = "SoundPlayer";
    private static final float VOLUME = 0.85f;

    private SoundPool mSoundPool;
    private int mStartId;
    private int mStopId;
    private boolean mStartLoaded;
    private boolean mStopLoaded;
    private int mStartDurationMs;

    SoundPlayer(Context context) {
        mStartDurationMs = readWavDurationMs(context.getApplicationContext(), R.raw.start);
        try {
            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            mStartId = mSoundPool.load(context, R.raw.start, 1);
            mStopId = mSoundPool.load(context, R.raw.stop, 1);
        } catch (Throwable t) {
            Log.w(TAG, "初始化 SoundPool 失败", t);
            mSoundPool = null;
        }
    }

    /** 开始音效的实际时长（毫秒），读取自 WAV 文件头。 */
    int getStartDurationMs() {
        return mStartDurationMs;
    }

    /** @return 是否真的开始播放（资源未加载完成时返回 false） */
    boolean playStart() {
        return play(mStartId, mStartLoaded, "开始");
    }

    void playStop() {
        play(mStopId, mStopLoaded, "停止");
    }

    private boolean play(int soundId, boolean loaded, String name) {
        if (mSoundPool == null || soundId == 0) {
            Log.w(TAG, "播放" + name + "音效失败：未初始化");
            return false;
        }
        if (!loaded) {
            Log.w(TAG, "播放" + name + "音效失败：资源尚未加载完成");
            return false;
        }
        try {
            mSoundPool.play(soundId, VOLUME, VOLUME, 1, 0, 1f);
            Log.i(TAG, "播放" + name + "音效");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "播放" + name + "音效失败", t);
            return false;
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

    /** 解析标准 PCM WAV 的文件头，返回时长；失败返回 0。 */
    private static int readWavDurationMs(Context context, int resId) {
        DataInputStream in = null;
        try {
            InputStream raw = context.getResources().openRawResource(resId);
            in = new DataInputStream(raw);
            byte[] id = new byte[4];
            in.readFully(id);
            if (!"RIFF".equals(new String(id, "US-ASCII"))) {
                return 0;
            }
            readIntLE(in); // RIFF size
            in.readFully(id);
            if (!"WAVE".equals(new String(id, "US-ASCII"))) {
                return 0;
            }
            int byteRate = 0;
            while (true) {
                if (in.read(id) < 4) {
                    break;
                }
                String chunk = new String(id, "US-ASCII");
                int size = readIntLE(in);
                if ("fmt ".equals(chunk)) {
                    int readSize = Math.min(size, 64);
                    byte[] fmt = new byte[readSize];
                    in.readFully(fmt);
                    if (fmt.length >= 12) {
                        byteRate = (fmt[8] & 0xff)
                                | ((fmt[9] & 0xff) << 8)
                                | ((fmt[10] & 0xff) << 16)
                                | ((fmt[11] & 0xff) << 24);
                    }
                    skipFully(in, size - readSize);
                } else if ("data".equals(chunk)) {
                    if (byteRate > 0) {
                        return (int) ((long) size * 1000L / byteRate);
                    }
                    return 0;
                } else {
                    skipFully(in, size);
                }
                if ((size & 1) == 1) {
                    in.readByte();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析 WAV 时长失败", t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return 0;
    }

    private static int readIntLE(DataInputStream in) throws Exception {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void skipFully(DataInputStream in, int count) throws Exception {
        int remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                remaining--;
            } else {
                remaining -= (int) skipped;
            }
        }
    }
}
