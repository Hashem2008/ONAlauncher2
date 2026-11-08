"""ONA Renderer: ONA's own build of MobileGlues (MobileGL-Dev/MobileGlues, LGPL-2.1).

MobileGlues translates desktop OpenGL 4.x (what Minecraft, Sodium and Iris speak) to the OpenGL ES
3.2 every phone has. ONA builds it from source instead of shipping a prebuilt library, so its
fixes and tuning go into the engine itself. Changes here (keep them small and documented, the
source stays LGPL-2.1, see LICENSE in the checkout):

- the version string says "ONA Renderer" (Minecraft's F3 / log show which engine runs)
- the vendor string keeps the MobileGlues authors and adds ONA STUDIO
- ONA_RENDERER is defined for every source file, for later ONA-only code paths

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
