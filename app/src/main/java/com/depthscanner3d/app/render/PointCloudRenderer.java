package com.depthscanner3d.app.render;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.google.ar.core.Camera;
import com.google.ar.core.Pose;

import java.io.IOException;
import java.nio.FloatBuffer;

public final class PointCloudRenderer {
    private static final int FLOATS_PER_POINT = 6;
    private static final int BYTES_PER_POINT = FLOATS_PER_POINT * Float.BYTES;

    private int program;
    private int positionAttribute;
    private int colorAttribute;
    private int mvpUniform;
    private int pointSizeUniform;
    private int vertexBuffer;
    private int bufferCapacityBytes;
    private int pointCount;

    public void createOnGlThread(Context context) throws IOException {
        program = GlUtil.createProgram(
                context,
                "shaders/point_cloud.vert",
                "shaders/point_cloud.frag"
        );
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position");
        colorAttribute = GLES20.glGetAttribLocation(program, "a_Color");
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp");
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize");

        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        vertexBuffer = buffers[0];
        bufferCapacityBytes = 32_768 * BYTES_PER_POINT;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bufferCapacityBytes,
                null, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GlUtil.checkGlError("PointCloudRenderer.create");
    }

    public void update(FloatBuffer points) {
        if (points == null) {
            pointCount = 0;
            return;
        }
        points.position(0);
        pointCount = points.remaining() / FLOATS_PER_POINT;
        int requiredBytes = pointCount * BYTES_PER_POINT;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
        if (requiredBytes > bufferCapacityBytes) {
            while (bufferCapacityBytes < requiredBytes) {
                bufferCapacityBytes *= 2;
            }
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bufferCapacityBytes,
                    null, GLES20.GL_DYNAMIC_DRAW);
        }
        if (requiredBytes > 0) {
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, requiredBytes, points);
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GlUtil.checkGlError("PointCloudRenderer.update");
    }

    public void draw(Camera camera, Pose scanAnchorPose) {
        if (pointCount <= 0 || scanAnchorPose == null) {
            return;
        }

        float[] projection = new float[16];
        float[] view = new float[16];
        float[] model = new float[16];
        float[] viewModel = new float[16];
        float[] mvp = new float[16];
        camera.getProjectionMatrix(projection, 0, 0.05f, 25.0f);
        camera.getViewMatrix(view, 0);
        scanAnchorPose.toMatrix(model, 0);
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0);

        GLES20.glUseProgram(program);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);

        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT,
                false, BYTES_PER_POINT, 0);

        GLES20.glEnableVertexAttribArray(colorAttribute);
        GLES20.glVertexAttribPointer(colorAttribute, 3, GLES20.GL_FLOAT,
                false, BYTES_PER_POINT, 3 * Float.BYTES);

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0);
        GLES20.glUniform1f(pointSizeUniform, 4.0f);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount);

        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(colorAttribute);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GlUtil.checkGlError("PointCloudRenderer.draw");
    }
}
