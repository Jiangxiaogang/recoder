package com.example.recorder;

import android.content.Context;
import android.hardware.Camera;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备摄像头列表（Camera1）。只读取 CameraInfo，不会打开相机。
 */
public final class CameraDevices {

    private static final String TAG = "CameraDevices";

    public static final class Device {
        public final int id;
        public final int facing;

        Device(int id, int facing) {
            this.id = id;
            this.facing = facing;
        }
    }

    private CameraDevices() {
    }

    public static List<Device> query() {
        List<Device> devices = new ArrayList<Device>();
        try {
            int count = Camera.getNumberOfCameras();
            for (int i = 0; i < count; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                devices.add(new Device(i, info.facing));
            }
        } catch (Throwable t) {
            Log.w(TAG, "查询摄像头列表失败", t);
        }
        if (devices.isEmpty()) {
            devices.add(new Device(0, Camera.CameraInfo.CAMERA_FACING_BACK));
        }
        return devices;
    }

    public static String label(Context context, Device device, int total) {
        String base;
        switch (device.facing) {
            case Camera.CameraInfo.CAMERA_FACING_FRONT:
                base = context.getString(R.string.camera_facing_front);
                break;
            case Camera.CameraInfo.CAMERA_FACING_BACK:
                base = context.getString(R.string.camera_facing_back);
                break;
            default:
                base = context.getString(R.string.camera_facing_external);
                break;
        }
        return total > 1 ? context.getString(R.string.camera_label_with_id, base, device.id) : base;
    }
}
