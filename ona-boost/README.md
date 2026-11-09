# ONA Boost

ONA Launcher's own FPS mod, made for phones only (it switches itself off on a computer).

It keeps the game at a target FPS (60 by default, set in ONA Launcher):

- FPS under 85% of the target for 3 seconds: the next, least visible notch gets lighter
  (particles, shadows of mobs, clouds, rain and snow radius, improved transparency / Fabulous
  graphics, entity distance, render distance 2 chunks at a time down to 10, simulation distance in
  single player, fast leaves, render distance down to the floor, then minimal particles, no
  clouds, half entity distance).
- FPS over 130% of the target for 10 seconds: the last notch comes back. A notch that came back
  too soon waits longer next time, so nothing flickers.
- With an FPS limit in the game (or VSync) 130% may be out of reach: then staying at the limit for
  30 seconds brings a notch back. A limit under the target is what is kept, nothing is made
  lighter for an FPS the game is not allowed to reach.
- No decision while the game holds the FPS down itself (nobody touched it for a minute), in the
  first seconds of a world or another dimension, or right after the app came back.
- Fast leaves are not taken while a shader pack is on (Iris / Oculus), and come back when one is
  switched on: many packs cut the leaf texture out themselves, the trees would show holes.
- Render distance never goes under 6 chunks, 5 on a mid range phone and 4 on a weak one (ONA
  Launcher writes the device class); a `minRenderDistance` other than 6 always wins.
- Your own value of everything that is lighter is kept in `config/onaboost-baseline.json` and comes
  back when you leave the world, or at the next start after a crash. A value you chose yourself in
  between is never overwritten.
- Once: biome blend at most 2 and mipmaps at most 2 (the costly parts of building chunks).
- Any error switches it off for the session; the game goes on with your settings.

Shader packs (Fabric build): Iris is told when the phone's OpenGL layer has no compute shaders, and
the pack options that need them (Complementary's colored lighting and world space reflections) are
kept off every time a pack loads, also after a profile was picked in the game: the pack loads
instead of "Shader storage buffers ... not supported".

It works on top of Sodium / Embeddium and with Iris / Oculus shaders: it only uses the game's own
settings, one tiny mixin on the client tick (Fabric) or Forge's tick event, no Fabric API needed.

Config: `config/onaboost.json` (`enabled`, `targetFps`, `minRenderDistance`,
`minSimulationDistance`, `phoneDefaults`, `log`; ONA Launcher writes `deviceClass` and `displayHz`).

## Builds

- `fabric/`: Fabric / Quilt, Minecraft 26.x (Loom, Java 25)
- `forge/`: Forge 1.20.1 (ForgeGradle 7, Java 17, SRG renamed jar)
- `common/`: the loader independent part, compiled into both

`.github/workflows/ona_boost.yml` builds both and publishes them to the `ona-boost` release, where
ONA Launcher downloads them from (`OnaBoostInstaller`).

License: GPL-3.0-or-later.
