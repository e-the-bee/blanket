// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** The GL calls the executor makes. LWJGL 2 and 3 differ in a few names, so each has an implementation. */
public interface Gl {
    int getInteger(int pname);
    void getIntegers(int pname, IntBuffer out);
    void getFloats(int pname, FloatBuffer out);
    boolean isEnabled(int cap);
    void setEnabled(int cap, boolean on);
    void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha);
    void blendEquationSeparate(int rgb, int alpha);
    void depthFunc(int func);
    void depthMask(boolean on);
    void viewport(int x, int y, int w, int h);
    int createShader(int type);
    void shaderSource(int shader, String src);
    void compileShader(int shader);
    int getShaderi(int shader, int pname);
    String getShaderInfoLog(int shader);
    int createProgram();
    void attachShader(int program, int shader);
    void bindAttribLocation(int program, int index, String name);
    void linkProgram(int program);
    int getProgrami(int program, int pname);
    String getProgramInfoLog(int program);
    void useProgram(int program);
    int getUniformLocation(int program, String name);
    void uniformMatrix4(int location, FloatBuffer m);
    int genBuffers();
    void bindBuffer(int target, int buffer);
    void bufferData(int target, FloatBuffer data, int usage);
    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long offset);
    void enableVertexAttribArray(int index);
    void disableVertexAttribArray(int index);
    int genVertexArrays();
    void bindVertexArray(int vao);
    int genFramebuffers();
    void bindFramebuffer(int target, int fbo);
    void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level);
    int checkFramebufferStatus(int target);
    void drawArrays(int mode, int first, int count);
}
