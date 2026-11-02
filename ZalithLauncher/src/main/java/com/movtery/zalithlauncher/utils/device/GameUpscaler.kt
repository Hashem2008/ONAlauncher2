/*
 * ONA Launcher 2 (based on Zalith Launcher 2)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.utils.device

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.movtery.zalithlauncher.setting.enums.UpscalerMode
import com.movtery.zalithlauncher.utils.logging.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ONA: spatial upscaler that sits between the game and the screen.
 *
 * The game (any renderer: GL4ES, MobileGlues, Zink, the 26.x Vulkan backend...) draws into
 * [inputTexture] at the reduced render resolution. Every new frame is scaled up to the screen
 * by one full screen pass on a small GLES 2 context of our own and presented on the
 * TextureView. Rendering 67% of the pixels per side is less than half the pixel work for the
 * game, the pass itself costs well under a millisecond on current phones.
 *
 * Two filters:
 *  - GSR: single pass, 12 tap, edge directed Lanczos style filter that works on luma
 *    gradients and keeps edges sharp, the same idea as Snapdragon Game Super Resolution.
 *  - ASR: bicubic (Catmull-Rom, 9 bilinear taps) plus contrast adaptive sharpening, the
 *    cheap path that runs well on every ARM GPU (Mali, Immortalis, Xclipse, PowerVR, Adreno).
 * Arm's own ASR and GSR 2 are temporal and need motion vectors from the game engine, which
 * a launcher does not have, so both modes here are spatial.
 *
 * The colour grade of the game menu is applied in the same pass for free.
 */
class GameUpscaler private constructor(
    private val output: SurfaceTexture,
    requested: UpscalerMode
) {
    /** GSR or ASR once the GPU is known */
    @Volatile var mode: UpscalerMode = requested
        private set

    private val thread = HandlerThread("ONA-Upscaler", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var aPos = 0
    private var uTexMatrix = 0
    private var uInSize = 0
    private var uSharp = 0
    private var uGrade = 0
    private var uGradeOn = 0

    /** Where the game draws, at render resolution. Valid after [create] returned it. */
    lateinit var inputTexture: SurfaceTexture
        private set

    @Volatile private var inputWidth = 1
    @Volatile private var inputHeight = 1
    @Volatile private var released = false

    /** 0..1 */
    @Volatile var sharpness: Float = 0.5f

    /** Column major colour matrix (rgb' = M * (rgb, 1)), null for no grading */
    @Volatile var gradeMatrix: FloatArray? = null

    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    /** Resolution the game renders at */
    fun setInputSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        inputWidth = width
        inputHeight = height
        runCatching { inputTexture.setDefaultBufferSize(width, height) }
    }

    /** Re-present the last frame, e.g. after the colour grade changed while the game is paused */
    fun redraw() {
        handler.post { drawFrame(latch = false) }
    }

    private fun start(sharp: Float): Boolean {
        sharpness = sharp
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching { initGl() }.onFailure {
                Logger.error(TAG, "Upscaler init failed", it)
            }.getOrDefault(false)
            latch.countDown()
        }
        return latch.await(3, TimeUnit.SECONDS) && ok
    }

    private fun initGl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, null, 0, null, 0)) return false
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false
        val config = configs[0] ?: return false
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        // The game paces itself, never wait for vsync here on top of it
        EGL14.eglSwapInterval(display, 0)

        if (mode == UpscalerMode.AUTO) {
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            mode = if (renderer.contains("adreno", ignoreCase = true)) UpscalerMode.GSR else UpscalerMode.ASR
        }
        program = buildProgram(VERTEX, if (mode == UpscalerMode.GSR) FRAGMENT_GSR else FRAGMENT_ASR)
        if (program == 0) return false
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uInSize = GLES20.glGetUniformLocation(program, "uInSize")
        uSharp = GLES20.glGetUniformLocation(program, "uSharp")
        uGrade = GLES20.glGetUniformLocation(program, "uGrade")
        uGradeOn = GLES20.glGetUniformLocation(program, "uGradeOn")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        inputTexture = SurfaceTexture(texture).apply {
            setOnFrameAvailableListener({ drawFrame(latch = true) }, handler)
        }
        Logger.info(TAG, "Upscaler ready: $mode")
        return true
    }

    private fun drawFrame(latch: Boolean) {
        if (released || program == 0) return
        try {
            if (latch) inputTexture.updateTexImage()
            inputTexture.getTransformMatrix(texMatrix)

            val size = IntArray(2)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, size, 0)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, size, 1)
            GLES20.glViewport(0, 0, size[0], size[1])

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uInSize, inputWidth.toFloat(), inputHeight.toFloat())
            GLES20.glUniform1f(uSharp, sharpness)
            val grade = gradeMatrix
            if (grade != null) {
                GLES20.glUniformMatrix4fv(uGrade, 1, false, grade, 0)
                GLES20.glUniform1f(uGradeOn, 1f)
            } else {
                GLES20.glUniform1f(uGradeOn, 0f)
            }
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, surface)
        } catch (e: Exception) {
            // The game surface was abandoned while the activity goes away
            Logger.warning(TAG, "Upscaler frame dropped", e)
        }
    }

    private val releaseRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    fun release() {
        if (!releaseRequested.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        handler.post {
            released = true
            runCatching {
                if (::inputTexture.isInitialized) {
                    inputTexture.setOnFrameAvailableListener(null)
                    inputTexture.release()
                }
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                // Never eglTerminate: the game shares the default display in this process
            }
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Logger.error(TAG, "Shader error: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Logger.error(TAG, "Program error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    companion object {
        private const val TAG = "GameUpscaler"

        /**
         * @param mode the user setting, AUTO picks GSR on Adreno and ASR elsewhere
         * @param sharpness 0..1
         * @return null when upscaling is off or the GPU refused the context
         */
        fun create(output: SurfaceTexture, mode: UpscalerMode, sharpness: Float): GameUpscaler? {
            if (mode == UpscalerMode.OFF) return null
            val upscaler = GameUpscaler(output, mode)
            return if (upscaler.start(sharpness)) {
                upscaler
            } else {
                upscaler.release()
                null
            }
        }

        private const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val COMMON = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec2 uInSize;
uniform float uSharp;
uniform mat4 uGrade;
uniform float uGradeOn;
varying vec2 vUv;

vec3 fetch(vec2 uv) {
    return texture2D(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}
vec3 grade(vec3 c) {
    if (uGradeOn > 0.5) c = (uGrade * vec4(c, 1.0)).rgb;
    return clamp(c, 0.0, 1.0);
}
"""

        /** Edge directed 12 tap upscaler (GSR style) */
        private const val FRAGMENT_GSR = COMMON + """
float lanczosish(float d2, float lob, float clp) {
    d2 = min(d2, clp);
    float wB = 0.4 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 1.5625 * wB - 0.5625;
    return wB * wA;
}

void tap(inout vec3 sum, inout float wsum, vec3 col, vec2 o, vec2 dir, vec2 scale, float lob, float clp) {
    vec2 v = vec2(o.x * dir.x + o.y * dir.y, o.y * dir.x - o.x * dir.y) * scale;
    float w = lanczosish(dot(v, v), lob, clp);
    sum += col * w;
    wsum += w;
}

void main() {
    vec2 t = 1.0 / uInSize;
    vec2 pp = vUv * uInSize - 0.5;
    vec2 fp = floor(pp);
    vec2 f = pp - fp;
    vec2 base = (fp + 0.5) * t;

    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 cb = fetch(base + vec2( 0.0, -1.0) * t);
    vec3 cc = fetch(base + vec2( 1.0, -1.0) * t);
    vec3 ce = fetch(base + vec2(-1.0,  0.0) * t);
    vec3 cf = fetch(base);
    vec3 cg = fetch(base + vec2( 1.0,  0.0) * t);
    vec3 ch = fetch(base + vec2( 2.0,  0.0) * t);
    vec3 ci = fetch(base + vec2(-1.0,  1.0) * t);
    vec3 cj = fetch(base + vec2( 0.0,  1.0) * t);
    vec3 ck = fetch(base + vec2( 1.0,  1.0) * t);
    vec3 cl = fetch(base + vec2( 2.0,  1.0) * t);
    vec3 cn = fetch(base + vec2( 0.0,  2.0) * t);
    vec3 co = fetch(base + vec2( 1.0,  2.0) * t);

    float lb = luma(cb); float lc = luma(cc);
    float le = luma(ce); float lf = luma(cf); float lg = luma(cg); float lh = luma(ch);
    float li = luma(ci); float lj = luma(cj); float lk = luma(ck); float ll = luma(cl);
    float ln = luma(cn); float lo = luma(co);

    // Luma gradient at the four centre texels, blended to the sample position
    vec2 gF = vec2(lg - le, lj - lb);
    vec2 gG = vec2(lh - lf, lk - lc);
    vec2 gJ = vec2(lk - li, ln - lf);
    vec2 gK = vec2(ll - lj, lo - lg);
    vec2 dir = gF * (1.0 - f.x) * (1.0 - f.y) + gG * f.x * (1.0 - f.y)
             + gJ * (1.0 - f.x) * f.y + gK * f.x * f.y;
    float dirLen2 = dot(dir, dir);
    float edge = clamp(sqrt(dirLen2) * 2.0, 0.0, 1.0);
    edge *= edge;
    dir = dirLen2 < 1.0e-6 ? vec2(1.0, 0.0) : dir * inversesqrt(dirLen2);

    // Narrow across the edge, wide along it
    float stretch = 1.0 / max(abs(dir.x), abs(dir.y));
    vec2 scale = vec2(1.0 + (stretch - 1.0) * edge, 1.0 - 0.5 * edge);
    float lob = 0.5 - 0.29 * edge;
    float clp = 1.0 / lob;

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    tap(sum, wsum, cb, vec2(0.0, -1.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, cc, vec2(1.0, -1.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, ce, vec2(-1.0, 0.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, cf, vec2(0.0, 0.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, cg, vec2(1.0, 0.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, ch, vec2(2.0, 0.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, ci, vec2(-1.0, 1.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, cj, vec2(0.0, 1.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, ck, vec2(1.0, 1.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, cl, vec2(2.0, 1.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, cn, vec2(0.0, 2.0) - f, dir, scale, lob, clp);
    tap(sum, wsum, co, vec2(1.0, 2.0) - f, dir, scale, lob, clp);

    vec3 mn = min(min(cf, cg), min(cj, ck));
    vec3 mx = max(max(cf, cg), max(cj, ck));
    vec3 c = clamp(sum / max(wsum, 1.0e-4), mn, mx);

    // Light contrast adaptive sharpening on top
    vec3 avg = (cf + cg + cj + ck) * 0.25;
    vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(1.0e-4)), 0.0, 1.0);
    c += (c - avg) * sqrt(amp) * uSharp * 0.6;
    gl_FragColor = vec4(grade(c), 1.0);
}
"""

        /** Bicubic + contrast adaptive sharpening (ASR style, cheapest) */
        private const val FRAGMENT_ASR = COMMON + """
void main() {
    vec2 t = 1.0 / uInSize;
    vec2 samplePos = vUv * uInSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 p0 = (texPos1 - 1.0) * t;
    vec2 p3 = (texPos1 + 2.0) * t;
    vec2 p12 = (texPos1 + w2 / w12) * t;

    vec3 c = fetch(vec2(p0.x, p0.y)) * w0.x * w0.y
           + fetch(vec2(p12.x, p0.y)) * w12.x * w0.y
           + fetch(vec2(p3.x, p0.y)) * w3.x * w0.y
           + fetch(vec2(p0.x, p12.y)) * w0.x * w12.y
           + fetch(vec2(p12.x, p12.y)) * w12.x * w12.y
           + fetch(vec2(p3.x, p12.y)) * w3.x * w12.y
           + fetch(vec2(p0.x, p3.y)) * w0.x * w3.y
           + fetch(vec2(p12.x, p3.y)) * w12.x * w3.y
           + fetch(vec2(p3.x, p3.y)) * w3.x * w3.y;

    vec3 n = fetch(vUv + vec2(0.0, t.y));
    vec3 s = fetch(vUv - vec2(0.0, t.y));
    vec3 e = fetch(vUv + vec2(t.x, 0.0));
    vec3 w = fetch(vUv - vec2(t.x, 0.0));
    vec3 mn = min(min(min(n, s), min(e, w)), c);
    vec3 mx = max(max(max(n, s), max(e, w)), c);
    vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(1.0e-4)), 0.0, 1.0);
    vec3 blur = (n + s + e + w) * 0.25;
    c = clamp(c + (c - blur) * sqrt(amp) * uSharp, mn, mx);
    gl_FragColor = vec4(grade(c), 1.0);
}
"""
    }
}
