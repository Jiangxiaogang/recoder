package com.example.recorder;

import android.content.Intent;

/**
 * 录制参数：分辨率、帧率、单段时长、视频方向。
 */
public final class RecorderConfig {

    public static final String EXTRA_WIDTH = "rec_width";
    public static final String EXTRA_HEIGHT = "rec_height";
    public static final String EXTRA_FPS = "rec_fps";
    public static final String EXTRA_SEGMENT_MS = "rec_segment_ms";
    public static final String EXTRA_ORIENTATION = "rec_orientation";
    public static final String EXTRA_CAMERA_ID = "rec_camera_id";

    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;
    public static final int DEFAULT_FPS = 30;
    public static final long DEFAULT_SEGMENT_MS = 5 * 60 * 1000L;
    public static final int DEFAULT_CAMERA_ID = 0;

    /** 视频方向：强制横屏（文件为 1920×1080 等横向尺寸）。 */
    public static final int ORIENTATION_LANDSCAPE = 0;
    /** 视频方向：强制竖屏（横向缓冲 + 90° 旋转标记，播放为 1080×1920 等竖向）。 */
    public static final int ORIENTATION_PORTRAIT = 1;
    /** 视频方向：自动跟随手机方向（每段录制开始时取一次屏幕方向）。 */
    public static final int ORIENTATION_AUTO = 2;
    public static final int DEFAULT_ORIENTATION = ORIENTATION_AUTO;

    public final int width;
    public final int height;
    public final int fps;
    /** 单段最大时长，0 表示不切割。 */
    public final long segmentMs;
    public final int orientation;
    /** Camera1 的摄像头 id。 */
    public final int cameraId;

    public RecorderConfig(int width, int height, int fps, long segmentMs) {
        this(width, height, fps, segmentMs, DEFAULT_ORIENTATION, DEFAULT_CAMERA_ID);
    }

    public RecorderConfig(int width, int height, int fps, long segmentMs, int orientation) {
        this(width, height, fps, segmentMs, orientation, DEFAULT_CAMERA_ID);
    }

    public RecorderConfig(int width, int height, int fps, long segmentMs, int orientation, int cameraId) {
        this.width = Math.max(160, width);
        this.height = Math.max(120, height);
        this.fps = Math.max(1, Math.min(60, fps));
        this.segmentMs = Math.max(0, segmentMs);
        this.orientation = (orientation == ORIENTATION_PORTRAIT || orientation == ORIENTATION_AUTO)
                ? orientation : ORIENTATION_LANDSCAPE;
        this.cameraId = Math.max(0, cameraId);
    }

    public boolean isPortrait() {
        return orientation == ORIENTATION_PORTRAIT;
    }

    public boolean isAutoOrientation() {
        return orientation == ORIENTATION_AUTO;
    }

    public String orientationName() {
        if (orientation == ORIENTATION_PORTRAIT) {
            return "竖屏";
        }
        if (orientation == ORIENTATION_AUTO) {
            return "自动";
        }
        return "横屏";
    }

    public void putTo(Intent intent) {
        intent.putExtra(EXTRA_WIDTH, width);
        intent.putExtra(EXTRA_HEIGHT, height);
        intent.putExtra(EXTRA_FPS, fps);
        intent.putExtra(EXTRA_SEGMENT_MS, segmentMs);
        intent.putExtra(EXTRA_ORIENTATION, orientation);
        intent.putExtra(EXTRA_CAMERA_ID, cameraId);
    }

    public static RecorderConfig fromIntent(Intent intent) {
        if (intent == null) {
            return new RecorderConfig(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FPS, DEFAULT_SEGMENT_MS);
        }
        return new RecorderConfig(
                intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH),
                intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT),
                intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS),
                intent.getLongExtra(EXTRA_SEGMENT_MS, DEFAULT_SEGMENT_MS),
                intent.getIntExtra(EXTRA_ORIENTATION, DEFAULT_ORIENTATION),
                intent.getIntExtra(EXTRA_CAMERA_ID, DEFAULT_CAMERA_ID));
    }

    public String describe() {
        String seg = segmentMs > 0 ? (segmentMs / 60000L) + " 分钟分段" : "不分割";
        return width + "×" + height + "@" + fps + "fps · " + orientationName() + " · " + seg;
    }
}
