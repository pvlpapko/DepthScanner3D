package com.depthscanner3d.app.util;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import com.google.ar.core.Session;

public final class DisplayRotationHelper implements DisplayManager.DisplayListener {
    private final Activity activity;
    private final DisplayManager displayManager;
    private volatile boolean viewportChanged;
    private int viewportWidth;
    private int viewportHeight;

    public DisplayRotationHelper(Activity activity) {
        this.activity = activity;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    public void onResume() {
        displayManager.registerDisplayListener(this, null);
    }

    public void onPause() {
        displayManager.unregisterDisplayListener(this);
    }

    public void onSurfaceChanged(int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        viewportChanged = true;
    }

    public void updateSessionIfNeeded(Session session) {
        if (!viewportChanged || viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        Display display = activity.getWindowManager().getDefaultDisplay();
        int rotation = display.getRotation();
        session.setDisplayGeometry(rotation, viewportWidth, viewportHeight);
        viewportChanged = false;
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        viewportChanged = true;
    }
}
