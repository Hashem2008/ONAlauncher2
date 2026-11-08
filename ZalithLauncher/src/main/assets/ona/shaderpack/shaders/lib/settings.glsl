// ONA Mobile Shaders - options. Written for phone GPUs: forward lighting (one pass per object,
// no G-buffers), LDR colour buffer, every expensive feature behind an option. The launcher picks
// a level for the device (POTATO ... ULTRA) and writes the matching values.

//#define SHADOWS              // sun / moon shadows (renders the world once more from the sun)
#define SHADOW_FILTER 1        // [0 1 2] 0 = hard, 1 = soft (4 taps), 2 = softer (8 taps)
#define WAVING_PLANTS          // leaves, grass, flowers and crops move with the wind
#define WATER_WAVES            // moving water surface
#define WATER_REFLECTIONS      // sky reflection and sun glint on water
#define PBR 1                  // [0 1 2] surfaces: 0 = flat, 1 = bumps (normal maps), 2 = bumps + shine + glow
//#define BLOOM                // soft glow around bright things
//#define GODRAYS              // sun rays
#define SUN_STRENGTH 1.00      // [0.60 0.80 1.00 1.20 1.40]
#define SATURATION 1.10        // [0.80 0.90 1.00 1.10 1.20 1.30]
#define FOG_START 0.55         // [0.30 0.40 0.55 0.70 0.85]

const int shadowMapResolution = 1024; // [512 1024 2048]
const float shadowDistance = 64.0;    // [32.0 48.0 64.0 96.0 128.0]
const float shadowDistanceRenderMul = 1.0;
const float sunPathRotation = -25.0;
const float ambientOcclusionLevel = 0.85;
const bool shadowHardwareFiltering = true;

const float SHADOW_DISTORT = 0.85;
