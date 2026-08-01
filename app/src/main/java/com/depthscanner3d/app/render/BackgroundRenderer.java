package com.depthscanner3d.app.render;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.io.IOException;
import java.nio.FloatBuffer;

public final class BackgroundRenderer {
    private static final float[] QUAD_COORDINATES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
    };

    private final FloatBuffer quadCoords = GlUtil.createFloatBuffer(QUAD_COORDINATES);
    private final FloatBuffer transformedTexCoords = GlUtil.createFloatBuffer(new float[8]);

    private int textureId = -1;
    private int program;
    private int positionAttribute;
    private int texCoordAttribute;
    private int textureUniform;

    public void createOnGlThread(Context context) throws IOException {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        program = GlUtil.createProgram(
                context,
                "shaders/background.vert",
                "shaders/background.frag"
        );
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position");
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord");
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture");
        GlUtil.checkGlError("BackgroundRenderer.create");
    }

    public int getTextureId() {
        return textureId;
    }

    public void draw(Frame frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0);
            transformedTexCoords.position(0);
            frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    quadCoords,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    transformedTexCoords
            );
        }

        if (frame.getTimestamp() == 0L) {
            return;
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(program);

        quadCoords.position(0);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT,
                false, 0, quadCoords);
        GLES20.glEnableVertexAttribArray(positionAttribute);

        transformedTexCoords.position(0);
        GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT,
                false, 0, transformedTexCoords);
        GLES20.glEnableVertexAttribArray(texCoordAttribute);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(textureUniform, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(texCoordAttribute);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GlUtil.checkGlError("BackgroundRenderer.draw");
    }
}
