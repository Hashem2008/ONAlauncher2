# ONA Launcher 2 - notes for Claude sessions

- Read `ONA_HANDOFF.txt` first: what was done, open items, user rules, how to check CI.
- After EVERY push (every CI build), update `ONA_HANDOFF.txt` with what changed, the CI result and
  what comes next, and push it. The user asked for this so a new session can always continue.
- The user writes Arabic: answer in short, simple Arabic.
- Never disable or delete a user's mod; only make it work (configs, renderer choice, ports).
- There is no Android SDK/NDK in the container: GitHub Actions (push_ci.yml -> build.yml) is the
  compile check. Development branch: claude/upbeat-lovelace-cqpsmf.
