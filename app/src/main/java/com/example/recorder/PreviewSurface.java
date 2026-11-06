package com.example.recorder;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

/**
 * 为 MediaRecorder 提供一个“离屏”的相机预览表面，使录制不依赖 Activity 窗口。
 * <p>
 * 首选方案：在 WindowManager 中添加一个 1x1 的 SurfaceView（TYPE_SYSTEM_OVERLAY）。
 * 这是 Android 5.x 上后台/熄屏录制 MediaRecorder 的成熟做法，SurfaceFlinger 会持续消费
 * 预览帧，相机管线不会因为没人消费而卡死。
 * <p>
 * 备选方案：如果悬浮窗无法添加（例如 6.0+ 未授予“显示在其他应用上层”权限），
 * 退化为一块 {@link SurfaceTexture}。这种方案在部分机型上可用，但相机预览帧无人消费，
 * 个别设备可能出现录像卡住。
 */
final class PreviewSurface implements SurfaceHolder.Callback {

    interface Callback {
        void onSurfaceReady(Surface surface);

        void onSurfaceLost();
    }

    private static final String TAG = "PreviewSurface";

    private final Context mContext;
    private final Callback mCallback;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private SurfaceView mView;
    private WindowManager mWindowManager;
    private SurfaceTexture mTexture;
    private Surface mTextureSurface;
    private boolean mReleased;
    private boolean mSurfaceReady;
    private boolean mUsingTexture;

    PreviewSurface(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    boolean obtain() {
        if (tryOverlay()) {
            return true;
        }
        return fallbackTexture();
    }

    private boolean tryOverlay() {
        if (!canAddOverlay()) {
            Log.w(TAG, "没有悬浮窗权限，使用 SurfaceTexture 备用方案");
            return false;
        }
        try {
            SurfaceView view = new SurfaceView(mContext);
            WindowManager windowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return false;
            }
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    2, 2,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.START | Gravity.TOP;
            params.x = 0;
            params.y = 0;
            view.getHolder().addCallback(this);
            windowManager.addView(view, params);
            mView = view;
            mWindowManager = windowManager;
            // 极少数机型添加悬浮窗后不会回调 surfaceCreated，超时后退回备用方案
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mReleased && !mSurfaceReady && !mUsingTexture) {
                        Log.w(TAG, "悬浮预览窗口未产生 Surface，改用 SurfaceTexture");
                        removeOverlay();
                        fallbackTexture();
                    }
                }
            }, 2500);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "添加悬浮预览窗口失败，使用 SurfaceTexture 备用方案", t);
            return false;
        }
    }

    private boolean canAddOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(mContext);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean fallbackTexture() {
        if (mReleased) {
            return false;
        }
        try {
            mUsingTexture = true;
            SurfaceTexture texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(1280, 720);
            Surface surface = new Surface(texture);
            mTexture = texture;
            mTextureSurface = surface;
            mSurfaceReady = true;
            mCallback.onSurfaceReady(surface);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "创建备用预览表面失败", t);
            return false;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mReleased) {
            return;
        }
        mSurfaceReady = true;
        mCallback.onSurfaceReady(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // no-op
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!mReleased) {
            mCallback.onSurfaceLost();
        }
    }

    void release() {
        mReleased = true;
        mMain.removeCallbacksAndMessages(null);
        removeOverlay();
        if (mTextureSurface != null) {
            try {
                mTextureSurface.release();
            } catch (Throwable ignored) {
                // ignore
            }
            mTextureSurface = null;
        }
        if (mTexture != null) {
            try {
                mTexture.release();
            } catch (Throwable ignored) {
                // ignore
            }
            mTexture = null;
        }
    }

    private void removeOverlay() {
        if (mView != null) {
            try {
                mView.getHolder().removeCallback(this);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                if (mWindowManager != null) {
                    mWindowManager.removeView(mView);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            mView = null;
            mWindowManager = null;
        }
    }
}
