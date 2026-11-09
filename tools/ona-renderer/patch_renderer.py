"""ONA Renderer: ONA's own build of MobileGlues (MobileGL-Dev/MobileGlues, LGPL-2.1).

MobileGlues translates desktop OpenGL 4.x (what Minecraft, Sodium and Iris speak) to the OpenGL ES
3.2 every phone has. ONA builds it from source instead of shipping a prebuilt library, so its
fixes and tuning go into the engine itself. Changes here (keep them small and documented, the
source stays LGPL-2.1, see LICENSE in the checkout):

- the version string says "ONA Renderer" (Minecraft's F3 / log show which engine runs)
- the vendor string keeps the MobileGlues authors and adds ONA STUDIO
- ONA_RENDERER is defined for every source file, for later ONA-only code paths
- GL_ARB_shader_image_load_store and GL_ARB_shader_storage_buffer_object are only reported together
  with compute shaders: OpenGL ES needs a format on every image, desktop GLSL does not, and shader
  packs (Complementary, BSL, Solas...) turn on their image / SSBO paths whenever Iris sees these
  extensions, even on OpenGL 4.0; those paths then fail to convert ("all images have to define
  layout format"). With them hidden Iris keeps the paths off and the packs load
- for the same reason glXGetProcAddress gives no address for glDispatchCompute(Indirect) and
  glBindImageTexture(s) while compute is off: Iris decides compute / images by function address
  ("Shader compilation failed for shadowcomp" with Complementary). ONA Boost also tells Iris

Usage: python3 patch_renderer.py <MobileGlues checkout>
"""
import sys
from pathlib import Path

root = Path(sys.argv[1]) / "MobileGlues-cpp"


def patch(rel, old, new, count=1):
    path = root / rel
    text = path.read_text(encoding="utf-8")
    if text.count(old) != count:
        sys.exit(f"{rel}: expected {count} of {old!r}, found {text.count(old)}")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"patched {rel}")


patch("version.h", '#define VERSION_SUFFIX ""', '#define VERSION_SUFFIX " (ONA Renderer)"')
patch("gl/getter.cpp", 'std::string vendor = "Swung0x48, BZLZHH, Tungsten";',
      'std::string vendor = "Swung0x48, BZLZHH, Tungsten + ONA STUDIO";')
patch("CMakeLists.txt", 'project("mobileglues")', 'project("mobileglues")\nadd_compile_definitions(ONA_RENDERER=1)')
patch("gl/getter.cpp", '''                               "GL_ARB_shader_storage_buffer_object",
                               "GL_ARB_shader_image_load_store",
''', "")
patch("gles/loader.cpp", '''        AppendExtension("GL_ARB_compute_shader");''',
      '''        AppendExtension("GL_ARB_compute_shader");
        // images and SSBOs only with compute (see the note at the top of patch_renderer.py)
        AppendExtension("GL_ARB_shader_storage_buffer_object");
        AppendExtension("GL_ARB_shader_image_load_store");''')
patch("glx/lookup.cpp", '''void* glXGetProcAddress(const char* name) {
    LOG()
''', '''void* glXGetProcAddress(const char* name) {
    LOG()
    // ONA: without compute no compute / image entry points either (see patch_renderer.py)
    if (!global_settings.ext_compute_shader) {
        static const char* const hidden[] = {"glDispatchCompute", "glDispatchComputeIndirect",
                                             "glBindImageTexture", "glBindImageTextures"};
        for (const char* h : hidden) {
            if (std::strcmp(name, h) == 0) return nullptr;
        }
    }
''')
