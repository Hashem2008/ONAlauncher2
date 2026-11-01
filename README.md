<h1 align="center">ONA Launcher 2</h1>

ONA Launcher 2 is a Minecraft: Java Edition launcher for Android. It is
[Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2) with everything from
[ONAlauncher](https://github.com/Hashem2008/ONAlauncher) (our Amethyst/PojavLauncher based launcher) merged in.

## What ONA Launcher 2 adds on top of Zalith Launcher 2

**Mods**
- Before launching, the mods folder is checked: missing required mods, mods built for another
  Minecraft or loader version (for example a 1.21.1 jar on 1.21), and wrong loader (Fabric mod on Forge).
  A dialog lists every problem with a **Fix** button, plus **Fix all**, which downloads every missing
  mod and swaps broken builds for ones that load (from Modrinth); you can also *Play anyway*.
- The same check is in the mods manager (**Check & fix**).

**Performance on weak and mid-range phones**
- Big core affinity: on big.LITTLE CPUs the game threads stay on all big cores (not just one).
- Android Game Mode (Game Dashboard, Samsung Game Booster…): *Performance* forces big cores and turns off
  the sustained-performance cap, while *Battery* does the opposite.
- First launch defaults by device class: internal resolution near 540p (weak), 720p (mid) or 1080p,
  lighter `options.txt` for new game folders (render distance 6–8, 60 FPS, no clouds/shadows…),
  and 3 GB of RAM for phones with 8 GB or more.

**Accounts and downloads**
- Microsoft login works without a private Azure app id: builds without the `OAUTH_CLIENT_ID` secret use
  the public login.live.com device flow.
- Optional own CurseForge API key in *Settings → Game* (otherwise the built-in key or the MCIM mirror is used).
- **Data packs** download category (Modrinth and CurseForge): the pack is copied into every world of the chosen versions.

**Device support**
- Touchscreen is not required (Chromebooks, TV boxes, gamepad/mouse setups); tall/foldable screens use `max_aspect` 4.0.

**Branding**
- Package `com.onalauncher.app2` (`.debug` for test builds), so it installs next to Zalith Launcher and ONAlauncher.

Zalith Launcher 2 already includes the rest of ONAlauncher's features: the Modrinth/CurseForge browser with
per-version installs and required dependencies, worlds, modpacks, the Krypton Wrapper (NG-GL4ES) renderer,
Minecraft 26.x support and up-to-date Forge/NeoForge/Fabric/Quilt lists.

## Building

Push to GitHub: the **Build CI** workflow builds debug APKs (`all` and `arm64`) under the run's *Artifacts*.
Local build: JDK 21, Android SDK, NDK, Rust with `cargo-ndk`, then `./gradlew ZalithLauncher:assembleDebug`.

## License & credits

GPL-3.0, same as upstream (see [LICENSE](LICENSE)). All credit for the launcher itself goes to
MovTery and the Zalith Launcher contributors, and to the PojavLauncher and Amethyst teams.
The upstream README is kept in [README.zalith.md](README.zalith.md).
