// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import com.blanket.runtime.glue.GlueFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Draws a recorded triangle list with raw GL inside a state guard (rendering-api.md section 3.4). */
final class Executor {
    private Executor() {}

    static Gl gl;

    private static final int BLEND = 0x0BE2, DEPTH_TEST = 0x0B71, CULL_FACE = 0x0B44, SCISSOR_TEST = 0x0C11;
    private static final int CURRENT_PROGRAM = 0x8B8D, VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int ARRAY_BUFFER = 0x8892, ARRAY_BUFFER_BINDING = 0x8894;
    private static final int BLEND_SRC_RGB = 0x80C9, BLEND_DST_RGB = 0x80C8, BLEND_SRC_ALPHA = 0x80CB, BLEND_DST_ALPHA = 0x80CA;
    private static final int BLEND_EQUATION_RGB = 0x8009, BLEND_EQUATION_ALPHA = 0x883D;
    private static final int DEPTH_FUNC = 0x0B74, DEPTH_WRITEMASK = 0x0B72, VIEWPORT = 0x0BA2;
    private static final int FRAMEBUFFER = 0x8D40, DRAW_FRAMEBUFFER = 0x8CA9, READ_FRAMEBUFFER = 0x8CA8;
    private static final int DRAW_FRAMEBUFFER_BINDING = 0x8CA6, READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int PROJECTION_MATRIX = 0x0BA7, MODELVIEW_MATRIX = 0x0BA6;

    private static boolean failed, ready;
    private static int program, mvpLocation, vbo, vao, ownFbo;
    private static FloatBuffer vertices = direct(7 * 6 * 64).asFloatBuffer();
    private static final FloatBuffer matrix = direct(16).asFloatBuffer();
    private static final IntBuffer ints = direct(4).asIntBuffer();

    static void drawHud(Recorder r) {
        if (r.vertexCount() == 0) return;
        float[] mvp;
        if (GlueFrame.fbo() == -1) {
            mvp = mul(read(PROJECTION_MATRIX), read(MODELVIEW_MATRIX));
        } else {
            mvp = ortho(GlueFrame.guiWidth(), GlueFrame.guiHeight());
        }
        run(r, mvp, false);
    }

    static void drawWorld(Recorder r, float[] projection, float[] view) {
        if (r.vertexCount() == 0) return;
        float[] p = projection != null ? projection : read(PROJECTION_MATRIX);
        float[] v = projection != null ? view : read(MODELVIEW_MATRIX);
        run(r, v == null ? p : mul(p, v), true);
    }

    private static void run(Recorder r, float[] mvp, boolean depth) {
        if (failed) return;
        try {
            if (!ready) setup();
            boolean core = GlueFrame.core();
            // ---- save
            int prog = gl.getInteger(CURRENT_PROGRAM);
            int vaoBinding = core ? gl.getInteger(VERTEX_ARRAY_BINDING) : 0;
            int arrayBuffer = gl.getInteger(ARRAY_BUFFER_BINDING);
            boolean blend = gl.isEnabled(BLEND), depthTest = gl.isEnabled(DEPTH_TEST);
            boolean cull = gl.isEnabled(CULL_FACE), scissor = gl.isEnabled(SCISSOR_TEST);
            int srcRgb = gl.getInteger(BLEND_SRC_RGB), dstRgb = gl.getInteger(BLEND_DST_RGB);
            int srcA = gl.getInteger(BLEND_SRC_ALPHA), dstA = gl.getInteger(BLEND_DST_ALPHA);
            int eqRgb = gl.getInteger(BLEND_EQUATION_RGB), eqA = gl.getInteger(BLEND_EQUATION_ALPHA);
            int depthFunc = gl.getInteger(DEPTH_FUNC);
            boolean depthMask = gl.getInteger(DEPTH_WRITEMASK) != 0;
            int target = GlueFrame.fbo();
            int drawFbo = 0, readFbo = 0, vx = 0, vy = 0, vw = 0, vh = 0;
            if (target != -1) {
                drawFbo = gl.getInteger(DRAW_FRAMEBUFFER_BINDING);
                readFbo = gl.getInteger(READ_FRAMEBUFFER_BINDING);
                ints.clear();
                gl.getIntegers(VIEWPORT, ints);
                vx = ints.get(0); vy = ints.get(1); vw = ints.get(2); vh = ints.get(3);
            }
            try {
                // ---- draw
                if (target != -1) {
                    gl.bindFramebuffer(FRAMEBUFFER, target == -2 ? textureFbo() : target);
                    gl.viewport(0, 0, GlueFrame.fbWidth(), GlueFrame.fbHeight());
                }
                gl.useProgram(program);
                matrix.clear();
                matrix.put(mvp).flip();
                gl.uniformMatrix4(mvpLocation, matrix);
                if (core) gl.bindVertexArray(vao);
                gl.bindBuffer(ARRAY_BUFFER, vbo);
                if (vertices.capacity() < r.floats) vertices = direct(r.floats).asFloatBuffer();
                vertices.clear();
                vertices.put(r.data, 0, r.floats).flip();
                gl.bufferData(ARRAY_BUFFER, vertices, 0x88E0 /* STREAM_DRAW */);
                gl.vertexAttribPointer(0, 3, 0x1406 /* FLOAT */, false, 28, 0);
                gl.vertexAttribPointer(1, 4, 0x1406, false, 28, 12);
                gl.enableVertexAttribArray(0);
                gl.enableVertexAttribArray(1);
                gl.setEnabled(BLEND, true);
                gl.blendFuncSeparate(0x0302, 0x0303, 1, 0x0303); // SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA
                gl.blendEquationSeparate(0x8006, 0x8006);        // FUNC_ADD
                gl.setEnabled(CULL_FACE, false);
                gl.setEnabled(SCISSOR_TEST, false);
                gl.setEnabled(DEPTH_TEST, depth);
                if (depth) gl.depthFunc(0x0203);                // LEQUAL
                gl.depthMask(false);
                gl.drawArrays(4 /* TRIANGLES */, 0, r.vertexCount());
                if (!core) {
                    gl.disableVertexAttribArray(0);
                    gl.disableVertexAttribArray(1);
                }
            } finally {
                // ---- restore
                gl.useProgram(prog);
                if (core) gl.bindVertexArray(vaoBinding);
                gl.bindBuffer(ARRAY_BUFFER, arrayBuffer);
                gl.setEnabled(BLEND, blend);
                gl.blendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
                gl.blendEquationSeparate(eqRgb, eqA);
                gl.setEnabled(DEPTH_TEST, depthTest);
                gl.depthFunc(depthFunc);
                gl.depthMask(depthMask);
                gl.setEnabled(CULL_FACE, cull);
                gl.setEnabled(SCISSOR_TEST, scissor);
                if (target != -1) {
                    gl.bindFramebuffer(DRAW_FRAMEBUFFER, drawFbo);
                    gl.bindFramebuffer(READ_FRAMEBUFFER, readFbo);
                    gl.viewport(vx, vy, vw, vh);
                }
            }
        } catch (Throwable t) {
            failed = true;
            System.err.println("[blanket] drawing disabled after an error: " + t);
            t.printStackTrace();
        }
    }

    /** 26.1+: the main render target is not bound at our hooks, so attach its textures to our own FBO. */
    private static int textureFbo() {
        if (ownFbo == 0) ownFbo = gl.genFramebuffers();
        gl.bindFramebuffer(FRAMEBUFFER, ownFbo);
        gl.framebufferTexture2D(FRAMEBUFFER, 0x8CE0 /* COLOR_ATTACHMENT0 */, 0x0DE1, GlueFrame.colorTex(), 0);
        gl.framebufferTexture2D(FRAMEBUFFER, 0x8D00 /* DEPTH_ATTACHMENT */, 0x0DE1, GlueFrame.depthTex(), 0);
        int status = gl.checkFramebufferStatus(FRAMEBUFFER);
        if (status != 0x8CD5) throw new IllegalStateException("framebuffer incomplete: 0x" + Integer.toHexString(status));
        return ownFbo;
    }

    private static void setup() {
        boolean core = GlueFrame.core();
        String vs = core
                ? "#version 150\nin vec3 aPos;\nin vec4 aCol;\nuniform mat4 uMvp;\nout vec4 vCol;\n"
                  + "void main() { gl_Position = uMvp * vec4(aPos, 1.0); vCol = aCol; }\n"
                : "#version 120\nattribute vec3 aPos;\nattribute vec4 aCol;\nuniform mat4 uMvp;\nvarying vec4 vCol;\n"
                  + "void main() { gl_Position = uMvp * vec4(aPos, 1.0); vCol = aCol; }\n";
        String fs = core
                ? "#version 150\nin vec4 vCol;\nout vec4 fragColor;\nvoid main() { fragColor = vCol; }\n"
                : "#version 120\nvarying vec4 vCol;\nvoid main() { gl_FragColor = vCol; }\n";
        int v = shader(0x8B31, vs), f = shader(0x8B30, fs);
        program = gl.createProgram();
        gl.attachShader(program, v);
        gl.attachShader(program, f);
        gl.bindAttribLocation(program, 0, "aPos");
        gl.bindAttribLocation(program, 1, "aCol");
        gl.linkProgram(program);
        if (gl.getProgrami(program, 0x8B82) == 0) throw new IllegalStateException("link: " + gl.getProgramInfoLog(program));
        mvpLocation = gl.getUniformLocation(program, "uMvp");
        vbo = gl.genBuffers();
        if (core) vao = gl.genVertexArrays();
        ready = true;
    }

    private static int shader(int type, String src) {
        int s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (gl.getShaderi(s, 0x8B81) == 0) throw new IllegalStateException("compile: " + gl.getShaderInfoLog(s));
        return s;
    }

    private static float[] read(int pname) {
        matrix.clear();
        gl.getFloats(pname, matrix);
        float[] m = new float[16];
        matrix.get(m);
        return m;
    }

    /** Column-major a * b. */
    static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++)
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = s;
            }
        return r;
    }

    /** GUI pixels, origin top left. */
    static float[] ortho(float w, float h) {
        float[] m = new float[16];
        m[0] = 2 / w; m[5] = -2 / h; m[10] = -1; m[12] = -1; m[13] = 1; m[15] = 1;
        return m;
    }

    private static ByteBuffer direct(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder());
    }
}
