// ONA Mobile Shaders - options. Made for phone GPUs: forward lighting (one pass per object), LDR
// colour buffer, and every expensive effect behind an option. The launcher picks a level for the
// device (POTATO ... IMPOSSIBLE) and writes the matching values.

//#define SHADOWS              // sun / moon shadows (renders the world once more from the sun)
#define SHADOW_FILTER 1        // [0 1 2 3] 0 = hard, 1 = soft, 2 = softer, 3 = soft and smooth (rotated disc)
#define WAVING_PLANTS          // leaves, grass, flowers and crops move with the wind
#define WATER_WAVES            // moving water surface
#define WATER_REFLECTIONS      // sky reflection and sun glint on water
//#define WATER_DEPTH          // deep water gets darker and clearer near the shore
#define PBR 1                  // [0 1 2] surfaces: 0 = flat, 1 = bumps, 2 = bumps + shine + glow
//#define BLOOM                // soft glow around bright things
//#define GODRAYS              // sun rays (screen space, cheap)
//#define AUTO_EXPOSURE        // eyes adapt: caves get brighter, open sky stays natural
//#define FOLIAGE_SSS          // sunlight shines through leaves and grass
//#define ATMOSPHERE           // realistic sky colours (scattering), also used for the fog
//#define WET_SURFACES         // rain makes the ground wet and shiny
//#define VOLUMETRIC_LIGHT     // light shafts through the air (needs shadows)
#define VL_STEPS 8             // [6 8 12 16]
//#define SSAO                 // ambient occlusion: corners and gaps get darker
#define SSAO_SAMPLES 8         // [6 8 12 16]
//#define SSR                  // real reflections of the world on water
#define SSR_STEPS 16           // [12 16 24 32]
#define POM_SAMPLES 0          // [0 8 16 24] parallax depth from the resource pack's height maps (HD packs)
#define POM_DEPTH 0.20         // [0.10 0.15 0.20 0.30 0.40]
#define NORMAL_STRENGTH 1.00   // [0.25 0.50 0.75 1.00 1.25 1.50 2.00]
#define SPECULAR_STRENGTH 1.00 // [0.00 0.50 0.75 1.00 1.50 2.00]
#define HANDHELD_LIGHT         // a torch in the hand lights the area around you
#define WIND_STRENGTH 1.00     // [0.00 0.50 1.00 1.50 2.00 3.00]
#define WATER_WAVE_SPEED 1.00  // [0.25 0.50 1.00 1.50 2.00]
#define WATER_WAVE_SIZE 1.00   // [0.25 0.50 1.00 1.50 2.00]
//#define ONA_CLOUDS           // soft moving clouds painted in the sky (instead of the block clouds)
//#define SHADOW_ENTITIES      // mobs and players cast shadows (costs FPS)
#define SUN_HALO 1.00          // [0.00 0.50 1.00 1.50]
#define EXPOSURE 1.00          // [0.70 0.85 1.00 1.15 1.30]
#define CONTRAST 1.00          // [0.80 0.90 1.00 1.10 1.20]
#define WARMTH 0.00            // [-0.20 -0.10 0.00 0.10 0.20]
//#define VIGNETTE_FX          // darker corners like a camera
//#define FILM_GRAIN           // light film grain
//#define CHROMATIC            // colour fringes at the edges like a lens
#define SUN_STRENGTH 1.00      // [0.60 0.80 1.00 1.20 1.40]
#define SATURATION 1.10        // [0.80 0.90 1.00 1.10 1.20 1.30]
#define FOG_START 0.55         // [0.30 0.40 0.55 0.70 0.85]

const int shadowMapResolution = 1024; // [512 1024 2048 3072 4096]
const float shadowDistance = 64.0;    // [32.0 48.0 64.0 96.0 128.0 160.0 192.0]
const float shadowDistanceRenderMul = 1.0;
const float sunPathRotation = -25.0;
const float ambientOcclusionLevel = 0.85;
const bool shadowHardwareFiltering = true;

const float SHADOW_DISTORT = 0.85;
