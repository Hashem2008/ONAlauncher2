#version 120
#include "/lib/settings.glsl"

// ONA Shader ++ temporal smoothing: the finished picture is blended with the picture of the frames
// before it, each pixel taken from where that spot of the world was on screen then. Shadow edges,
// light shafts and corners take other sample spots every frame (onaFrameNoise), so the blend holds
// many times the samples of one frame: no grain, no flicker on thin things far away.

/* RENDERTARGETS: 0,3 */
/*
const int colortex3Format = RGBA8;
const bool colortex3Clear = false;
*/

uniform sampler2D colortex0;
uniform sampler2D colortex3;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform float viewWidth;
uniform float viewHeight;

varying vec2 texcoord;

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
#ifdef TAA
    float depth = texture2D(depthtex0, texcoord).r;
    vec2 prev = texcoord;
    float keep = 0.90;
    // the hand moves with the camera: it is where it was. Everything else: where this spot of the
    // world was on screen one frame ago
    if (depth > 0.56) {
        vec4 view = gbufferProjectionInverse * vec4(vec3(texcoord, depth) * 2.0 - 1.0, 1.0);
        view /= view.w;
        vec4 world = gbufferModelViewInverse * view;
        world.xyz += cameraPosition - previousCameraPosition;
        vec4 clip = gbufferPreviousProjection * (gbufferPreviousModelView * world);
        if (clip.w > 0.0) prev = clip.xy / clip.w * 0.5 + 0.5; else keep = 0.0;
    }
    if (prev.x < 0.0 || prev.x > 1.0 || prev.y < 0.0 || prev.y > 1.0) keep = 0.0;
    vec4 history = texture2D(colortex3, prev);
    // alpha 1 marks a stored picture (nothing is stored in the very first frame)
    if (history.a < 0.5) keep = 0.0;
    if (keep > 0.0) {
        // the colours around this pixel now: a past colour outside of them belongs to something
        // that moved away (no ghost trails behind mobs and the hand)
        vec2 px = vec2(1.0 / viewWidth, 1.0 / viewHeight);
        vec3 c1 = texture2D(colortex0, texcoord + vec2(px.x, 0.0)).rgb;
        vec3 c2 = texture2D(colortex0, texcoord - vec2(px.x, 0.0)).rgb;
        vec3 c3 = texture2D(colortex0, texcoord + vec2(0.0, px.y)).rgb;
        vec3 c4 = texture2D(colortex0, texcoord - vec2(0.0, px.y)).rgb;
        vec3 low = min(color, min(min(c1, c2), min(c3, c4)));
        vec3 high = max(color, max(max(c1, c2), max(c3, c4)));
        // turning fast: less of the past, so the picture stays sharp in motion
        float moved = length((prev - texcoord) * vec2(viewWidth, viewHeight));
        keep *= 1.0 - clamp(moved / 24.0, 0.0, 1.0) * 0.45;
        color = mix(color, clamp(history.rgb, low, high), keep);
    }
#endif
    gl_FragData[0] = vec4(color, 1.0);
    gl_FragData[1] = vec4(color, 1.0);
}
