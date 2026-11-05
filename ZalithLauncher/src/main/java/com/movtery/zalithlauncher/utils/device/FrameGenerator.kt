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

import android.opengl.GLES20
import com.movtery.zalithlauncher.utils.logging.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ONA: frame generation. Makes one extra picture between every two real game frames, so a game
 * running at 40 FPS is shown at 80 on a 90/120 Hz screen.
 *
 * Works on the finished picture only, so it runs with every renderer and every Minecraft
 * version, in the upscaler's own GLES 2 context (see [GameUpscaler]):
 *  1. every real frame is kept at screen size (two slots, newest and the one before),
 *  2. both are reduced to a quarter size luma picture,
 *  3. a motion field (one vector per 16x16 screen pixels) is found by block matching the two
 *     luma pictures: a coarse search of +-13 luma pixels (52 screen pixels) in steps of 3,
 *     then a fine +-1 search around the best match, with a small bias to "no motion" so flat
 *     areas and the HUD stay still,
 *  4. a 3x3 vector median drops single wrong matches,
 *  5. the middle picture takes every pixel half way along the best of its own, its neighbours'
 *     and the zero vector from both frames and
 *     blends them; where the two disagree (things appearing or hidden) it leans on the newer
 *     frame, so there is no ghosting there.
 * The game never waits for any of it; the real frame is shown half a frame later, which is
 * the only cost in input delay (like every frame generation that has no engine motion vectors).
 */
internal class FrameGenerator {
    private var width = 0
    private var height = 0
    private var lumaWidth = 0
    private var lumaHeight = 0

    private val colorTex = IntArray(2)
    private val colorFbo = IntArray(2)
    private val lumaTex = IntArray(2)
    private val lumaFbo = IntArray(2)
    private var motionTex = 0
    private var motionFbo = 0
    /** The motion field after the outlier pass */
    private var smoothTex = 0
    private var smoothFbo = 0

    /** Slot of the newest real frame */
    private var newest = 0
    private var framesInSlots = 0

    private var blitProgram = 0
    private var lumaProgram = 0
    private var motionProgram = 0
    private var interpProgram = 0
    private var smoothProgram = 0

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    /** Both slots hold a real frame, so a middle picture can be made */
    val canGenerate: Boolean get() = framesInSlots >= 2 && motionTex != 0

    fun init(): Boolean {
        blitProgram = GlPrograms.build(TAG, VERTEX, BLIT)
        lumaProgram = GlPrograms.build(TAG, VERTEX, LUMA)
        motionProgram = GlPrograms.build(TAG, VERTEX, MOTION)
        interpProgram = GlPrograms.build(TAG, VERTEX, INTERPOLATE)
        smoothProgram = GlPrograms.build(TAG, VERTEX, SMOOTH)
        val ok = blitProgram != 0 && lumaProgram != 0 && motionProgram != 0 && interpProgram != 0 && smoothProgram != 0
        if (!ok) Logger.error(TAG, "Frame generation shaders did not build, frames are shown as they come")
        return ok
    }

    /** (Re)create the frame slots for this screen size */
    fun ensureSize(w: Int, h: Int) {
        if (w == width && h == height && colorTex[0] != 0) return
        deleteTargets()
        width = w
        height = h
        lumaWidth = maxOf(1, w / 4)
        lumaHeight = maxOf(1, h / 4)
        for (i in 0..1) {
            makeTarget(w, h).let { (t, f) -> colorTex[i] = t; colorFbo[i] = f }
            makeTarget(lumaWidth, lumaHeight).let { (t, f) -> lumaTex[i] = t; lumaFbo[i] = f }
        }
        // Vectors are read one by one (no blending of two neighbours' vectors at object edges)
        makeTarget(maxOf(1, lumaWidth / 4), maxOf(1, lumaHeight / 4), nearest = true).let { (t, f) -> motionTex = t; motionFbo = f }
        makeTarget(maxOf(1, lumaWidth / 4), maxOf(1, lumaHeight / 4), nearest = true).let { (t, f) -> smoothTex = t; smoothFbo = f }
        framesInSlots = 0
        Logger.info(TAG, "Frame generation ready at ${w}x$h (motion field ${lumaWidth / 4}x${lumaHeight / 4})")
    }

    /** Forget the older frame (pause, menu change, long stall): the next frame is not blended with it */
    fun resetHistory() {
        framesInSlots = minOf(framesInSlots, 1)
    }

    /**
     * Framebuffer the upscaled game picture is drawn into.
     * @param newFrame true for a new real frame (takes the other slot), false to redraw the newest
     */
    fun beginFrame(newFrame: Boolean): Int {
        if (newFrame) newest = 1 - newest
        return colorFbo[newest]
    }

    /** After the picture was drawn into [beginFrame]'s framebuffer */
    fun endFrame(newFrame: Boolean) {
        // The luma picture of the newest frame, the older one is still in the other slot
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lumaFbo[newest])
        GLES20.glViewport(0, 0, lumaWidth, lumaHeight)
        GLES20.glUseProgram(lumaProgram)
        bindTexture(lumaProgram, "uTex", 0, colorTex[newest])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lumaProgram, "uTexel"), 1f / width, 1f / height)
        draw(lumaProgram)
        if (newFrame) framesInSlots = minOf(framesInSlots + 1, 2)
    }

    /** Show the newest real frame on the bound window surface */
    fun drawNewest(viewW: Int, viewH: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glUseProgram(blitProgram)
        bindTexture(blitProgram, "uTex", 0, colorTex[newest])
        draw(blitProgram)
    }

    /** Make and show the picture half way between the older and the newest frame */
    fun drawGenerated(viewW: Int, viewH: Int) {
        val older = 1 - newest
        val lumaTexelX = 1f / lumaWidth
        val lumaTexelY = 1f / lumaHeight

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
        GLES20.glViewport(0, 0, maxOf(1, lumaWidth / 4), maxOf(1, lumaHeight / 4))
        GLES20.glUseProgram(motionProgram)
        bindTexture(motionProgram, "uPrev", 0, lumaTex[older])
        bindTexture(motionProgram, "uCur", 1, lumaTex[newest])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(motionProgram, "uTexel"), lumaTexelX, lumaTexelY)
        draw(motionProgram)

        // Outliers (a block that matched the wrong place) take the vector their neighbours agree on
        val motionW = maxOf(1, lumaWidth / 4)
        val motionH = maxOf(1, lumaHeight / 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smoothFbo)
        GLES20.glUseProgram(smoothProgram)
        bindTexture(smoothProgram, "uMotion", 0, motionTex)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(smoothProgram, "uTexel"), 1f / motionW, 1f / motionH)
        draw(smoothProgram)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glUseProgram(interpProgram)
        bindTexture(interpProgram, "uPrev", 0, colorTex[older])
        bindTexture(interpProgram, "uCur", 1, colorTex[newest])
        bindTexture(interpProgram, "uMotion", 2, smoothTex)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(interpProgram, "uLumaTexel"), lumaTexelX, lumaTexelY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(interpProgram, "uMotionTexel"), 1f / motionW, 1f / motionH)
        draw(interpProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun release() {
        deleteTargets()
        listOf(blitProgram, lumaProgram, motionProgram, interpProgram, smoothProgram).forEach { if (it != 0) GLES20.glDeleteProgram(it) }
        smoothProgram = 0
        blitProgram = 0
        lumaProgram = 0
        motionProgram = 0
        interpProgram = 0
    }

    private fun bindTexture(program: Int, name: String, unit: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
    }

    private fun draw(program: Int) {
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun makeTarget(w: Int, h: Int, nearest: Boolean = false): Pair<Int, Int> {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        val filter = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) Logger.warning(TAG, "Frame slot ${w}x$h incomplete: $status")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return texture to fbo
    }

    private fun deleteTargets() {
        val textures = (colorTex + lumaTex + intArrayOf(motionTex, smoothTex)).filter { it != 0 }.toIntArray()
        val fbos = (colorFbo + lumaFbo + intArrayOf(motionFbo, smoothFbo)).filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        colorTex.fill(0); colorFbo.fill(0); lumaTex.fill(0); lumaFbo.fill(0)
        motionTex = 0
        motionFbo = 0
        smoothTex = 0
        smoothFbo = 0
        framesInSlots = 0
    }

    companion object {
        private const val TAG = "FrameGenerator"

        private const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
"""

        private const val BLIT = HEADER + """
uniform sampler2D uTex;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""

        /** Quarter size luma: 4 bilinear taps cover the 4x4 screen pixels of one output pixel */
        private const val LUMA = HEADER + """
uniform sampler2D uTex;
uniform vec2 uTexel;
float l(vec2 uv) { return dot(texture2D(uTex, uv).rgb, vec3(0.299, 0.587, 0.114)); }
void main() {
    float v = 0.25 * (l(vUv - uTexel) + l(vUv + vec2(uTexel.x, -uTexel.y))
                    + l(vUv + vec2(-uTexel.x, uTexel.y)) + l(vUv + uTexel));
    gl_FragColor = vec4(v, v, v, 1.0);
}
"""

        /**
         * One vector per 4x4 luma pixels: where the block of the older frame went in the newest.
         * Stored as rg = vector / 32 + 0.5 (luma pixels), b = how sure the match is.
         */
        private const val MOTION = HEADER + """
uniform sampler2D uPrev;
uniform sampler2D uCur;
uniform vec2 uTexel;
float cost(vec2 d) {
    vec2 dd = d * uTexel;
    vec2 a = uTexel;
    vec2 b = vec2(uTexel.x, -uTexel.y);
    float c = abs(texture2D(uPrev, vUv - a).r - texture2D(uCur, vUv - a + dd).r);
    c += abs(texture2D(uPrev, vUv + a).r - texture2D(uCur, vUv + a + dd).r);
    c += abs(texture2D(uPrev, vUv - b).r - texture2D(uCur, vUv - b + dd).r);
    c += abs(texture2D(uPrev, vUv + b).r - texture2D(uCur, vUv + b + dd).r);
    c += abs(texture2D(uPrev, vUv).r - texture2D(uCur, vUv + dd).r);
    return c;
}
void main() {
    vec2 best = vec2(0.0);
    float bestCost = cost(best) - 0.02;
    for (int y = -4; y <= 4; y++) {
        for (int x = -4; x <= 4; x++) {
            vec2 d = vec2(float(x), float(y)) * 3.0;
            float c = cost(d) + 0.002 * length(d);
            if (c < bestCost) { bestCost = c; best = d; }
        }
    }
    vec2 coarse = best;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 d = coarse + vec2(float(x), float(y));
            float c = cost(d) + 0.002 * length(d);
            if (c < bestCost) { bestCost = c; best = d; }
        }
    }
    float sure = 1.0 - clamp(bestCost * 1.5, 0.0, 1.0);
    gl_FragColor = vec4(best / 32.0 + 0.5, sure, 1.0);
}
"""

        /**
         * Vector median of the 3x3 neighbourhood: the vector closest to all the others. Keeps
         * real motion edges, drops single wrong matches (those made blocks tear and jump).
         */
        private const val SMOOTH = HEADER + """
uniform sampler2D uMotion;
uniform vec2 uTexel;
void main() {
    vec2 v[9];
    float sure = 0.0;
    for (int y = 0; y < 3; y++) {
        for (int x = 0; x < 3; x++) {
            vec4 m = texture2D(uMotion, vUv + vec2(float(x - 1), float(y - 1)) * uTexel);
            v[y * 3 + x] = m.rg;
            sure += m.b;
        }
    }
    vec2 best = v[4];
    float bestSum = 1e9;
    for (int i = 0; i < 9; i++) {
        float sum = 0.0;
        for (int j = 0; j < 9; j++) sum += length(v[i] - v[j]);
        if (sum < bestSum) { bestSum = sum; best = v[i]; }
    }
    gl_FragColor = vec4(best, min(texture2D(uMotion, vUv).b, sure / 9.0 + 0.1), 1.0);
}
"""

        /**
         * The middle picture: half way along the motion from both frames. Every pixel tries the
         * vector of its own block, of the four next to it and "no motion" (HUD, still parts) and
         * keeps the one on which both frames agree best, so block edges no longer show.
         */
        private const val INTERPOLATE = HEADER + """
uniform sampler2D uPrev;
uniform sampler2D uCur;
uniform sampler2D uMotion;
uniform vec2 uLumaTexel;
uniform vec2 uMotionTexel;
const vec3 W = vec3(0.299, 0.587, 0.114);
vec2 vectorAt(vec2 uv) {
    return (texture2D(uMotion, uv).rg - 0.5) * 32.0 * uLumaTexel;
}
void main() {
    vec4 m = texture2D(uMotion, vUv);
    vec2 cand[6];
    cand[0] = (m.rg - 0.5) * 32.0 * uLumaTexel;
    cand[1] = vectorAt(vUv + vec2(uMotionTexel.x, 0.0));
    cand[2] = vectorAt(vUv - vec2(uMotionTexel.x, 0.0));
    cand[3] = vectorAt(vUv + vec2(0.0, uMotionTexel.y));
    cand[4] = vectorAt(vUv - vec2(0.0, uMotionTexel.y));
    cand[5] = vec2(0.0);
    vec3 a = texture2D(uPrev, vUv - cand[0] * 0.5).rgb;
    vec3 b = texture2D(uCur, vUv + cand[0] * 0.5).rgb;
    float diff = abs(dot(a - b, W));
    // Inside a block that moves like its neighbours (most of the screen) only "no motion" is
    // worth a try; the full search runs at motion edges only, so it costs little GPU time
    float spread = 0.0;
    for (int i = 1; i < 5; i++) spread = max(spread, length((cand[i] - cand[0]) / uLumaTexel));
    int first = spread < 0.75 ? 5 : 1;
    for (int i = 1; i < 6; i++) {
        if (i < first || diff < 0.01) continue;
        vec3 ca = texture2D(uPrev, vUv - cand[i] * 0.5).rgb;
        vec3 cb = texture2D(uCur, vUv + cand[i] * 0.5).rgb;
        // the block's own vector wins ties, so flat areas do not flicker between candidates
        float cd = abs(dot(ca - cb, W)) + 0.004;
        if (cd < diff) { diff = cd; a = ca; b = cb; }
    }
    float lean = max(smoothstep(0.05, 0.22, diff), 1.0 - smoothstep(0.2, 0.6, m.b));
    gl_FragColor = vec4(mix(mix(a, b, 0.5), b, lean), 1.0);
}
"""
    }
}

/** Shader program helper shared by the upscaler and frame generation */
internal object GlPrograms {
    fun build(tag: String, vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Logger.error(tag, "Shader error: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs)
            if (fs != 0) GLES20.glDeleteShader(fs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Logger.error(tag, "Program error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }
}
