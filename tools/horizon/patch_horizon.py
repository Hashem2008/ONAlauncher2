"""ONA: small patches to the Horizon Android port (MocLG/horizon-android, MIT) so its game
library runs inside ONA Launcher instead of its own app:

- its files go to a folder of their own (filesDir/horizon-rust, externalFiles/horizon-rust),
  so nothing of ONA's is touched;
- it uses the Java 25 runtime ONA already installed (platform "jre_dir") instead of a second copy;
- it also looks for Minecraft 26.3 in ONA's own .minecraft (platform "minecraft_dir");
- the NDK r25 clang resource folder is lib64/clang, which its ndk-clang wrapper did not look in.

Usage: python3 patch_horizon.py <horizon checkout>
"""
import sys
from pathlib import Path

root = Path(sys.argv[1])


def patch(rel, old, new, count=1):
    path = root / rel
    text = path.read_text(encoding="utf-8")
    if text.count(old) != count:
        sys.exit(f"{rel}: expected {count} of {old!r}, found {text.count(old)}")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"patched {rel}")


install = "crates/horizon-client/src/android/install.rs"

patch(install,
      '            .unwrap_or_else(|| PathBuf::from("/data/local/tmp/horizon"));\n',
      '            .unwrap_or_else(|| PathBuf::from("/data/local/tmp/horizon"));\n'
      '        // ONA: Horizon keeps its files in a folder of its own inside ONA Launcher\'s.\n'
      '        let internal = internal.join("horizon-rust");\n')

patch(install,
      '            .unwrap_or_else(|| internal.join("shared"));\n',
      '            .unwrap_or_else(|| internal.join("shared"));\n'
      '        let shared = shared.join("horizon-rust");\n'
      '        let _ = fs::create_dir_all(&shared);\n')

patch(install,
      '            jre: internal.join("runtimes").join("jre-25"),\n',
      '            // ONA: the Java 25 runtime ONA Launcher installed, when it hands one over.\n'
      '            jre: ona_platform("jre_dir")\n'
      '                .map(PathBuf::from)\n'
      '                .unwrap_or_else(|| internal.join("runtimes").join("jre-25")),\n')

patch(install,
      '    pub fn copied_minecraft(&self) -> PathBuf {\n        self.shared.join("minecraft")\n',
      '    pub fn copied_minecraft(&self) -> PathBuf {\n'
      '        // ONA: ONA Launcher\'s own .minecraft, where 26.3 may already be installed.\n'
      '        ona_platform("minecraft_dir")\n'
      '            .map(PathBuf::from)\n'
      '            .unwrap_or_else(|| self.shared.join("minecraft"))\n')

patch(install,
      '    unpack(app, "jre-25.zip", &paths.jre, log)\n}',
      '    if ona_platform("jre_dir").is_some() {\n'
      '        return Ok(());\n'
      '    }\n'
      '    unpack(app, "jre-25.zip", &paths.jre, log)\n}\n'
      '\n'
      '/// ONA: a non-empty answer of the activity\'s platform call.\n'
      'fn ona_platform(op: &str) -> Option<String> {\n'
      '    super::jni::platform(op, "").filter(|value| !value.is_empty())\n'
      '}')

# ONA local account: the game plays offline under the name ONA hands over (platform
# "player_name"), instead of the name Horizon made up; a Microsoft sign-in is not asked for then.
patch(install,
      '    if account::client_id().is_some() {\n'
      '        let (session, _offline) =\n',
      '    let ona_name = ona_player_name();\n'
      '    if ona_name.is_none() && account::client_id().is_some() {\n'
      '        let (session, _offline) =\n')

patch(install,
      '            args.push("--session-file".into());\n'
      '            args.push(file.display().to_string());\n'
      '        }\n'
      '    }\n'
      '    Ok(args)\n',
      '            args.push("--session-file".into());\n'
      '            args.push(file.display().to_string());\n'
      '        }\n'
      '    }\n'
      '    if let Some(name) = ona_name {\n'
      '        args.push("--name".into());\n'
      '        args.push(name);\n'
      '    }\n'
      '    Ok(args)\n')

patch(install,
      '/// ONA: a non-empty answer of the activity\'s platform call.\n',
      '/// ONA: the local account ONA Launcher plays with, when it is a valid player name.\n'
      'pub fn ona_player_name() -> Option<String> {\n'
      '    ona_platform("player_name").filter(|name| {\n'
      '        (3..=16).contains(&name.len())\n'
      '            && name.chars().all(|c| c.is_ascii_alphanumeric() || c == \'_\')\n'
      '    })\n'
      '}\n'
      '\n'
      '/// ONA: a non-empty answer of the activity\'s platform call.\n')

patch("crates/horizon-client/src/android/launcher.rs",
      '                ui.row(\n'
      '                    row,\n'
      '                    i,\n'
      '                    "Microsoft account",\n'
      '                    "Not signed in: the game plays offline.",\n',
      '                let ona_offline = install::ona_player_name()\n'
      '                    .map(|name| format!("ONA local account: the game plays offline as {name}."))\n'
      '                    .unwrap_or_else(|| "Not signed in: the game plays offline.".to_owned());\n'
      '                ui.row(\n'
      '                    row,\n'
      '                    i,\n'
      '                    "Microsoft account",\n'
      '                    &ona_offline,\n')

patch("android/tools/ndk-clang",
      'resource=$(printf "%s\\n" "$llvm"/lib/clang/* | sort -V | tail -n 1)',
      'resource=$(ls -d "$llvm"/lib/clang/* "$llvm"/lib64/clang/* 2>/dev/null | sort -V | tail -n 1)')
