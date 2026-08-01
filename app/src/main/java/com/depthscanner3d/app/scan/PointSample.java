package com.depthscanner3d.app.scan;

public final class PointSample {
    private final float x;
    private final float y;
    private final float z;
    private final int red;
    private final int green;
    private final int blue;

    public PointSample(float x, float y, float z, int red, int green, int blue) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public float x() { return x; }
    public float y() { return y; }
    public float z() { return z; }
    public int red() { return red; }
    public int green() { return green; }
    public int blue() { return blue; }
}
