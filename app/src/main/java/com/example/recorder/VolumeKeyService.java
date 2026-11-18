package com.example.recorder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：拦截音量键（加/减），短按切换录制状态。
 * <p>
 * 音量键被系统保留用于调音量，普通前台服务 / Activity 在后台时都收不到，
 * 因此用无障碍服务的按键过滤能力（FLAG_REQUEST_FILTER_KEY_EVENTS）来接收，
 * 熄屏 / 后台同样有效。
 * <p>
 * 需要在「系统设置 → 辅助功能」中手动开启。本服务只负责转发，不直接操作相机，
 * 通过 {@link RecorderService#ACTION_TOGGLE} 复用现有的去抖、参数读取、通知与待机逻辑。
 */
public final class VolumeKeyService extends AccessibilityService {

    private static final String TAG = "VolumeKeyService";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // 部分 ROM 不会按 XML 配置生效，这里兜底再声明一次按键过滤能力
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        Log.i(TAG, "音量键录制服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只关心按键事件，不处理窗口事件
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event != null && isVolumeKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                toggleRecording();
            }
            // 始终消费音量键，避免改变系统音量
            return true;
        }
        return super.onKeyEvent(event);
    }

    private static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private void toggleRecording() {
        Intent intent = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
