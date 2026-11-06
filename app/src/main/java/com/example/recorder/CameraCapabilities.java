package com.example.recorder;

import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;
import android.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通过 Camera1 查询设备支持的分辨率与帧率，供界面下拉框使用。
 * <p>
 * 分辨率只取「录像能用」的尺寸：优先 CamcorderProfile（与 media_profiles.xml 一致，
 * MediaRecorder 可以直接套用），并要求相机预览也支持该尺寸；
 * 帧率则按所选分辨率再过滤一遍（相机区间 ∩ 编码器该尺寸支持的帧率）。
 * 查询结果按摄像头缓存，避免反复打开相机。
 */
public final class CameraCapabilities {

    private static final String TAG = "CameraCapabilities";

    private static final Map<Integer, CameraCapabilities> sCache = new HashMap<Integer, CameraCapabilities>();

    /** 常见档位，用于枚举 CamcorderProfile。 */
    private static final int[] PROFILE_QUALITIES = {
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P,
            CamcorderProfile.QUALITY_CIF,
            CamcorderProfile.QUALITY_QVGA,
            CamcorderProfile.QUALITY_QCIF,
            CamcorderProfile.QUALITY_HIGH,
            CamcorderProfile.QUALITY_LOW
    };

    /** 每个元素为 {宽, 高}，宽 >= 高。 */
    public final List<int[]> sizes;
    /** 相机上报的候选帧率（已按区间上限 + 常用帧率整理）。 */
    public final List<Integer> fpsChoices;
    /** 每个尺寸对应的编码器帧率区间 [min, max]（fps）。 */
    private final Map<Long, float[]> mFpsRangeBySize;

    private CameraCapabilities(List<int[]> sizes, List<Integer> fpsChoices, Map<Long, float[]> fpsRangeBySize) {
        this.sizes = Collections.unmodifiableList(sizes);
        this.fpsChoices = Collections.unmodifiableList(fpsChoices);
        this.mFpsRangeBySize = fpsRangeBySize;
    }

    public static synchronized CameraCapabilities getCached(int cameraId) {
        return sCache.get(cameraId);
    }

    public static synchronized CameraCapabilities query(int cameraId) {
        CameraCapabilities cached = sCache.get(cameraId);
        if (cached != null) {
            return cached;
        }

        List<int[]> sizes = new ArrayList<int[]>();
        List<Integer> fps = new ArrayList<Integer>();
        Map<Long, float[]> fpsRangeBySize = new HashMap<Long, float[]>();
        Camera camera = null;
        boolean opened = false;
        try {
            camera = Camera.open(cameraId);
            if (camera != null) {
                opened = true;
                Camera.Parameters params = camera.getParameters();
                List<int[]> previewSizes = new ArrayList<int[]>();
                appendSizes(previewSizes, params.getSupportedPreviewSizes());
                List<int[]> videoSizes = new ArrayList<int[]>();
                appendSizes(videoSizes, params.getSupportedVideoSizes());

                // 只保留真正可录制的尺寸：
                // 1) CamcorderProfile 中有的（MediaRecorder 可直接套用 profile）
                // 2) 相机声明给 MediaRecorder 的视频尺寸，且相机预览也支持
                List<int[]> candidates = new ArrayList<int[]>(recordableSizes(cameraId, previewSizes));
                for (int[] videoSize : videoSizes) {
                    if (contains(previewSizes, videoSize[0], videoSize[1])
                            && !contains(candidates, videoSize[0], videoSize[1])) {
                        candidates.add(videoSize);
                    }
                }
                sizes = candidates;
                if (sizes.isEmpty()) {
                    // 设备既没有 profile 也没有视频尺寸时，退回预览尺寸
                    sizes = previewSizes;
                }
                if (sizes.isEmpty()) {
                    sizes = defaultSizeList();
                }
                Log.i(TAG, "camera " + cameraId + " previews=" + formatSizes(previewSizes)
                        + " videos=" + formatSizes(videoSizes)
                        + " recordable=" + formatSizes(sizes));

                List<int[]> ranges = params.getSupportedPreviewFpsRange();
                if (ranges != null) {
                    StringBuilder builder = new StringBuilder();
                    Set<Integer> set = new LinkedHashSet<Integer>();
                    int widestMin = Integer.MAX_VALUE;
                    int widestMax = 0;
                    for (int[] range : ranges) {
                        builder.append('[').append(range[0]).append(',').append(range[1]).append("] ");
                        int max = range[1] / 1000;
                        if (max >= 10 && max <= 60) {
                            set.add(max);
                        }
                        if (range[1] > widestMax) {
                            widestMax = range[1];
                            widestMin = range[0];
                        } else if (range[1] == widestMax && range[0] < widestMin) {
                            widestMin = range[0];
                        }
                    }
                    // 很多 5.x 设备只上报一个自适应区间（如 7.5~30fps），
                    // 这里补充区间内的常用帧率，录制时通过 setVideoFrameRate 限制编码帧率。
                    if (widestMax > 0) {
                        int low = Math.max(10, (int) Math.ceil(widestMin / 1000.0));
                        int high = Math.min(60, widestMax / 1000);
                        for (int candidate : new int[]{30, 24, 15}) {
                            if (candidate >= low && candidate <= high) {
                                set.add(candidate);
                            }
                        }
                    }
                    Log.i(TAG, "camera " + cameraId + " supportedPreviewFpsRanges=" + builder);
                    fps.addAll(set);
                }

                MediaCodecInfo.CodecCapabilities avcCaps = findAvcEncoder();
                if (avcCaps != null) {
                    try {
                        MediaCodecInfo.VideoCapabilities videoCaps = avcCaps.getVideoCapabilities();
                        for (int[] size : sizes) {
                            try {
                                if (!videoCaps.isSizeSupported(size[0], size[1])) {
                                    continue;
                                }
                                Range<Double> rate = videoCaps.getSupportedFrameRatesFor(size[0], size[1]);
                                if (rate != null) {
                                    fpsRangeBySize.put(sizeKey(size[0], size[1]),
                                            new float[]{rate.getLower().floatValue(), rate.getUpper().floatValue()});
                                }
                            } catch (Throwable ignored) {
                                // 个别机型在查询某些尺寸时会抛异常，忽略即可
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "查询编码器帧率能力失败", t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "查询相机能力失败", t);
        } finally {
            if (camera != null) {
                try {
                    camera.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }

        if (sizes.isEmpty()) {
            sizes = defaultSizeList();
        }
        if (fps.isEmpty()) {
            fps = defaultFpsList();
        }
        sortSizes(sizes);
        Collections.sort(fps, Collections.reverseOrder());

        CameraCapabilities result = new CameraCapabilities(sizes, fps, fpsRangeBySize);
        if (opened) {
            sCache.put(cameraId, result);
        }
        return result;
    }

    public static CameraCapabilities defaults() {
        List<int[]> sizes = defaultSizeList();
        sortSizes(sizes);
        List<Integer> fps = defaultFpsList();
        Collections.sort(fps, Collections.reverseOrder());
        return new CameraCapabilities(sizes, fps, new HashMap<Long, float[]>());
    }

    /**
     * 返回指定分辨率下可用的帧率（相机区间 ∩ 编码器该尺寸支持的范围）。
     * 查不到编码器信息时退回相机候选列表。
     */
    public List<Integer> fpsChoicesFor(int width, int height) {
        float[] range = mFpsRangeBySize.get(sizeKey(width, height));
        if (range == null) {
            return new ArrayList<Integer>(fpsChoices);
        }
        List<Integer> result = new ArrayList<Integer>();
        for (Integer value : fpsChoices) {
            if (value + 0.5f >= range[0] && value - 0.5f <= range[1]) {
                result.add(value);
            }
        }
        if (result.isEmpty()) {
            int fallback = Math.max(1, Math.min(60, Math.round(range[1])));
            result.add(fallback);
        }
        return result;
    }

    /** 通过 CamcorderProfile 得到真正可录制的尺寸，并要求相机预览支持该尺寸。 */
    private static List<int[]> recordableSizes(int cameraId, List<int[]> previewSizes) {        List<int[]> result = new ArrayList<int[]>();
        for (int quality : PROFILE_QUALITIES) {
            try {
                if (!CamcorderProfile.hasProfile(cameraId, quality)) {
                    continue;
                }
                CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                if (profile == null) {
                    continue;
                }
                int w = Math.max(profile.videoFrameWidth, profile.videoFrameHeight);
                int h = Math.min(profile.videoFrameWidth, profile.videoFrameHeight);
                if (w <= 0 || h <= 0) {
                    continue;
                }
                if (!previewSizes.isEmpty() && !contains(previewSizes, w, h)) {
                    // 相机预览不支持时，MediaRecorder 也无法按该尺寸录制
                    continue;
                }
                if (!contains(result, w, h)) {
                    result.add(new int[]{w, h});
                }
            } catch (Throwable ignored) {
                // 个别档位在某些机型上会抛异常，跳过即可
            }
        }
        return result;
    }

    private static MediaCodecInfo.CodecCapabilities findAvcEncoder() {
        try {
            int count = MediaCodecList.getCodecCount();
            for (int i = 0; i < count; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info == null || !info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        return info.getCapabilitiesForType(type);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "枚举视频编码器失败", t);
        }
        return null;
    }

    private static long sizeKey(int width, int height) {
        return ((long) width << 32) | (height & 0xffffffffL);
    }

    private static boolean contains(List<int[]> list, int width, int height) {
        for (int[] size : list) {
            if (size[0] == width && size[1] == height) {
                return true;
            }
        }
        return false;
    }

    private static String formatSizes(List<int[]> list) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(list.get(i)[0]).append('x').append(list.get(i)[1]);
        }
        return builder.append(']').toString();
    }

    private static void appendSizes(List<int[]> out, List<Camera.Size> list) {
        if (list == null) {
            return;
        }
        for (Camera.Size size : list) {
            if (size == null) {
                continue;
            }
            int w = Math.max(size.width, size.height);
            int h = Math.min(size.width, size.height);
            if (w <= 0 || h <= 0) {
                continue;
            }
            if (!contains(out, w, h)) {
                out.add(new int[]{w, h});
            }
        }
    }

    private static void sortSizes(List<int[]> sizes) {
        Collections.sort(sizes, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                long areaA = (long) a[0] * a[1];
                long areaB = (long) b[0] * b[1];
                if (areaA != areaB) {
                    return areaA > areaB ? -1 : 1;
                }
                return b[0] - a[0];
            }
        });
    }

    private static List<int[]> defaultSizeList() {
        List<int[]> list = new ArrayList<int[]>();
        list.add(new int[]{1920, 1080});
        list.add(new int[]{1280, 720});
        list.add(new int[]{854, 480});
        list.add(new int[]{640, 480});
        list.add(new int[]{320, 240});
        return list;
    }

    private static List<Integer> defaultFpsList() {
        List<Integer> list = new ArrayList<Integer>();
        list.add(30);
        list.add(24);
        list.add(15);
        return list;
    }
}
