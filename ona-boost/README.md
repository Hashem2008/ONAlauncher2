# ONA Boost

ONA Launcher's own FPS mod, made for phones only (it switches itself off on a computer).

It keeps the game at a target FPS (60 by default, set in ONA Launcher):

- FPS under 85% of the target for 3 seconds: the next, least visible notch gets lighter
  (particles, shadows of mobs, clouds, rain and snow radius, entity distance, render distance
  2 chunks at a time, simulation distance in single player, then minimal particles, no clouds,
  half entity distance).
- FPS over 130% of the target for 10 seconds: the last notch comes back. A notch that came back
  too soon waits longer next time, so nothing flickers.
- Your own settings are kept in `config/onaboost-baseline.json` while anything is lighter and come
  back when you leave the world, or at the next start after a crash.
- Once: biome blend at most 2 and mipmaps at most 2 (the costly parts of building chunks).
- Any error switches it off for the session; the game goes on with your settings.

It works on top of Sodium / Embeddium and with Iris / Oculus shaders: it only uses the game's own
settings, one tiny mixin on the client tick (Fabric) or Forge's tick event, no Fabric API needed.

Config: `config/onaboost.json` (`enabled`, `targetFps`, `minRenderDistance`,
`minSimulationDistance`, `phoneDefaults`, `log`).

## Builds

- `fabric/`: Fabric / Quilt, Minecraft 26.x (Loom, Java 25)
- `forge/`: Forge 1.20.1 (ForgeGradle 7, Java 17, SRG renamed jar)
- `common/`: the loader independent part, compiled into both

`.github/workflows/ona_boost.yml` builds both and publishes them to the `ona-boost` release, where
ONA Launcher downloads them from (`OnaBoostInstaller`).

License: GPL-3.0-or-later.
