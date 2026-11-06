package com.example.recorder;

import android.hardware.Camera;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通过 Camera1 查询设备支持的分辨率与帧率，供界面下拉框使用。
 * 分辨率完全按设备上报的能力展示，不做固定也不做过分类的过滤。
 * 查询结果会缓存，避免反复打开相机。
 */
public final class CameraCapabilities {

    private static final String TAG = "CameraCapabilities";

    private static volatile CameraCapabilities sCached;

    /** 每个元素为 {宽, 高}，宽 >= 高。 */
    public final List<int[]> sizes;
    /** 可选帧率（设备上报区间的最大帧率 + 区间内的常用帧率）。 */
    public final List<Integer> fpsChoices;

    private CameraCapabilities(List<int[]> sizes, List<Integer> fpsChoices) {
        this.sizes = Collections.unmodifiableList(sizes);
        this.fpsChoices = Collections.unmodifiableList(fpsChoices);
    }

    public static CameraCapabilities getCached() {
        return sCached;
    }

    public static synchronized CameraCapabilities query() {
        CameraCapabilities cached = sCached;
        if (cached != null) {
            return cached;
        }

        List<int[]> sizes = new ArrayList<int[]>();
        List<Integer> fps = new ArrayList<Integer>();
        Camera camera = null;
        boolean opened = false;
        try {
            camera = Camera.open(0);
            if (camera != null) {
                opened = true;
                Camera.Parameters params = camera.getParameters();
                List<int[]> supported = new ArrayList<int[]>();
                appendSizes(supported, params.getSupportedVideoSizes());
                appendSizes(supported, params.getSupportedPreviewSizes());
                // 设备支持什么就展示什么，不做固定档位、不做过滤
                sizes = supported;

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
                    Log.i(TAG, "supportedPreviewFpsRanges=" + builder);
                    fps.addAll(set);
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

        CameraCapabilities result = new CameraCapabilities(sizes, fps);
        if (opened) {
            sCached = result;
        }
        return result;
    }

    public static CameraCapabilities defaults() {
        List<int[]> sizes = defaultSizeList();
        sortSizes(sizes);
        List<Integer> fps = defaultFpsList();
        Collections.sort(fps, Collections.reverseOrder());
        return new CameraCapabilities(sizes, fps);
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
            boolean duplicate = false;
            for (int[] existing : out) {
                if (existing[0] == w && existing[1] == h) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
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
