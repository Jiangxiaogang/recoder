package com.example.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

/**
 * 录制前台服务：持有离屏预览表面、唤醒锁、通知、MediaSession（蓝牙媒体键）与提示音。
 * 相机与 MediaRecorder 由 {@link RecorderEngine} 管理，录制不依赖 Activity 窗口，
 * 因此按 POWER 熄屏后仍会继续录制。
 * <p>
 * 「待机」状态下服务保持前台并持有活跃 MediaSession，
 * 按蓝牙键盘/耳机发送的播放暂停键（KEYCODE_MEDIA_PLAY_PAUSE）即可开始或停止录制。
 */
public class RecorderService extends Service {

    private static final String TAG = "RecorderService";

    public static final String ACTION_START = "com.example.recorder.action.START";
    public static final String ACTION_STOP = "com.example.recorder.action.STOP";
    /** 进入待机：保持前台通知与 MediaSession，等待硬件按键。 */
    public static final String ACTION_STANDBY = "com.example.recorder.action.STANDBY";
    /** 退出待机：结束服务与通知（硬件按键失效，直到再次打开 APP）。 */
    public static final String ACTION_EXIT = "com.example.recorder.action.EXIT";

    private static final String CHANNEL_ID = "recorder_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int TICK_INTERVAL_MS = 1000;
    private static final int GRACE_TICKS = 5;
    private static final long TOGGLE_DEBOUNCE_MS = 500;
    /** 开始音播放结束后再留一点余量，让扬声器尾音/混响衰减（真正的延迟由音频播放完成回调决定）。 */
    private static final int START_SETTLE_MS = 120;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private PowerManager.WakeLock mWakeLock;
    private PreviewSurface mPreviewSurface;
    private RecorderConfig mConfig;
    private MediaSession mMediaSession;
    private SoundPlayer mSound;
    private Vibrator mVibrator;
    private boolean mStopRequested;
    private boolean mExitRequested;
    private boolean mStandby;
    private boolean mStartPending;
    private boolean mWasRecording;
    private long mLastToggleElapsed;
    private int mTicks;

    /** 播放完开始提示音后再真正启动录制。 */
    private final Runnable mStartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mStartPending || mStopRequested || mExitRequested) {
                return;
            }
            mStartPending = false;
            startPreviewSurface();
        }
    };

    private final Runnable mUpdate = new Runnable() {
        @Override
        public void run() {
            RecorderEngine engine = RecorderEngine.get();
            mTicks++;

            if (mExitRequested) {
                if (!engine.isBusy()) {
                    shutdown();
                    return;
                }
            } else if (mStopRequested) {
                if (!engine.isBusy()) {
                    enterStandby();
                    return;
                }
            } else if (engine.getState() == RecorderEngine.STATE_ERROR) {
                updateNotification(getString(R.string.notif_error, safe(engine.getLastError())), false);
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!mExitRequested) {
                            enterStandby();
                        }
                    }
                }, 1500);
                return;
            } else if (!engine.isBusy() && mTicks > GRACE_TICKS) {
                // 引擎意外停止（例如系统回收相机），回到待机而不是留下空服务
                enterStandby();
                return;
            }

            updateNotification(buildStatusText(engine, mConfig), false);
            mMain.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    private final RecorderEngine.Listener mEngineListener = new RecorderEngine.Listener() {
        @Override
        public void onEngineStateChanged(RecorderEngine engine) {
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    RecorderEngine engine = RecorderEngine.get();
                    boolean recording = engine.getState() == RecorderEngine.STATE_RECORDING;
                    if (recording != mWasRecording) {
                        mWasRecording = recording;
                        if (!recording) {
                            // 停止时录音链已释放，此时反馈不受影响
                            feedbackStop();
                        }
                        updatePlaybackState();
                    }
                    if (!mStopRequested && !mExitRequested && recording) {
                        mStandby = false;
                        updateNotification(buildStatusText(engine, mConfig), false);
                        mMain.removeCallbacks(mUpdate);
                        mMain.postDelayed(mUpdate, TICK_INTERVAL_MS);
                    }
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        initMediaSession();
        mSound = new SoundPlayer(this);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        RecorderEngine.get().setListener(mEngineListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_EXIT.equals(action)) {
            mExitRequested = true;
            mStandby = false;
            mMain.removeCallbacks(mUpdate);
            if (RecorderEngine.get().isBusy()) {
                mStopRequested = true;
                RecorderEngine.get().stop();
                mMain.postDelayed(mUpdate, 200);
            } else {
                shutdown();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (RecorderEngine.get().isBusy() || mPreviewSurface != null || mStartPending) {
                // 已经在录制或正在准备，忽略重复的启动请求
                return START_STICKY;
            }
            startRecording(RecorderConfig.fromIntent(intent));
            return START_STICKY;
        }

        if (ACTION_STANDBY.equals(action)) {
            if (!RecorderEngine.get().isBusy() && !mStartPending) {
                enterStandby();
            }
            return START_STICKY;
        }

        // 进程被系统重建（sticky）时没有配置，进入待机等待按键
        Log.w(TAG, "收到无 Action 的启动请求，进入待机");
        if (!RecorderEngine.get().isBusy()) {
            enterStandby();
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------------
    // 录制控制
    // ---------------------------------------------------------------------

    private void startRecording(RecorderConfig config) {
        mConfig = config;
        mStandby = false;
        mStopRequested = false;
        mExitRequested = false;
        mTicks = 0;
        acquireWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_preparing), false));
        updatePlaybackState();
        // 先完成启停反馈，再启动相机/录音，避免音频输入链路切换把提示音弄脏
        mStartPending = true;
        mMain.removeCallbacks(mStartRunnable);
        boolean played = feedbackStart();
        int delay = 0;
        if (played && mSound != null) {
            delay = mSound.getStartDurationMs() + START_SETTLE_MS;
        }
        mMain.postDelayed(mStartRunnable, delay);
        mMain.removeCallbacks(mUpdate);
        mMain.postDelayed(mUpdate, TICK_INTERVAL_MS);
    }

    /** 回到待机：释放相机/预览/唤醒锁，保留前台通知与 MediaSession。 */
    private void enterStandby() {
        mStandby = true;
        mStopRequested = false;
        mWasRecording = false;
        mStartPending = false;
        mMain.removeCallbacks(mStartRunnable);
        mMain.removeCallbacks(mUpdate);
        releasePreview();
        releaseWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_standby), true));
        updatePlaybackState();
    }

    private void requestStop() {
        if (mStartPending) {
            // 开始音还在播、录制尚未启动：直接取消并回到待机
            mStartPending = false;
            mMain.removeCallbacks(mStartRunnable);
            playStopSound();
            enterStandby();
            return;
        }
        if (mStandby && !RecorderEngine.get().isBusy()) {
            return;
        }
        mStopRequested = true;
        mMain.removeCallbacks(mUpdate);
        RecorderEngine.get().stop();
        mMain.postDelayed(mUpdate, 200);
    }

    private void shutdown() {
        mStartPending = false;
        mMain.removeCallbacks(mStartRunnable);
        mMain.removeCallbacks(mUpdate);
        releasePreview();
        releaseWakeLock();
        if (mMediaSession != null) {
            try {
                mMediaSession.setActive(false);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // ignore
        }
        stopSelf();
    }

    private void startPreviewSurface() {
        releasePreview();
        mPreviewSurface = new PreviewSurface(this, new PreviewSurface.Callback() {
            @Override
            public void onSurfaceReady(Surface surface) {
                if (mStopRequested || mExitRequested) {
                    return;
                }
                RecorderEngine.get().start(getApplicationContext(), mConfig, surface);
            }

            @Override
            public void onSurfaceLost() {
                if (!mStopRequested && !mExitRequested) {
                    RecorderEngine.get().onPreviewSurfaceLost();
                }
            }
        });
        if (!mPreviewSurface.obtain()) {
            // 让引擎给出一个明确的错误信息，界面可据此提示
            RecorderEngine.get().start(getApplicationContext(), mConfig, null);
        }
    }

    private void releasePreview() {
        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
    }

    // ---------------------------------------------------------------------
    // 硬件按键（MediaSession）
    // ---------------------------------------------------------------------

    private void initMediaSession() {
        try {
            mMediaSession = new MediaSession(this, "recorder_media_session");
            mMediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mMediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    KeyEvent event = mediaButtonIntent == null ? null
                            : (KeyEvent) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null
                            && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getRepeatCount() == 0
                            && isToggleKey(event.getKeyCode())) {
                        toggleFromHardware();
                        return true;
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }

                @Override
                public void onPlay() {
                    toggleFromHardware();
                }

                @Override
                public void onPause() {
                    toggleFromHardware();
                }
            });
            mMediaSession.setActive(true);
            updatePlaybackState();
        } catch (Throwable t) {
            Log.w(TAG, "初始化 MediaSession 失败", t);
        }
    }

    private static boolean isToggleKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    /** 蓝牙/有线耳机的播放暂停键：录制中则停止，否则用上次参数开始录制。 */
    private void toggleFromHardware() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastToggleElapsed < TOGGLE_DEBOUNCE_MS) {
            return;
        }
        mLastToggleElapsed = now;
        if (RecorderEngine.get().isBusy() || mStartPending) {
            Log.i(TAG, "硬件按键：停止录制");
            requestStop();
        } else {
            Log.i(TAG, "硬件按键：开始录制");
            startRecording(RecorderPrefs.load(this));
        }
    }

    private void updatePlaybackState() {
        if (mMediaSession == null) {
            return;
        }
        try {
            boolean busy = RecorderEngine.get().isBusy();
            mMediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_STOP)
                    .setState(busy ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 开始：一声稍长的「叮~」；播放完成后回调，由调用方决定何时真正启动录制。 */
    /** 开始反馈：按设置振动和/或播放音效；返回开始音效是否已真正开始播放。 */
    private boolean feedbackStart() {
        boolean vibration = mConfig != null && mConfig.hasVibrationFeedback();
        if (vibration) {
            vibrate(true);
        }
        if (mConfig != null && mConfig.hasSoundFeedback() && mSound != null) {
            return mSound.playStart();
        }
        return false;
    }

    private void feedbackStop() {
        boolean sound = mConfig != null && mConfig.hasSoundFeedback();
        boolean vibration = mConfig != null && mConfig.hasVibrationFeedback();
        if (vibration) {
            vibrate(false);
        }
        if (sound) {
            playStopSound();
        }
    }

    /** 开始：一次较长振动；停止：两次短振，便于分辨。 */
    private void vibrate(boolean start) {
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }
        try {
            if (start) {
                mVibrator.vibrate(180);
            } else {
                mVibrator.vibrate(new long[]{0, 90, 80, 90}, -1);
            }
            Log.i(TAG, start ? "振动：开始" : "振动：停止");
        } catch (Throwable t) {
            Log.w(TAG, "振动失败", t);
        }
    }

    private void playStopSound() {
        if (mSound != null) {
            mSound.playStop();
        }
    }

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    @Override
    public void onDestroy() {
        mMain.removeCallbacks(mStartRunnable);
        mMain.removeCallbacks(mUpdate);
        RecorderEngine.get().setListener(null);
        releasePreview();
        releaseWakeLock();
        if (mMediaSession != null) {
            try {
                mMediaSession.setActive(false);
                mMediaSession.release();
            } catch (Throwable ignored) {
                // ignore
            }
            mMediaSession = null;
        }
        if (mSound != null) {
            mSound.release();
            mSound = null;
        }
        if (mVibrator != null) {
            try {
                mVibrator.cancel();
            } catch (Throwable ignored) {
                // ignore
            }
            mVibrator = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------
    // 唤醒锁 / 通知
    // ---------------------------------------------------------------------

    @android.annotation.SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recorder:recording");
            mWakeLock.setReferenceCounted(false);
        }
        if (!mWakeLock.isHeld()) {
            // 熄屏后仍需保持 CPU 运行；录完由用户点“停止录制”时释放
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private String buildStatusText(RecorderEngine engine, RecorderConfig config) {
        String resolution;
        if (config != null) {
            resolution = config.width + "×" + config.height + "@" + config.fps + "fps · "
                    + config.orientationName();
        } else {
            resolution = engine.getVideoWidth() + "×" + engine.getVideoHeight();
        }
        return getString(R.string.notif_recording, formatDuration(engine.getTotalElapsedMs()), resolution);
    }

    private void updateNotification(String text, boolean standbyMode) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text, standbyMode));
        }
    }

    private Notification buildNotification(String text, boolean standbyMode) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel();
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false);

        if (standbyMode) {
            Intent exitIntent = new Intent(this, RecorderService.class).setAction(ACTION_EXIT);
            PendingIntent exitPendingIntent = PendingIntent.getService(this, 2, exitIntent, pendingFlags);
            builder.addAction(R.drawable.ic_stat_rec, getString(R.string.btn_exit_standby), exitPendingIntent);
        } else {
            Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
            PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags);
            builder.addAction(R.drawable.ic_stat_rec, getString(R.string.btn_stop), stopPendingIntent);
        }

        return builder.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
