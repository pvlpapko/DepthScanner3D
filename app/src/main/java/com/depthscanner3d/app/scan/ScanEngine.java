package com.depthscanner3d.app.scan;

import android.media.Image;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScanEngine {
    public static final int FLOATS_PER_RENDER_POINT = 6;

    private static final float CAPTURE_VOXEL_SIZE_METERS = 0.0125f;
    private static final float MIN_CONFIDENCE = 0.55f;
    private static final float MIN_DEPTH_METERS = 0.18f;
    private static final float MAX_DEPTH_METERS = 5.0f;
    private static final int MAX_STORED_POINTS = 350_000;
    private static final int MAX_SAMPLES_PER_FRAME = 18_000;
    private static final int MAX_RENDER_POINTS = 140_000;
    private static final long MIN_PROCESS_INTERVAL_NS = 90_000_000L;

    private static final int KEY_BITS = 21;
    private static final long KEY_MASK = (1L << KEY_BITS) - 1L;
    private static final int KEY_BIAS = 1 << (KEY_BITS - 1);

    private final Object lock = new Object();
    private final LinkedHashMap<Long, Accumulator> voxelPoints = new LinkedHashMap<>();

    private volatile boolean scanning;
    private Anchor scanAnchor;
    private long startedAtEpochMillis;
    private long lastDepthTimestamp = Long.MIN_VALUE;
    private long lastProcessedFrameTimestamp = Long.MIN_VALUE;
    private int integratedDepthFrames;
    private long renderVersion;

    public void begin(Anchor anchor) {
        if (anchor == null) {
            throw new IllegalArgumentException("Scan anchor must not be null");
        }
        synchronized (lock) {
            clearLocked();
            scanAnchor = anchor;
            startedAtEpochMillis = System.currentTimeMillis();
            scanning = true;
        }
    }

    public void stop() {
        scanning = false;
    }

    public void clear() {
        synchronized (lock) {
            clearLocked();
        }
    }

    private void clearLocked() {
        scanning = false;
        scanAnchor = null;
        voxelPoints.clear();
        integratedDepthFrames = 0;
        lastDepthTimestamp = Long.MIN_VALUE;
        lastProcessedFrameTimestamp = Long.MIN_VALUE;
        startedAtEpochMillis = 0L;
        renderVersion++;
    }

    public boolean isScanning() {
        return scanning;
    }

    public int getPointCount() {
        synchronized (lock) {
            return voxelPoints.size();
        }
    }

    public int getIntegratedDepthFrames() {
        synchronized (lock) {
            return integratedDepthFrames;
        }
    }

    public long getRenderVersion() {
        synchronized (lock) {
            return renderVersion;
        }
    }

    public Anchor getScanAnchor() {
        synchronized (lock) {
            return scanAnchor;
        }
    }

    public String getSettingsSummary() {
        return String.format(Locale.US,
                "voxel=%.4fm confidence>=%.2f depth=%.2f..%.1fm",
                CAPTURE_VOXEL_SIZE_METERS, MIN_CONFIDENCE,
                MIN_DEPTH_METERS, MAX_DEPTH_METERS);
    }

    public int processFrame(Frame frame, Camera camera) {
        if (!scanning || camera.getTrackingState() != TrackingState.TRACKING) {
            return 0;
        }
        Anchor anchor;
        synchronized (lock) {
            anchor = scanAnchor;
        }
        if (anchor == null || anchor.getTrackingState() != TrackingState.TRACKING) {
            return 0;
        }

        long frameTimestamp = frame.getTimestamp();
        if (lastProcessedFrameTimestamp != Long.MIN_VALUE
                && frameTimestamp - lastProcessedFrameTimestamp < MIN_PROCESS_INTERVAL_NS) {
            return 0;
        }

        try (Image depth = frame.acquireRawDepthImage16Bits();
             Image confidence = frame.acquireRawDepthConfidenceImage()) {
            if (depth.getTimestamp() == lastDepthTimestamp) {
                return 0;
            }
            lastDepthTimestamp = depth.getTimestamp();
            lastProcessedFrameTimestamp = frameTimestamp;

            Image cameraImage = null;
            try {
                cameraImage = frame.acquireCameraImage();
            } catch (NotYetAvailableException ignored) {
                // Geometry is still usable; points receive a neutral color.
            }

            try {
                int added = integrateDepthFrame(frame, camera, anchor, depth, confidence, cameraImage);
                synchronized (lock) {
                    integratedDepthFrames++;
                    if (added > 0) {
                        renderVersion++;
                    }
                }
                return added;
            } finally {
                if (cameraImage != null) {
                    cameraImage.close();
                }
            }
        } catch (NotYetAvailableException ignored) {
            return 0;
        }
    }

    private int integrateDepthFrame(
            Frame frame,
            Camera camera,
            Anchor anchor,
            Image depth,
            Image confidence,
            Image cameraImage
    ) {
        Image.Plane depthPlane = depth.getPlanes()[0];
        Image.Plane confidencePlane = confidence.getPlanes()[0];
        ByteBuffer depthBuffer = depthPlane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer confidenceBuffer = confidencePlane.getBuffer().duplicate();

        int depthWidth = depth.getWidth();
        int depthHeight = depth.getHeight();
        int step = Math.max(1, (int) Math.ceil(
                Math.sqrt((double) depthWidth * depthHeight / MAX_SAMPLES_PER_FRAME)));

        CameraIntrinsics intrinsics = camera.getTextureIntrinsics();
        int[] dimensions = intrinsics.getImageDimensions();
        float[] focal = intrinsics.getFocalLength();
        float[] principal = intrinsics.getPrincipalPoint();
        float fx = focal[0] * depthWidth / dimensions[0];
        float fy = focal[1] * depthHeight / dimensions[1];
        float cx = principal[0] * depthWidth / dimensions[0];
        float cy = principal[1] * depthHeight / dimensions[1];

        int estimatedCapacity = Math.max(1, (depthWidth / step + 1) * (depthHeight / step + 1));
        float[] localPositions = new float[estimatedCapacity * 3];
        float[] textureCoordinates = new float[estimatedCapacity * 2];
        float[] confidences = new float[estimatedCapacity];
        int candidateCount = 0;

        Pose cameraToAnchor = anchor.getPose().inverse().compose(camera.getPose());
        float[] cameraPoint = new float[3];
        float[] localPoint = new float[3];

        for (int y = 0; y < depthHeight; y += step) {
            for (int x = 0; x < depthWidth; x += step) {
                int depthOffset = y * depthPlane.getRowStride() + x * depthPlane.getPixelStride();
                if (depthOffset < 0 || depthOffset + 1 >= depthBuffer.limit()) {
                    continue;
                }
                int depthMillimeters = depthBuffer.getShort(depthOffset) & 0xFFFF;
                if (depthMillimeters == 0) {
                    continue;
                }
                float depthMeters = depthMillimeters / 1000.0f;
                if (depthMeters < MIN_DEPTH_METERS || depthMeters > MAX_DEPTH_METERS) {
                    continue;
                }

                int confidenceOffset = y * confidencePlane.getRowStride()
                        + x * confidencePlane.getPixelStride();
                if (confidenceOffset < 0 || confidenceOffset >= confidenceBuffer.limit()) {
                    continue;
                }
                float confidenceValue = (confidenceBuffer.get(confidenceOffset) & 0xFF) / 255.0f;
                if (confidenceValue < MIN_CONFIDENCE) {
                    continue;
                }

                cameraPoint[0] = depthMeters * (x - cx) / fx;
                cameraPoint[1] = depthMeters * (cy - y) / fy;
                cameraPoint[2] = -depthMeters;
                cameraToAnchor.transformPoint(cameraPoint, 0, localPoint, 0);

                if (!isFinite(localPoint[0]) || !isFinite(localPoint[1]) || !isFinite(localPoint[2])) {
                    continue;
                }
                if (Math.abs(localPoint[0]) > 12.0f
                        || Math.abs(localPoint[1]) > 12.0f
                        || Math.abs(localPoint[2]) > 12.0f) {
                    continue;
                }

                int positionIndex = candidateCount * 3;
                localPositions[positionIndex] = localPoint[0];
                localPositions[positionIndex + 1] = localPoint[1];
                localPositions[positionIndex + 2] = localPoint[2];

                int textureIndex = candidateCount * 2;
                textureCoordinates[textureIndex] = (x + 0.5f) / depthWidth;
                textureCoordinates[textureIndex + 1] = (y + 0.5f) / depthHeight;
                confidences[candidateCount] = confidenceValue;
                candidateCount++;
            }
        }

        if (candidateCount == 0) {
            return 0;
        }

        int[] colors = new int[candidateCount];
        if (cameraImage != null) {
            float[] compactTextureCoordinates = new float[candidateCount * 2];
            System.arraycopy(textureCoordinates, 0,
                    compactTextureCoordinates, 0, compactTextureCoordinates.length);
            float[] imageCoordinates = new float[candidateCount * 2];
            frame.transformCoordinates2d(
                    Coordinates2d.TEXTURE_NORMALIZED,
                    compactTextureCoordinates,
                    Coordinates2d.IMAGE_PIXELS,
                    imageCoordinates
            );
            for (int i = 0; i < candidateCount; i++) {
                int imageX = Math.round(imageCoordinates[i * 2]);
                int imageY = Math.round(imageCoordinates[i * 2 + 1]);
                colors[i] = sampleYuv(cameraImage, imageX, imageY,
                        fallbackColor(confidences[i]));
            }
        } else {
            for (int i = 0; i < candidateCount; i++) {
                colors[i] = fallbackColor(confidences[i]);
            }
        }

        int added = 0;
        synchronized (lock) {
            for (int i = 0; i < candidateCount; i++) {
                int p = i * 3;
                float x = localPositions[p];
                float y = localPositions[p + 1];
                float z = localPositions[p + 2];
                long key = packVoxel(x, y, z, CAPTURE_VOXEL_SIZE_METERS);
                Accumulator accumulator = voxelPoints.get(key);
                if (accumulator == null) {
                    if (voxelPoints.size() >= MAX_STORED_POINTS) {
                        continue;
                    }
                    accumulator = new Accumulator();
                    voxelPoints.put(key, accumulator);
                    added++;
                }
                int color = colors[i];
                accumulator.add(
                        x, y, z,
                        (color >> 16) & 0xFF,
                        (color >> 8) & 0xFF,
                        color & 0xFF
                );
            }
        }
        return added;
    }

    public FloatBuffer createRenderBuffer() {
        List<PointSample> points = snapshotPoints(MAX_RENDER_POINTS);
        FloatBuffer buffer = ByteBuffer.allocateDirect(
                        points.size() * FLOATS_PER_RENDER_POINT * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (PointSample point : points) {
            buffer.put(point.x());
            buffer.put(point.y());
            buffer.put(point.z());
            buffer.put(point.red() / 255.0f);
            buffer.put(point.green() / 255.0f);
            buffer.put(point.blue() / 255.0f);
        }
        buffer.flip();
        return buffer;
    }

    public ScanSnapshot createExportSnapshot() {
        synchronized (lock) {
            return new ScanSnapshot(
                    snapshotPointsLocked(Integer.MAX_VALUE),
                    integratedDepthFrames,
                    startedAtEpochMillis,
                    CAPTURE_VOXEL_SIZE_METERS,
                    MIN_CONFIDENCE,
                    MIN_DEPTH_METERS,
                    MAX_DEPTH_METERS
            );
        }
    }

    private List<PointSample> snapshotPoints(int maxPoints) {
        synchronized (lock) {
            return snapshotPointsLocked(maxPoints);
        }
    }

    private List<PointSample> snapshotPointsLocked(int maxPoints) {
        int total = voxelPoints.size();
        int stride = Math.max(1, (int) Math.ceil(total / (double) Math.max(1, maxPoints)));
        List<PointSample> result = new ArrayList<>(Math.min(total, maxPoints));
        int index = 0;
        for (Map.Entry<Long, Accumulator> entry : voxelPoints.entrySet()) {
            if (index % stride == 0) {
                result.add(entry.getValue().toPoint());
            }
            index++;
        }
        return result;
    }

    private static long packVoxel(float x, float y, float z, float voxelSize) {
        int ix = clampVoxelIndex((int) Math.floor(x / voxelSize));
        int iy = clampVoxelIndex((int) Math.floor(y / voxelSize));
        int iz = clampVoxelIndex((int) Math.floor(z / voxelSize));
        long bx = (ix + KEY_BIAS) & KEY_MASK;
        long by = (iy + KEY_BIAS) & KEY_MASK;
        long bz = (iz + KEY_BIAS) & KEY_MASK;
        return (bx << (KEY_BITS * 2)) | (by << KEY_BITS) | bz;
    }

    private static int clampVoxelIndex(int value) {
        int min = -KEY_BIAS;
        int max = KEY_BIAS - 1;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static int fallbackColor(float confidence) {
        int blue = clampColor(Math.round(130 + confidence * 125));
        int green = clampColor(Math.round(100 + confidence * 120));
        int red = clampColor(Math.round(50 + confidence * 70));
        return (red << 16) | (green << 8) | blue;
    }

    private static int sampleYuv(Image image, int x, int y, int fallback) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return fallback;
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return fallback;
        }
        try {
            int yValue = samplePlane(planes[0], x, y);
            int uValue = samplePlane(planes[1], x / 2, y / 2);
            int vValue = samplePlane(planes[2], x / 2, y / 2);

            int c = Math.max(0, yValue - 16);
            int d = uValue - 128;
            int e = vValue - 128;
            int red = clampColor((298 * c + 409 * e + 128) >> 8);
            int green = clampColor((298 * c - 100 * d - 208 * e + 128) >> 8);
            int blue = clampColor((298 * c + 516 * d + 128) >> 8);
            return (red << 16) | (green << 8) | blue;
        } catch (IndexOutOfBoundsException ignored) {
            return fallback;
        }
    }

    private static int samplePlane(Image.Plane plane, int x, int y) {
        ByteBuffer buffer = plane.getBuffer();
        int index = y * plane.getRowStride() + x * plane.getPixelStride();
        if (index < 0 || index >= buffer.limit()) {
            throw new IndexOutOfBoundsException("YUV plane coordinate outside buffer");
        }
        return buffer.get(index) & 0xFF;
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static final class Accumulator {
        private double sumX;
        private double sumY;
        private double sumZ;
        private long sumRed;
        private long sumGreen;
        private long sumBlue;
        private int count;

        void add(float x, float y, float z, int red, int green, int blue) {
            sumX += x;
            sumY += y;
            sumZ += z;
            sumRed += red;
            sumGreen += green;
            sumBlue += blue;
            count++;
        }

        PointSample toPoint() {
            int safeCount = Math.max(1, count);
            return new PointSample(
                    (float) (sumX / safeCount),
                    (float) (sumY / safeCount),
                    (float) (sumZ / safeCount),
                    (int) (sumRed / safeCount),
                    (int) (sumGreen / safeCount),
                    (int) (sumBlue / safeCount)
            );
        }
    }
}
