package com.depthscanner3d.app.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScanSnapshot {
    private final List<PointSample> points;
    private final int integratedDepthFrames;
    private final long startedAtEpochMillis;
    private final float captureVoxelSizeMeters;
    private final float minimumConfidence;
    private final float minimumDepthMeters;
    private final float maximumDepthMeters;

    public ScanSnapshot(
            List<PointSample> points,
            int integratedDepthFrames,
            long startedAtEpochMillis,
            float captureVoxelSizeMeters,
            float minimumConfidence,
            float minimumDepthMeters,
            float maximumDepthMeters
    ) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.integratedDepthFrames = integratedDepthFrames;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.captureVoxelSizeMeters = captureVoxelSizeMeters;
        this.minimumConfidence = minimumConfidence;
        this.minimumDepthMeters = minimumDepthMeters;
        this.maximumDepthMeters = maximumDepthMeters;
    }

    public List<PointSample> points() { return points; }
    public int integratedDepthFrames() { return integratedDepthFrames; }
    public long startedAtEpochMillis() { return startedAtEpochMillis; }
    public float captureVoxelSizeMeters() { return captureVoxelSizeMeters; }
    public float minimumConfidence() { return minimumConfidence; }
    public float minimumDepthMeters() { return minimumDepthMeters; }
    public float maximumDepthMeters() { return maximumDepthMeters; }
}
