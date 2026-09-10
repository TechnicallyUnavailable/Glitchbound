package com.technicallyunavailable.measure;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class CameraBackgroundRenderer {
    private static final float[] QUAD = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 a_Position;\n" +
            "attribute vec2 a_TexCoord;\n" +
            "varying vec2 v_TexCoord;\n" +
            "void main(){ gl_Position=vec4(a_Position,0.0,1.0); v_TexCoord=a_TexCoord; }";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 v_TexCoord;\n" +
            "void main(){ gl_FragColor=texture2D(sTexture,v_TexCoord); }";

    private final FloatBuffer quadBuffer = allocate(8);
    private final FloatBuffer transformedUv = allocate(8);
    private int textureId = -1;
    private int program;
    private int posLoc;
    private int uvLoc;
    private int samplerLoc;

    public void createOnGlThread() {
        quadBuffer.put(QUAD).position(0);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new RuntimeException("Could not link camera shader: " + GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);

        posLoc = GLES20.glGetAttribLocation(program, "a_Position");
        uvLoc = GLES20.glGetAttribLocation(program, "a_TexCoord");
        samplerLoc = GLES20.glGetUniformLocation(program, "sTexture");
    }

    public int getTextureId() { return textureId; }

    public void draw(Frame frame) {
        if (frame == null || frame.getTimestamp() == 0) return;

        quadBuffer.position(0);
        transformedUv.position(0);
        frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedUv);
        quadBuffer.position(0);
        transformedUv.position(0);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(samplerLoc, 0);

        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glEnableVertexAttribArray(uvLoc);
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, transformedUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(uvLoc);
        GLES20.glDepthMask(true);
    }

    private static FloatBuffer allocate(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) throw new RuntimeException("Could not compile shader: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
}
