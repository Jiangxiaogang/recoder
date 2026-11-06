package com.example.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：选择分辨率 / 帧率 / 分段时长，开始或停止录制。
 * <p>
 * 界面只是参数与控制面板，真正的录制在 {@link RecorderService} 中完成，
 * 所以返回桌面或按 POWER 熄屏都不会中断录制。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1001;

    private static final String[] PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    /** 与 arrays.xml 中 segment_labels / SEGMENT_VALUES 一一对应。 */
    private static final long[] SEGMENT_VALUES = {
            60_000L,        // 1 分钟
            180_000L,       // 3 分钟
            300_000L,       // 5 分钟（默认）
            600_000L,       // 10 分钟
            1_800_000L,     // 30 分钟
            3_600_000L,     // 60 分钟
            0L              // 不分割
    };
    private static final int DEFAULT_SEGMENT_INDEX = 2;

    /** 与 arrays.xml 中 orientation_labels 一一对应。 */
    private static final int[] ORIENTATION_VALUES = {
            RecorderConfig.ORIENTATION_LANDSCAPE,
            RecorderConfig.ORIENTATION_PORTRAIT,
            RecorderConfig.ORIENTATION_AUTO
    };

    /** 与 arrays.xml 中 feedback_labels 一一对应。 */
    private static final int[] FEEDBACK_VALUES = {
            RecorderConfig.FEEDBACK_NONE,
            RecorderConfig.FEEDBACK_SOUND,
            RecorderConfig.FEEDBACK_VIBRATE,
            RecorderConfig.FEEDBACK_SOUND_VIBRATE
    };

    private static final String PREF_WIDTH = RecorderPrefs.KEY_WIDTH;
    private static final String PREF_HEIGHT = RecorderPrefs.KEY_HEIGHT;
    private static final String PREF_FPS = RecorderPrefs.KEY_FPS;
    private static final String PREF_SEGMENT = RecorderPrefs.KEY_SEGMENT;
    private static final String PREF_ORIENTATION = RecorderPrefs.KEY_ORIENTATION;
    private static final String PREF_CAMERA = RecorderPrefs.KEY_CAMERA;
    private static final String PREF_FEEDBACK = RecorderPrefs.KEY_FEEDBACK;
    private static final String PREF_CONFIG_VERSION = RecorderPrefs.KEY_CONFIG_VERSION;
    private static final int CONFIG_VERSION = RecorderPrefs.CONFIG_VERSION;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            updateUi();
            mUi.postDelayed(this, 500);
        }
    };

    private final List<int[]> mSizes = new ArrayList<int[]>();
    private final List<Integer> mFpsChoices = new ArrayList<Integer>();
    private final List<CameraDevices.Device> mCameras = new ArrayList<CameraDevices.Device>();

    private TextView mStatus;
    private TextView mDetail;
    private TextView mDir;
    private Spinner mSpCamera;
    private Spinner mSpResolution;
    private Spinner mSpFps;
    private Spinner mSpOrientation;
    private Spinner mSpSegment;
    private Spinner mSpFeedback;
    private Button mBtnToggle;
    private SharedPreferences mPrefs;

    private int mLastLoadedCameraId = -1;
    private CameraCapabilities mCapabilities;
    private long mFpsSizeKey = -1;
    private String mShownError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        RecorderPrefs.migrateIfNeeded(this);
        mPrefs = RecorderPrefs.get(this);
        migratePreferences();
        mStatus = (TextView) findViewById(R.id.status);
        mDetail = (TextView) findViewById(R.id.detail);
        mDir = (TextView) findViewById(R.id.dir);
        mSpCamera = (Spinner) findViewById(R.id.sp_camera);
        mSpResolution = (Spinner) findViewById(R.id.sp_resolution);
        mSpFps = (Spinner) findViewById(R.id.sp_fps);
        mSpOrientation = (Spinner) findViewById(R.id.sp_orientation);
        mSpSegment = (Spinner) findViewById(R.id.sp_segment);
        mSpFeedback = (Spinner) findViewById(R.id.sp_feedback);
        mBtnToggle = (Button) findViewById(R.id.btn_toggle);

        ArrayAdapter<String> orientationAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.orientation_labels));
        orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpOrientation.setAdapter(orientationAdapter);
        mSpOrientation.setSelection(defaultOrientationIndex());

        ArrayAdapter<String> segmentAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.segment_labels));
        segmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpSegment.setAdapter(segmentAdapter);
        mSpSegment.setSelection(DEFAULT_SEGMENT_INDEX);

        ArrayAdapter<String> feedbackAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.feedback_labels));
        feedbackAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpFeedback.setAdapter(feedbackAdapter);
        mSpFeedback.setSelection(defaultFeedbackIndex());

        mBtnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggleClicked();
            }
        });

        mSpResolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= mSizes.size()) {
                    return;
                }
                int[] size = mSizes.get(position);
                if (sizeKey(size[0], size[1]) == mFpsSizeKey) {
                    return;
                }
                syncFpsChoices(selectedFps());
                persistCurrentConfig();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        mSpFps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                persistCurrentConfig();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        mSpOrientation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                persistCurrentConfig();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        mSpSegment.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                persistCurrentConfig();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        mSpFeedback.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                persistCurrentConfig();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        mDir.setText(getString(R.string.save_dir, outputDirPath()));
        initCameraSpinner();
        loadCapabilities();
        updateUi();
        startStandbyServiceIfIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUi.removeCallbacks(mTicker);
        mUi.post(mTicker);
    }

    @Override
    protected void onPause() {
        mUi.removeCallbacks(mTicker);
        super.onPause();
    }

    // ---------------------------------------------------------------------
    // 交互
    // ---------------------------------------------------------------------

    private void onToggleClicked() {
        RecorderEngine engine = RecorderEngine.get();
        if (engine.isBusy()) {
            stopRecording();
            return;
        }
        if (!hasPermissions()) {
            requestNeededPermissions();
            return;
        }

        RecorderConfig config = readConfig();
        saveConfig(config);

        Intent intent = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
        config.putTo(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
        disableToggleBriefly();
        mUi.post(mTicker);
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
        startService(intent);
        disableToggleBriefly();
    }

    private void disableToggleBriefly() {
        mBtnToggle.setEnabled(false);
        mUi.postDelayed(new Runnable() {
            @Override
            public void run() {
                mBtnToggle.setEnabled(true);
                updateUi();
            }
        }, 900);
    }

    // ---------------------------------------------------------------------
    // 摄像头列表
    // ---------------------------------------------------------------------

    private void initCameraSpinner() {
        mCameras.clear();
        mCameras.addAll(CameraDevices.query());

        List<String> labels = new ArrayList<String>();
        for (CameraDevices.Device device : mCameras) {
            labels.add(CameraDevices.label(device, mCameras.size()));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpCamera.setAdapter(adapter);

        int savedId = mPrefs.getInt(PREF_CAMERA, mCameras.get(0).id);
        int index = 0;
        for (int i = 0; i < mCameras.size(); i++) {
            if (mCameras.get(i).id == savedId) {
                index = i;
                break;
            }
        }
        mSpCamera.setSelection(index);
        mLastLoadedCameraId = mCameras.get(index).id;

        mSpCamera.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int cameraId = selectedCameraId();
                if (cameraId == mLastLoadedCameraId) {
                    return;
                }
                loadCapabilities(cameraId);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });
    }

    private int selectedCameraId() {
        int position = mSpCamera.getSelectedItemPosition();
        if (position >= 0 && position < mCameras.size()) {
            return mCameras.get(position).id;
        }
        return RecorderConfig.DEFAULT_CAMERA_ID;
    }

    // ---------------------------------------------------------------------
    // 相机能力与下拉框
    // ---------------------------------------------------------------------

    private void loadCapabilities() {
        loadCapabilities(selectedCameraId());
    }

    private void loadCapabilities(final int cameraId) {
        mLastLoadedCameraId = cameraId;
        CameraCapabilities cached = CameraCapabilities.getCached(cameraId);
        if (cached != null) {
            applyCapabilities(cached);
            return;
        }
        if (RecorderEngine.get().isBusy()) {
            applyCapabilities(CameraCapabilities.defaults());
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final CameraCapabilities capabilities = CameraCapabilities.query(cameraId);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && cameraId == selectedCameraId()) {
                            applyCapabilities(capabilities);
                        }
                    }
                });
            }
        }, "camera-capabilities").start();
    }

    private void applyCapabilities(CameraCapabilities capabilities) {
        mCapabilities = capabilities;
        mFpsSizeKey = -1;
        mSizes.clear();
        mSizes.addAll(capabilities.sizes);

        List<String> resolutionLabels = new ArrayList<String>();
        for (int[] size : capabilities.sizes) {
            resolutionLabels.add(size[0] + "×" + size[1]);
        }
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, resolutionLabels);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpResolution.setAdapter(resolutionAdapter);

        restoreSelection();
        // 让 Service 在硬件按键启动时能拿到和界面一致的最新参数
        persistCurrentConfig();
    }

    /** 保存当前界面参数，供硬件按键（Service）启动时使用。 */
    private void persistCurrentConfig() {
        if (mSizes.isEmpty() || mFpsChoices.isEmpty()) {
            return;
        }
        saveConfig(readConfig());
    }

    private void syncFpsChoices(int preferredFps) {
        int position = mSpResolution.getSelectedItemPosition();
        List<Integer> choices;
        long key = -1;
        if (mCapabilities != null && position >= 0 && position < mSizes.size()) {
            int[] size = mSizes.get(position);
            choices = mCapabilities.fpsChoicesFor(size[0], size[1]);
            key = sizeKey(size[0], size[1]);
        } else if (mCapabilities != null) {
            choices = mCapabilities.fpsChoices;
        } else {
            choices = new ArrayList<Integer>();
        }

        mFpsChoices.clear();
        mFpsChoices.addAll(choices);
        if (mFpsChoices.isEmpty()) {
            mFpsChoices.add(RecorderConfig.DEFAULT_FPS);
        }

        List<String> labels = new ArrayList<String>();
        for (Integer fps : mFpsChoices) {
            labels.add(getString(R.string.fps_value, fps));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpFps.setAdapter(adapter);

        int index = preferredFps > 0 ? mFpsChoices.indexOf(preferredFps) : -1;
        if (index < 0) {
            index = 0;
        }
        mSpFps.setSelection(index);
        mFpsSizeKey = key;
    }

    private int selectedFps() {
        int position = mSpFps.getSelectedItemPosition();
        if (position >= 0 && position < mFpsChoices.size()) {
            return mFpsChoices.get(position);
        }
        return 0;
    }

    private static long sizeKey(int width, int height) {
        return ((long) width << 32) | (height & 0xffffffffL);
    }

    private static int defaultOrientationIndex() {
        for (int i = 0; i < ORIENTATION_VALUES.length; i++) {
            if (ORIENTATION_VALUES[i] == RecorderConfig.DEFAULT_ORIENTATION) {
                return i;
            }
        }
        return 0;
    }

    private static int defaultFeedbackIndex() {
        for (int i = 0; i < FEEDBACK_VALUES.length; i++) {
            if (FEEDBACK_VALUES[i] == RecorderConfig.DEFAULT_FEEDBACK) {
                return i;
            }
        }
        return 1;
    }

    private void restoreSelection() {
        int width = mPrefs.getInt(PREF_WIDTH, -1);
        int height = mPrefs.getInt(PREF_HEIGHT, -1);
        int fps = mPrefs.getInt(PREF_FPS, -1);
        long segment = mPrefs.getLong(PREF_SEGMENT, -1L);

        // 分辨率：有保存值且仍被设备支持就恢复，否则默认选最高档（列表已按面积降序）
        int sizeIndex = -1;
        if (width > 0 && height > 0) {
            for (int i = 0; i < mSizes.size(); i++) {
                int[] size = mSizes.get(i);
                if (size[0] == width && size[1] == height) {
                    sizeIndex = i;
                    break;
                }
            }
        }
        if (sizeIndex < 0 && !mSizes.isEmpty()) {
            sizeIndex = 0;
        }
        if (sizeIndex >= 0) {
            mSpResolution.setSelection(sizeIndex);
        }

        // 帧率：按所选分辨率过滤后再选，默认该分辨率下的最高档
        syncFpsChoices(fps);

        int segmentIndex = -1;
        for (int i = 0; i < SEGMENT_VALUES.length; i++) {
            if (SEGMENT_VALUES[i] == segment) {
                segmentIndex = i;
                break;
            }
        }
        mSpSegment.setSelection(segmentIndex < 0 ? DEFAULT_SEGMENT_INDEX : segmentIndex);

        int orientation = mPrefs.getInt(PREF_ORIENTATION, RecorderConfig.DEFAULT_ORIENTATION);
        int orientationIndex = 0;
        for (int i = 0; i < ORIENTATION_VALUES.length; i++) {
            if (ORIENTATION_VALUES[i] == orientation) {
                orientationIndex = i;
                break;
            }
        }
        mSpOrientation.setSelection(orientationIndex);

        int feedback = mPrefs.getInt(PREF_FEEDBACK, RecorderConfig.DEFAULT_FEEDBACK);
        int feedbackIndex = 0;
        for (int i = 0; i < FEEDBACK_VALUES.length; i++) {
            if (FEEDBACK_VALUES[i] == feedback) {
                feedbackIndex = i;
                break;
            }
        }
        mSpFeedback.setSelection(feedbackIndex);
    }

    private RecorderConfig readConfig() {
        int sizeIndex = mSpResolution.getSelectedItemPosition();
        int fpsIndex = mSpFps.getSelectedItemPosition();
        int segmentIndex = mSpSegment.getSelectedItemPosition();

        int[] size;
        if (sizeIndex >= 0 && sizeIndex < mSizes.size()) {
            size = mSizes.get(sizeIndex);
        } else {
            size = new int[]{RecorderConfig.DEFAULT_WIDTH, RecorderConfig.DEFAULT_HEIGHT};
        }

        int fps;
        if (fpsIndex >= 0 && fpsIndex < mFpsChoices.size()) {
            fps = mFpsChoices.get(fpsIndex);
        } else {
            fps = RecorderConfig.DEFAULT_FPS;
        }

        long segmentMs;
        if (segmentIndex >= 0 && segmentIndex < SEGMENT_VALUES.length) {
            segmentMs = SEGMENT_VALUES[segmentIndex];
        } else {
            segmentMs = RecorderConfig.DEFAULT_SEGMENT_MS;
        }

        int orientationIndex = mSpOrientation.getSelectedItemPosition();
        int orientation = RecorderConfig.DEFAULT_ORIENTATION;
        if (orientationIndex >= 0 && orientationIndex < ORIENTATION_VALUES.length) {
            orientation = ORIENTATION_VALUES[orientationIndex];
        }

        int feedbackIndex = mSpFeedback.getSelectedItemPosition();
        int feedback = RecorderConfig.DEFAULT_FEEDBACK;
        if (feedbackIndex >= 0 && feedbackIndex < FEEDBACK_VALUES.length) {
            feedback = FEEDBACK_VALUES[feedbackIndex];
        }

        return new RecorderConfig(size[0], size[1], fps, segmentMs, orientation,
                selectedCameraId(), feedback);
    }

    private void saveConfig(RecorderConfig config) {
        RecorderPrefs.save(this, config);
    }

    /** 打开 APP 后让服务进入待机，确保蓝牙播放/暂停键随时能开始录制。 */
    private void startStandbyServiceIfIdle() {
        if (RecorderEngine.get().isBusy()) {
            return;
        }
        Intent intent = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_STANDBY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /**
     * 配置版本升级：一次性清掉旧版本保存的选择，让新的默认值
     * （最高分辨率 / 最高帧率 / 5 分钟分段）生效；之后仍按用户选择持久化。
     */
    private void migratePreferences() {
        int version = mPrefs.getInt(PREF_CONFIG_VERSION, 1);
        if (version >= CONFIG_VERSION) {
            return;
        }
        mPrefs.edit()
                .remove(PREF_WIDTH)
                .remove(PREF_HEIGHT)
                .remove(PREF_FPS)
                .remove(PREF_SEGMENT)
                .putInt(PREF_CONFIG_VERSION, CONFIG_VERSION)
                .apply();
    }

    // ---------------------------------------------------------------------
    // 界面刷新
    // ---------------------------------------------------------------------

    private void updateUi() {
        RecorderEngine engine = RecorderEngine.get();
        boolean busy = engine.isBusy();
        int state = engine.getState();

        if (state == RecorderEngine.STATE_ERROR) {
            mStatus.setText(R.string.status_error);
            String error = engine.getLastError();
            mDetail.setText(error == null ? "" : error);
            if (error != null && !error.equals(mShownError)) {
                mShownError = error;
                Toast.makeText(this, getString(R.string.toast_error, error), Toast.LENGTH_LONG).show();
            }
        } else if (busy) {
            mStatus.setText(getString(R.string.status_recording, formatDuration(engine.getTotalElapsedMs())));
            String path = engine.getCurrentFile();
            if (path == null) {
                mDetail.setText(R.string.detail_starting);
            } else {
                mDetail.setText(getString(R.string.detail_file, new File(path).getName()));
            }
        } else {
            mStatus.setText(R.string.status_idle);
            mDetail.setText(R.string.detail_idle);
            if (state == RecorderEngine.STATE_IDLE) {
                mShownError = null;
            }
        }

        mBtnToggle.setText(busy ? R.string.btn_stop : R.string.btn_start);
        mSpCamera.setEnabled(!busy);
        mSpResolution.setEnabled(!busy);
        mSpFps.setEnabled(!busy);
        mSpOrientation.setEnabled(!busy);
        mSpSegment.setEnabled(!busy);
        mSpFeedback.setEnabled(!busy);
        mDir.setText(getString(R.string.save_dir, outputDirPath()));
    }

    private static String outputDirPath() {
        File base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Recorder");
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        return new File(base, date).getAbsolutePath();
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    // ---------------------------------------------------------------------
    // 权限
    // ---------------------------------------------------------------------

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String permission : PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            loadCapabilities();
            onToggleClicked();
        } else {
            Toast.makeText(this, R.string.toast_permission, Toast.LENGTH_LONG).show();
        }
    }
}
