package com.example.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

/**
 * 录制前台服务：持有离屏预览表面、唤醒锁与通知，相机与 MediaRecorder 由 {@link RecorderEngine} 管理。
 * 因为录制过程完全不依赖 Activity 的窗口，所以按 POWER 熄屏后仍会继续录制。
 */
public class RecorderService extends Service {

    private static final String TAG = "RecorderService";

    public static final String ACTION_START = "com.example.recorder.action.START";
    public static final String ACTION_STOP = "com.example.recorder.action.STOP";

    private static final String CHANNEL_ID = "recorder_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int TICK_INTERVAL_MS = 1000;
    private static final int GRACE_TICKS = 5;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private PowerManager.WakeLock mWakeLock;
    private PreviewSurface mPreviewSurface;
    private RecorderConfig mConfig;
    private boolean mStopRequested;
    private int mTicks;

    private final Runnable mUpdate = new Runnable() {
        @Override
        public void run() {
            RecorderEngine engine = RecorderEngine.get();
            mTicks++;

            if (mStopRequested) {
                if (!engine.isBusy()) {
                    finish();
                    return;
                }
            } else if (engine.getState() == RecorderEngine.STATE_ERROR) {
                updateNotification(getString(R.string.notif_error, safe(engine.getLastError())));
                mTicks = -1000;
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }, 1500);
                return;
            } else if (!engine.isBusy() && mTicks > GRACE_TICKS) {
                // 引擎意外停止（例如系统回收相机），收尾避免留下一个空的前台服务
                finish();
                return;
            }

            updateNotification(buildStatusText(engine, mConfig));
            mMain.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        RecorderEngine.get().setListener(new RecorderEngine.Listener() {
            @Override
            public void onEngineStateChanged(RecorderEngine engine) {
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mStopRequested && RecorderEngine.get().getState() == RecorderEngine.STATE_RECORDING) {
                            updateNotification(buildStatusText(RecorderEngine.get(), mConfig));
                            mMain.removeCallbacks(mUpdate);
                            mMain.postDelayed(mUpdate, TICK_INTERVAL_MS);
                        }
                    }
                });
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (RecorderEngine.get().isBusy() || mPreviewSurface != null) {
                // 已经在录制或正在准备，忽略重复的启动请求
                return START_STICKY;
            }
            mConfig = RecorderConfig.fromIntent(intent);
            mStopRequested = false;
            mTicks = 0;
            acquireWakeLock();
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_preparing)));
            startPreviewSurface();
            mMain.removeCallbacks(mUpdate);
            mMain.postDelayed(mUpdate, TICK_INTERVAL_MS);
            return START_STICKY;
        }

        // 进程被系统重建时没有配置，无法恢复相机状态，直接结束，避免残留空服务
        Log.w(TAG, "收到无 Action 的启动请求，结束服务");
        requestStop();
        return START_NOT_STICKY;
    }

    private void startPreviewSurface() {
        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
        mPreviewSurface = new PreviewSurface(this, new PreviewSurface.Callback() {
            @Override
            public void onSurfaceReady(Surface surface) {
                if (mStopRequested) {
                    return;
                }
                RecorderEngine.get().start(getApplicationContext(), mConfig, surface);
            }

            @Override
            public void onSurfaceLost() {
                if (!mStopRequested) {
                    RecorderEngine.get().onPreviewSurfaceLost();
                }
            }
        });
        if (!mPreviewSurface.obtain()) {
            // 让引擎给出一个明确的错误信息，界面可据此提示
            RecorderEngine.get().start(getApplicationContext(), mConfig, null);
        }
    }

    private void requestStop() {
        mStopRequested = true;
        mMain.removeCallbacks(mUpdate);
        RecorderEngine.get().stop();
        mMain.postDelayed(mUpdate, 200);
    }

    private void finish() {
        mMain.removeCallbacks(mUpdate);
        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // ignore
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        mMain.removeCallbacks(mUpdate);
        RecorderEngine.get().setListener(null);
        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
        releaseWakeLock();
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

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
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

        Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags);
        builder.addAction(R.drawable.ic_stat_rec, getString(R.string.btn_stop), stopPendingIntent);

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
