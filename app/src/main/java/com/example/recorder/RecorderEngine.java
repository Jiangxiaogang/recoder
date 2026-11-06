package com.example.recorder;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 录制引擎：持有相机与 MediaRecorder，运行在独立线程上。
 * <p>
 * 相机与 MediaRecorder 都由本类持有（不依赖 Activity 的窗口），
 * 因此 Activity 退到后台或按 POWER 键熄屏时录制不会中断。
 * <p>
 * 分段通过 {@link MediaRecorder#setMaxDuration(long)} + INFO 回调实现：
 * 到达单段时长后本次录制由系统自动停止，引擎立即关闭当前文件并开启下一段。
 */
public final class RecorderEngine {

    public interface Listener {
        void onEngineStateChanged(RecorderEngine engine);
    }

    private static final String TAG = "RecorderEngine";

    private static final int MAX_RESTART_FAILURES = 3;
    private static final int ROLLOVER_DELAY_MS = 300;
    private static final int RESTART_RETRY_DELAY_MS = 500;

    public static final int STATE_IDLE = 0;
    public static final int STATE_RECORDING = 1;
    public static final int STATE_ERROR = 2;

    private static volatile RecorderEngine sInstance;

    public static RecorderEngine get() {
        RecorderEngine instance = sInstance;
        if (instance == null) {
            synchronized (RecorderEngine.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new RecorderEngine();
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    private final HandlerThread mThread;
    private final Handler mHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile int mState = STATE_IDLE;
    private volatile boolean mPreparing;
    private volatile boolean mStopRequested;
    private volatile String mLastError;
    private volatile String mCurrentFile;
    private File mTempFile;
    private File mFinalFile;
    private volatile long mClosedElapsedMs;
    private volatile long mSegmentStartElapsed;
    private volatile int mSegmentCount;

    private Context mContext;
    private Listener mListener;

    private Camera mCamera;
    private boolean mCameraUnlocked;
    private int mSensorOrientation = 90;
    private MediaRecorder mRecorder;
    private RecorderConfig mConfig;
    private Surface mPreviewSurface;
    private File mOutputDir;

    private int mVideoWidth = RecorderConfig.DEFAULT_WIDTH;
    private int mVideoHeight = RecorderConfig.DEFAULT_HEIGHT;
    private int mVideoFps = RecorderConfig.DEFAULT_FPS;

    private int mRestartFailures;
    private int mFileIndex;

    private RecorderEngine() {
        mThread = new HandlerThread("recorder-engine");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    // ---------------------------------------------------------------------
    // 对外接口（均可在任意线程调用）
    // ---------------------------------------------------------------------

    public void start(final Context context, final RecorderConfig config, final Surface previewSurface) {
        if (context == null) {
            fail("内部错误：Context 为空");
            return;
        }
        final Context appContext = context.getApplicationContext();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                startInternal(appContext, config, previewSurface);
            }
        });
    }

    public void stop() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                stopInternal();
            }
        });
    }

    /** 预览表面失效（例如悬浮窗被系统移除）时调用。 */
    public void onPreviewSurfaceLost() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mState == STATE_RECORDING || mPreparing) {
                    cleanupAll();
                    fail("预览表面已失效，录制停止");
                }
            }
        });
    }

    public int getState() {
        return mState;
    }

    public boolean isRecording() {
        return mState == STATE_RECORDING;
    }

    /** 正在录制或正在准备/切换分段。 */
    public boolean isBusy() {
        return mPreparing || mState == STATE_RECORDING;
    }

    public String getLastError() {
        return mLastError;
    }

    public String getCurrentFile() {
        return mCurrentFile;
    }

    public int getSegmentCount() {
        return mSegmentCount;
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public String getOutputDirPath() {
        File dir = mOutputDir;
        return dir == null ? null : dir.getAbsolutePath();
    }

    public long getTotalElapsedMs() {
        long total = mClosedElapsedMs;
        long segmentStart = mSegmentStartElapsed;
        if (segmentStart > 0) {
            total += Math.max(0, SystemClock.elapsedRealtime() - segmentStart);
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // 引擎线程内部实现
    // ---------------------------------------------------------------------

    private void startInternal(Context context, RecorderConfig config, Surface previewSurface) {
        if (mPreparing || mState == STATE_RECORDING) {
            return;
        }
        if (previewSurface == null || !previewSurface.isValid()) {
            fail("预览表面不可用，无法录制");
            return;
        }

        mContext = context;
        mConfig = config;
        mPreviewSurface = previewSurface;
        mStopRequested = false;
        mPreparing = true;
        mState = STATE_IDLE;
        mLastError = null;
        mCurrentFile = null;
        mTempFile = null;
        mFinalFile = null;
        mClosedElapsedMs = 0;
        mSegmentStartElapsed = 0;
        mSegmentCount = 0;
        mRestartFailures = 0;
        mFileIndex = 0;

        mOutputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Recorder");
        if (!mOutputDir.exists() && !mOutputDir.mkdirs()) {
            mPreparing = false;
            fail("无法创建保存目录：" + mOutputDir.getAbsolutePath());
            return;
        }

        try {
            openCamera(config);
        } catch (Exception e) {
            Log.e(TAG, "打开相机失败", e);
            mPreparing = false;
            cleanupAll();
            fail("打开相机失败：" + describe(e));
            return;
        }

        boolean ok = startSegment();
        if (!ok) {
            mPreparing = false;
            cleanupAll();
            fail("启动录制失败，请检查分辨率/帧率是否被设备支持");
        }
    }

    private void openCamera(RecorderConfig config) throws IOException {
        releaseCamera();

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(config.cameraId, info);
        mSensorOrientation = info.orientation;

        Camera camera;
        try {
            camera = Camera.open(config.cameraId);
        } catch (RuntimeException e) {
            throw new IOException("相机可能被其他应用占用", e);
        }
        if (camera == null) {
            throw new IOException("无法打开相机");
        }

        try {
            Camera.Parameters params = camera.getParameters();
            params.setRecordingHint(true);

            int[] size = chooseSize(params.getSupportedPreviewSizes(), config.width, config.height);
            mVideoWidth = size[0];
            mVideoHeight = size[1];
            params.setPreviewSize(mVideoWidth, mVideoHeight);
            params.setPreviewFormat(ImageFormat.NV21);

            int[] fpsRange = chooseFpsRange(params.getSupportedPreviewFpsRange(), config.fps);
            mVideoFps = config.fps;
            if (fpsRange != null) {
                // 预览区间只决定相机采集帧率，录制帧率由 setVideoFrameRate 控制
                params.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
                if ((long) config.fps * 1000L > fpsRange[1]) {
                    mVideoFps = Math.round(fpsRange[1] / 1000f);
                } else if ((long) config.fps * 1000L < fpsRange[0]) {
                    mVideoFps = Math.round(fpsRange[0] / 1000f);
                }
                if (mVideoFps <= 0) {
                    mVideoFps = config.fps;
                }
            } else {
                try {
                    params.setPreviewFrameRate(config.fps);
                } catch (RuntimeException ignored) {
                    // 设备不支持设置固定帧率时忽略
                }
            }

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            try {
                if (params.isVideoStabilizationSupported()) {
                    params.setVideoStabilization(false);
                }
            } catch (RuntimeException ignored) {
                // ignore
            }

            camera.setParameters(params);
        } catch (RuntimeException e) {
            try {
                camera.release();
            } catch (RuntimeException ignored) {
                // ignore
            }
            throw new IOException("相机参数设置失败：" + describe(e), e);
        }

        mCamera = camera;
        mCameraUnlocked = false;
    }

    /**
     * 开始新的一段录制。
     *
     * @return 是否成功
     */
    private boolean startSegment() {
        if (mCamera == null) {
            return false;
        }
        File segmentDir = dateDir();
        if (segmentDir == null) {
            Log.e(TAG, "无法创建按日期划分的保存目录");
            return false;
        }

        MediaRecorder recorder = null;
        try {
            // 上一次录制结束后需要重新 lock/unlock，否则再次 setCamera 会失败。
            if (mCameraUnlocked) {
                try {
                    mCamera.lock();
                } catch (RuntimeException ignored) {
                    // ignore
                }
                mCameraUnlocked = false;
            }
            mCamera.unlock();
            mCameraUnlocked = true;

            recorder = new MediaRecorder();
            recorder.setCamera(mCamera);
            configureRecorder(recorder);

            // 录制时先写 .mp4.tmp，正常结束后再改名为 .mp4，
            // 避免录制中/异常中断时留下半截 .mp4 被媒体库扫到。
            File finalFile = new File(segmentDir, buildFileName());
            File tempFile = new File(segmentDir, finalFile.getName() + ".tmp");
            recorder.setOutputFile(tempFile.getAbsolutePath());
            mTempFile = tempFile;
            mFinalFile = finalFile;
            if (mConfig.segmentMs > 0) {
                recorder.setMaxDuration((int) Math.min(mConfig.segmentMs, Integer.MAX_VALUE));
            }
            recorder.setOnInfoListener(mInfoListener);
            recorder.setOnErrorListener(mErrorListener);
            recorder.setPreviewDisplay(mPreviewSurface);
            recorder.setOrientationHint(orientationHint());

            recorder.prepare();
            recorder.start();

            Log.i(TAG, "开始录制 camera=" + mConfig.cameraId + " " + mVideoWidth + "x" + mVideoHeight
                    + "@" + mVideoFps + "fps");
            mRecorder = recorder;
            mCurrentFile = finalFile.getAbsolutePath();
            mSegmentStartElapsed = SystemClock.elapsedRealtime();
            mSegmentCount++;
            mRestartFailures = 0;
            mPreparing = false;
            mState = STATE_RECORDING;
            notifyState();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startSegment 失败", e);
            if (recorder != null) {
                try {
                    recorder.reset();
                } catch (Exception ignored) {
                    // ignore
                }
                try {
                    recorder.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            mRecorder = null;
            if (mCamera != null && mCameraUnlocked) {
                try {
                    mCamera.lock();
                } catch (RuntimeException ignored) {
                    // ignore
                }
                mCameraUnlocked = false;
            }
            abortPendingFile();
            return false;
        }
    }

    private void configureRecorder(MediaRecorder recorder) {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        CamcorderProfile profile = findProfile(mConfig == null ? 0 : mConfig.cameraId, mVideoWidth, mVideoHeight);
        if (profile != null) {
            // 使用系统 CamcorderProfile 可以避免 H.264 level 不匹配导致的启动失败。
            recorder.setProfile(profile);
            if (profile.videoFrameWidth != mVideoWidth || profile.videoFrameHeight != mVideoHeight) {
                recorder.setVideoSize(mVideoWidth, mVideoHeight);
            }
            recorder.setVideoFrameRate(mVideoFps);
            int baseFps = profile.videoFrameRate > 0 ? profile.videoFrameRate : mVideoFps;
            int bitRate = profile.videoBitRate;
            if (baseFps > 0 && mVideoFps > 0 && mVideoFps != baseFps) {
                bitRate = (int) ((long) profile.videoBitRate * mVideoFps / baseFps);
            }
            recorder.setVideoEncodingBitRate(clamp(bitRate, 1500000, 20000000));
        } else {
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioChannels(2);
            recorder.setVideoSize(mVideoWidth, mVideoHeight);
            recorder.setVideoFrameRate(mVideoFps);
            recorder.setVideoEncodingBitRate(estimateBitrate(mVideoWidth, mVideoHeight, mVideoFps));
        }
    }

    private CamcorderProfile findProfile(int cameraId, int width, int height) {
        int[] qualities = new int[]{
                CamcorderProfile.QUALITY_1080P,
                CamcorderProfile.QUALITY_720P,
                CamcorderProfile.QUALITY_480P,
                CamcorderProfile.QUALITY_CIF,
                CamcorderProfile.QUALITY_QVGA,
                CamcorderProfile.QUALITY_QCIF
        };
        for (int quality : qualities) {
            try {
                if (!CamcorderProfile.hasProfile(cameraId, quality)) {
                    continue;
                }
                CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                if (profile != null
                        && profile.videoFrameWidth == width
                        && profile.videoFrameHeight == height) {
                    return profile;
                }
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
        return null;
    }

    private int orientationHint() {
        int sensor = ((mSensorOrientation % 360) + 360) % 360;
        int mode = mConfig == null ? RecorderConfig.DEFAULT_ORIENTATION : mConfig.orientation;
        if (mode == RecorderConfig.ORIENTATION_PORTRAIT) {
            // 强制竖屏：横向缓冲 + 旋转 90°（即传感器方向），播放器显示为竖向
            return sensor;
        }
        if (mode == RecorderConfig.ORIENTATION_AUTO) {
            // 自动：按当前屏幕方向校正，竖持正立、横持也正立
            int degrees = displayRotationDegrees();
            return ((sensor - degrees) % 360 + 360) % 360;
        }
        // 强制横屏：传感器方向逆时针转 90°，让横向画面正立（常见 90° → 0°）
        return (sensor + 270) % 360;
    }

    /** 当前屏幕旋转角度（0/90/180/270）。 */
    private int displayRotationDegrees() {
        try {
            if (mContext != null) {
                WindowManager windowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager != null) {
                    Display display = windowManager.getDefaultDisplay();
                    if (display != null) {
                        switch (display.getRotation()) {
                            case Surface.ROTATION_90:
                                return 90;
                            case Surface.ROTATION_180:
                                return 180;
                            case Surface.ROTATION_270:
                                return 270;
                            default:
                                return 0;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取屏幕方向失败", t);
        }
        return 0;
    }

    /** 按日期（yyyy-MM-dd）创建当天的子目录。 */
    private File dateDir() {
        File base = mOutputDir;
        if (base == null) {
            return null;
        }
        File dir = new File(base, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private String buildFileName() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String base = "REC_" + format.format(new Date());
        mFileIndex++;
        return String.format(Locale.US, "%s_%02d.mp4", base, mFileIndex);
    }

    /** 到达单段时长：关闭当前文件并立即开始下一段。 */
    private void rollOver() {
        if (mStopRequested || mState != STATE_RECORDING) {
            return;
        }

        if (mSegmentStartElapsed > 0) {
            mClosedElapsedMs += Math.max(0, SystemClock.elapsedRealtime() - mSegmentStartElapsed);
            mSegmentStartElapsed = 0;
        }
        // 此时 MediaRecorder 已因 setMaxDuration 自动停止，不能再调用 stop()。
        releaseRecorder(false, true, true);
        mCurrentFile = null;
        mPreparing = true;

        final Runnable restart = new Runnable() {
            @Override
            public void run() {
                if (mStopRequested) {
                    mPreparing = false;
                    notifyState();
                    return;
                }
                if (startSegment()) {
                    return;
                }
                mRestartFailures++;
                if (mRestartFailures >= MAX_RESTART_FAILURES) {
                    mPreparing = false;
                    cleanupAll();
                    fail("分段续录失败，录制已停止");
                } else {
                    mHandler.postDelayed(this, RESTART_RETRY_DELAY_MS);
                }
            }
        };
        mHandler.postDelayed(restart, ROLLOVER_DELAY_MS);
    }

    private void stopInternal() {
        mStopRequested = true;
        mPreparing = false;
        if (mSegmentStartElapsed > 0) {
            mClosedElapsedMs += Math.max(0, SystemClock.elapsedRealtime() - mSegmentStartElapsed);
            mSegmentStartElapsed = 0;
        }
        releaseRecorder(mState == STATE_RECORDING, false, true);
        releaseCamera();
        mCurrentFile = null;
        mState = STATE_IDLE;
        notifyState();
    }

    private void cleanupAll() {
        releaseRecorder(mState == STATE_RECORDING, false, true);
        releaseCamera();
        mPreparing = false;
        mCurrentFile = null;
    }

    private void releaseRecorder(boolean callStop, boolean assumeStoppedCleanly, boolean finalizeFile) {
        MediaRecorder recorder = mRecorder;
        mRecorder = null;
        // 到达单段时长时由系统自动停止，可视为正常结束
        boolean stoppedCleanly = assumeStoppedCleanly;

        if (recorder != null) {
            if (callStop) {
                try {
                    recorder.stop();
                    stoppedCleanly = true;
                } catch (RuntimeException e) {
                    Log.w(TAG, "停止录制失败（文件可能不完整）", e);
                }
            }
            try {
                recorder.reset();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
                // ignore
            }
        }

        if (mCamera != null && mCameraUnlocked) {
            try {
                mCamera.lock();
            } catch (RuntimeException ignored) {
                // ignore
            }
            mCameraUnlocked = false;
        }

        if (finalizeFile) {
            finalizeSegment(stoppedCleanly);
        }
    }

    /** 录制结束：把 .tmp 改名为正式文件并通知媒体库；异常结束时保留 .tmp 便于识别。 */
    private void finalizeSegment(boolean stoppedCleanly) {
        File temp = mTempFile;
        File target = mFinalFile;
        mTempFile = null;
        mFinalFile = null;
        if (temp == null || target == null || !temp.exists()) {
            return;
        }
        if (temp.length() <= 0L) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        if (!stoppedCleanly) {
            Log.w(TAG, "录制未正常结束，保留临时文件：" + temp.getName());
            return;
        }
        if (temp.renameTo(target)) {
            scanMedia(target.getAbsolutePath());
        } else {
            Log.e(TAG, "重命名临时文件失败：" + temp.getAbsolutePath());
        }
    }

    /** 启动失败时清理尚未写入有效内容的临时文件。 */
    private void abortPendingFile() {
        File temp = mTempFile;
        mTempFile = null;
        mFinalFile = null;
        if (temp != null && temp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private void releaseCamera() {
        Camera camera = mCamera;
        mCamera = null;
        if (camera != null) {
            if (mCameraUnlocked) {
                try {
                    camera.lock();
                } catch (RuntimeException ignored) {
                    // ignore
                }
                mCameraUnlocked = false;
            }
            try {
                camera.stopPreview();
            } catch (RuntimeException ignored) {
                // ignore
            }
            try {
                camera.release();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
    }

    private void scanMedia(String path) {
        if (mContext == null) {
            return;
        }
        try {
            MediaScannerConnection.scanFile(mContext, new String[]{path}, new String[]{"video/mp4"}, null);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void fail(String message) {
        Log.e(TAG, message);
        mLastError = message;
        mState = STATE_ERROR;
        notifyState();
    }

    private void notifyState() {
        if (mListener == null) {
            return;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                Listener listener = mListener;
                if (listener != null) {
                    listener.onEngineStateChanged(RecorderEngine.this);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // MediaRecorder 回调（运行在引擎线程）
    // ---------------------------------------------------------------------

    private final MediaRecorder.OnInfoListener mInfoListener = new MediaRecorder.OnInfoListener() {
        @Override
        public void onInfo(MediaRecorder mr, int what, int extra) {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                Log.i(TAG, "单段时长到达，切换下一段");
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        rollOver();
                    }
                });
            }
        }
    };

    private final MediaRecorder.OnErrorListener mErrorListener = new MediaRecorder.OnErrorListener() {
        @Override
        public void onError(MediaRecorder mr, final int what, final int extra) {
            Log.e(TAG, "MediaRecorder 错误 what=" + what + " extra=" + extra);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mSegmentStartElapsed > 0) {
                        mClosedElapsedMs += Math.max(0, SystemClock.elapsedRealtime() - mSegmentStartElapsed);
                        mSegmentStartElapsed = 0;
                    }
                    releaseRecorder(false, false, true);
                    releaseCamera();
                    mPreparing = false;
                    mCurrentFile = null;
                    fail("录制出错（code=" + what + "，extra=" + extra + "）");
                }
            });
        }
    };

    // ---------------------------------------------------------------------
    // 参数选择工具
    // ---------------------------------------------------------------------

    private static int[] chooseSize(List<Camera.Size> supported, int wantedWidth, int wantedHeight) {
        int wantW = Math.max(wantedWidth, wantedHeight);
        int wantH = Math.min(wantedWidth, wantedHeight);
        int[] fallback = null;
        long fallbackScore = Long.MAX_VALUE;
        if (supported != null) {
            for (Camera.Size size : supported) {
                if (size == null) {
                    continue;
                }
                int w = Math.max(size.width, size.height);
                int h = Math.min(size.width, size.height);
                if (w == wantW && h == wantH) {
                    return new int[]{w, h};
                }
                double wantRatio = (double) wantW / wantH;
                double ratio = (double) w / h;
                long score = Math.abs((long) w * h - (long) wantW * wantH)
                        + (long) (Math.abs(ratio - wantRatio) * 1000000);
                if (score < fallbackScore) {
                    fallbackScore = score;
                    fallback = new int[]{w, h};
                }
            }
        }
        return fallback != null ? fallback : new int[]{wantW, wantH};
    }

    private static int[] chooseFpsRange(List<int[]> ranges, int wantedFps) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        int target = Math.max(1, wantedFps) * 1000;
        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                continue;
            }
            int min = range[0];
            int max = range[1];
            if (max < target) {
                // 优先选择能满足目标帧率的区间
                continue;
            }
            long score = (long) (max - target) * 10 + Math.max(0, target - min);
            if (score < bestScore) {
                bestScore = score;
                best = range;
            }
        }
        if (best != null) {
            return best;
        }
        // 没有能满足目标帧率的区间时，取上限最接近的区间
        best = ranges.get(0);
        long bestDelta = Math.abs((long) best[1] - target);
        for (int[] range : ranges) {
            long delta = Math.abs((long) range[1] - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = range;
            }
        }
        return best;
    }

    private static int estimateBitrate(int width, int height, int fps) {
        long bitRate = (long) width * height * Math.max(1, fps) * 3L / 20L;
        return clamp((int) Math.min(bitRate, Integer.MAX_VALUE), 2000000, 20000000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return (message == null || message.isEmpty()) ? throwable.getClass().getSimpleName() : message;
    }
}
