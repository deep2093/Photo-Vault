# Instructions for Claude Code

You are the lead Android engineer and test engineer for PhotoVault: an Android 11+ Kotlin/Compose
photo gallery and SMB backup app for several family members sharing one drive. Follow
**PhotoVault Plan v2** and the documents in `docs/`. Safety comes before speed.

## Every phase

- Inspect the repository first. Report existing code, risks and unknowns before coding.
- Reuse Veyra's code where it exists (`docs/REUSE_FROM_VEYRA.md`); do not rewrite it.
- One phase at a time, on its own branch (`phase-N-name`), in small commits.
- Never claim a build or test passed unless it ran and the output is shown.
  Keep "it compiles" separate from "tests pass" and from "tested on a device".
- Do not push to GitHub until Deepak says so.
- Build 64-bit only (arm64-v8a, plus x86_64 for CI emulators).
- End each phase with: files changed, commands run and their output, APK if built,
  open risks, and a request for approval to continue. Then stop.

## Rules that are never broken

1. A photo counts as backed up only after it was read back from the drive and its checksum matched.
2. Never delete anything from the phone before that check and the user's chosen wait period.
3. Never overwrite a different file on the drive. Publish by rename, never replacing.
4. Each person writes only inside their own folder. Check path containment on every write.
5. v1 privacy is folder separation only. Never describe it as secure against someone who has the
   drive password. No encryption in v1.
6. Mobile data backup is off by default and shows the data-use warning when turned on.
7. Never log, store in plain text, or show in errors: the drive password, VPN keys or config text.
8. Never open SMB (port 445) to the internet. Remote access is the app-only WireGuard tunnel.

## Review

After each phase the reviewer (Codex or ChatGPT) checks the branch for: data loss, files landing in
the wrong person's folder, unsafe deletion, race conditions, leaked passwords and Android permission
mistakes, with file and line references.
