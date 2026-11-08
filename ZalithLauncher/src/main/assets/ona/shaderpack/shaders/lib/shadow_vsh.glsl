// ONA Mobile Shaders - where a vertex lands in the shadow map (warp is done per pixel)
#ifdef SHADOWS
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
varying vec4 shadowClip;

// playerPos: position relative to the camera in world axes; worldNormal: world axes
void onaShadowPos(vec3 playerPos, vec3 worldNormal) {
    // pushed out along the normal a little: no shadow acne on lit faces
    vec3 p = playerPos + worldNormal * (0.04 + length(playerPos) * 0.0015);
    shadowClip = shadowProjection * (shadowModelView * vec4(p, 1.0));
}
#endif
