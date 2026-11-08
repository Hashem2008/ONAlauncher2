// ONA Mobile Shaders - options. Made for phone GPUs: forward lighting (one pass per object), LDR
// colour buffer, and every expensive effect behind an option. The launcher picks a level for the
// device (POTATO ... IMPOSSIBLE) and writes the matching values.

//#define SHADOWS              // sun / moon shadows (renders the world once more from the sun)
#define SHADOW_FILTER 1        // [0 1 2 3 4] 0 = hard, 1 = soft, 2 = softer, 3 = soft and smooth (rotated disc), 4 = realistic (sharp where it touches, softer further away)
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
#define VL_STEPS 8             // [6 8 12 16 20]
//#define SSAO                 // ambient occlusion: corners and gaps get darker
#define SSAO_SAMPLES 8         // [6 8 12 16]
//#define SSR                  // real reflections of the world on water
#define SSR_STEPS 16           // [12 16 24 32]
#define POM_SAMPLES 0          // [0 8 16 24 32] parallax depth from the resource pack's height maps (HD packs)
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
//#define RAIN_PUDDLES         // rain leaves puddles on open flat ground that mirror the sky
//#define WATER_FOAM           // white foam where water meets the shore (with Deep water)
//#define WATER_CAUSTICS       // moving light patterns on the ground while you swim
//#define DEPTH_OF_FIELD       // what is far from the thing you look at gets soft, like a camera
#define DOF_STRENGTH 1.00      // [0.50 1.00 1.50 2.00] how soft the out-of-focus parts get
//#define LENS_FLARE           // glare rings across the screen when you look near the sun
//#define CLOUD_SHADOWS        // the clouds cast moving shadows on the ground (with ONA clouds)
//#define RAIN_RIPPLES         // raindrops make rings on water and puddles
//#define DISTANCE_HAZE        // far land fades into the air colour, like real distance
//#define SPARKLE              // sand and snow glitter in the sun
#define COLOR_GRADE 0          // [0 1 2 3] colours: 0 natural, 1 film, 2 cool, 3 vivid
//#define VOLUMETRIC_CLOUDS    // 3D-looking clouds: sunlit edges, dark bellies, a high thin layer above (with ONA clouds)
//#define WATER_REFRACTION     // the ground under the water is seen bent by the waves
//#define FLOATING_DUST        // particles: specks of dust drifting in the sunlight, fireflies at night
#define SHADER_DISTANCE 0      // [0 2 4 6 8 12 16] the full shader only this many chunks around you, plain further away (0 = everywhere)
#define SATURATION 1.10        // [0.80 0.90 1.00 1.10 1.20 1.30]
#define FOG_START 0.55         // [0.30 0.40 0.55 0.70 0.85]

const int shadowMapResolution = 1024; // [512 1024 2048 3072 4096]
const float shadowDistance = 64.0;    // [32.0 48.0 64.0 96.0 112.0 128.0 160.0 192.0 224.0]
const float shadowDistanceRenderMul = 1.0;
const float sunPathRotation = -25.0;
const float ambientOcclusionLevel = 0.85;
// shadowtex1 compared in hardware, shadowtex0 raw depth for the realistic filter (as Photon does)
const bool shadowHardwareFiltering1 = true;

const float SHADOW_DISTORT = 0.85;
