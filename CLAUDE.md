# ONA Launcher 2 - notes for Claude sessions

- Read `ONA_HANDOFF.txt` first: what was done, open items, user rules, how to check CI.
- Then read `ONA_WORK_METHOD.txt`: HOW the work is done here (read the log as a timeline, check the
  real game code with javap, prove changes before pushing, CI flow, checklist). Work that way.
- After EVERY push (every CI build), update `ONA_HANDOFF.txt` with what changed, the CI result and
  what comes next, and push it. The user asked for this so a new session can always continue.
- The user writes Arabic: answer in short, simple Arabic.
- Never disable or delete a user's mod; only make it work (configs, renderer choice, ports).
- There is no Android SDK/NDK in the container: GitHub Actions (push_ci.yml -> build.yml) is the
  compile check. Development branch: main (the old claude/upbeat-lovelace-cqpsmf is merged).
