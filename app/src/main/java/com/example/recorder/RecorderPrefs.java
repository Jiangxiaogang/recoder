package com.example.recorder;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 录制参数的统一存储。Activity 与 Service（硬件按键启动时）都要读取，
 * 因此不再使用 Activity 私有的 getPreferences()。
 */
public final class RecorderPrefs {

    public static final String FILE = "recorder_prefs";

    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_FPS = "fps";
    public static final String KEY_SEGMENT = "segment_ms";
    public static final String KEY_ORIENTATION = "orientation";
    public static final String KEY_CAMERA = "camera_id";
    public static final String KEY_FEEDBACK = "feedback_mode";
    public static final String KEY_CONFIG_VERSION = "config_version";
    private static final String KEY_MIGRATED = "migrated_to_recorder_prefs";

    /** 配置版本：升级到 2 时清空旧的默认选择，改用“最高分辨率/最高帧率/5 分钟分段”。 */
    public static final int CONFIG_VERSION = 2;

    private RecorderPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 把旧版写在 Activity 私有 preferences 里的参数迁移过来（一次性）。 */
    public static void migrateIfNeeded(Context context) {
        SharedPreferences prefs = get(context);
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            return;
        }
        SharedPreferences old = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (old.contains(KEY_WIDTH)) {
            editor.putInt(KEY_WIDTH, old.getInt(KEY_WIDTH, RecorderConfig.DEFAULT_WIDTH));
            editor.putInt(KEY_HEIGHT, old.getInt(KEY_HEIGHT, RecorderConfig.DEFAULT_HEIGHT));
            editor.putInt(KEY_FPS, old.getInt(KEY_FPS, RecorderConfig.DEFAULT_FPS));
            editor.putLong(KEY_SEGMENT, old.getLong(KEY_SEGMENT, RecorderConfig.DEFAULT_SEGMENT_MS));
            editor.putInt(KEY_ORIENTATION, old.getInt(KEY_ORIENTATION, RecorderConfig.DEFAULT_ORIENTATION));
            editor.putInt(KEY_CAMERA, old.getInt(KEY_CAMERA, RecorderConfig.DEFAULT_CAMERA_ID));
            editor.putInt(KEY_FEEDBACK, old.getInt(KEY_FEEDBACK, RecorderConfig.DEFAULT_FEEDBACK));
            editor.putInt(KEY_CONFIG_VERSION, old.getInt(KEY_CONFIG_VERSION, CONFIG_VERSION));
        }
        editor.putBoolean(KEY_MIGRATED, true).apply();
    }

    public static RecorderConfig load(Context context) {
        SharedPreferences prefs = get(context);
        return new RecorderConfig(
                prefs.getInt(KEY_WIDTH, RecorderConfig.DEFAULT_WIDTH),
                prefs.getInt(KEY_HEIGHT, RecorderConfig.DEFAULT_HEIGHT),
                prefs.getInt(KEY_FPS, RecorderConfig.DEFAULT_FPS),
                prefs.getLong(KEY_SEGMENT, RecorderConfig.DEFAULT_SEGMENT_MS),
                prefs.getInt(KEY_ORIENTATION, RecorderConfig.DEFAULT_ORIENTATION),
                prefs.getInt(KEY_CAMERA, RecorderConfig.DEFAULT_CAMERA_ID),
                prefs.getInt(KEY_FEEDBACK, RecorderConfig.DEFAULT_FEEDBACK));
    }

    public static void save(Context context, RecorderConfig config) {
        get(context).edit()
                .putInt(KEY_WIDTH, config.width)
                .putInt(KEY_HEIGHT, config.height)
                .putInt(KEY_FPS, config.fps)
                .putLong(KEY_SEGMENT, config.segmentMs)
                .putInt(KEY_ORIENTATION, config.orientation)
                .putInt(KEY_CAMERA, config.cameraId)
                .putInt(KEY_FEEDBACK, config.feedbackMode)
                .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
                .apply();
    }
}
