// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class Lwjgl3Gl implements Gl {
    public int getInteger(int pname) { return GL11.glGetInteger(pname); }
    public void getIntegers(int pname, IntBuffer out) { GL11.glGetIntegerv(pname, out); }
    public void getFloats(int pname, FloatBuffer out) { GL11.glGetFloatv(pname, out); }
    public boolean isEnabled(int cap) { return GL11.glIsEnabled(cap); }
    public void setEnabled(int cap, boolean on) { if (on) GL11.glEnable(cap); else GL11.glDisable(cap); }
    public void blendFuncSeparate(int a, int b, int c, int d) { GL14.glBlendFuncSeparate(a, b, c, d); }
    public void blendEquationSeparate(int rgb, int alpha) { GL20.glBlendEquationSeparate(rgb, alpha); }
    public void depthFunc(int func) { GL11.glDepthFunc(func); }
    public void depthMask(boolean on) { GL11.glDepthMask(on); }
    public void viewport(int x, int y, int w, int h) { GL11.glViewport(x, y, w, h); }
    public int createShader(int type) { return GL20.glCreateShader(type); }
    public void shaderSource(int shader, String src) { GL20.glShaderSource(shader, src); }
    public void compileShader(int shader) { GL20.glCompileShader(shader); }
    public int getShaderi(int shader, int pname) { return GL20.glGetShaderi(shader, pname); }
    public String getShaderInfoLog(int shader) { return GL20.glGetShaderInfoLog(shader, 4096); }
    public int createProgram() { return GL20.glCreateProgram(); }
    public void attachShader(int program, int shader) { GL20.glAttachShader(program, shader); }
    public void bindAttribLocation(int program, int index, String name) { GL20.glBindAttribLocation(program, index, name); }
    public void linkProgram(int program) { GL20.glLinkProgram(program); }
    public int getProgrami(int program, int pname) { return GL20.glGetProgrami(program, pname); }
    public String getProgramInfoLog(int program) { return GL20.glGetProgramInfoLog(program, 4096); }
    public void useProgram(int program) { GL20.glUseProgram(program); }
    public int getUniformLocation(int program, String name) { return GL20.glGetUniformLocation(program, name); }
    public void uniformMatrix4(int location, FloatBuffer m) { GL20.glUniformMatrix4fv(location, false, m); }
    public int genBuffers() { return GL15.glGenBuffers(); }
    public void bindBuffer(int target, int buffer) { GL15.glBindBuffer(target, buffer); }
    public void bufferData(int target, FloatBuffer data, int usage) { GL15.glBufferData(target, data, usage); }
    public void vertexAttribPointer(int i, int size, int type, boolean norm, int stride, long off) { GL20.glVertexAttribPointer(i, size, type, norm, stride, off); }
    public void enableVertexAttribArray(int index) { GL20.glEnableVertexAttribArray(index); }
    public void disableVertexAttribArray(int index) { GL20.glDisableVertexAttribArray(index); }
    public int genVertexArrays() { return GL30.glGenVertexArrays(); }
    public void bindVertexArray(int vao) { GL30.glBindVertexArray(vao); }
    public int genFramebuffers() { return GL30.glGenFramebuffers(); }
    public void bindFramebuffer(int target, int fbo) { GL30.glBindFramebuffer(target, fbo); }
    public void framebufferTexture2D(int t, int a, int tt, int tex, int l) { GL30.glFramebufferTexture2D(t, a, tt, tex, l); }
    public int checkFramebufferStatus(int target) { return GL30.glCheckFramebufferStatus(target); }
    public void drawArrays(int mode, int first, int count) { GL11.glDrawArrays(mode, first, count); }
}
