// ONA Mobile Shaders - where a vertex lands in the shadow map (the warp is done per pixel)
#ifdef SHADOWS
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
varying vec4 shadowClip;

// Pushed out along the face normal, more far away and on faces turned from the light: no shadow
// acne and no shadows lifting off the ground (the usual normal-offset bias of good packs).
void onaShadowPos(vec3 playerPos, vec3 worldNormal, float NdotL) {
    float bias = 0.25 * clamp(0.12 + 0.01 * length(playerPos), 0.0, 1.0) * (2.0 - clamp(NdotL, 0.0, 1.0));
    shadowClip = shadowProjection * (shadowModelView * vec4(playerPos + worldNormal * bias, 1.0));
}
#endif
