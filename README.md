# PhotoVault

An Android 11+ photo and video gallery that backs up each family member's phone into their own folder
on one shared SMB drive (for example a USB drive on the home router). Built on the SMB and WireGuard
code from Veyra (`deep2093/SmbVideoPlayer`).

**Status: Phase 1 (design).** The Liquid Glass design, four themes and all main screens with sample data,
on Android and as a Windows PC app. No real photos or backup yet. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Layout

| Path | What it holds |
| --- | --- |
| `app/` | The Android app |
| `desktop/` | The Windows PC app (same screens) |
| `core/designsystem/` | Shared: Liquid Glass components and the four themes |
| `ui/shell/` | Shared: navigation and screens |
| `core/security/` | Keystore encryption helper, drive credential store, path rules |
| `core/smb/` | SMB2/3 connection, error messages, LAN discovery (from Veyra) |
| `core/vpn/` | WireGuard tunnel manager, encrypted config store, reachability (from Veyra) |
| `docs/` | Requirements, architecture, security model, backup protocol, roadmap, test plan |
| `PROJECT_PLAN.md` | Standing instructions for Claude Code |

## Building

CI (`.github/workflows/android.yml`) is the build authority. It runs unit tests and lint for every
module (Android and PC), builds the preview APK and the Windows app. From a run's artifacts:
`PhotoVault-preview-arm64-apk` for phones and tablets, `PhotoVault-windows` for a PC (run `PhotoVault.exe`).

Locally: `./gradlew testDebugUnitTest desktopTest :app:assembleDebug` with JDK 17 and the Android SDK
(platform 36). Run the PC app with `./gradlew :desktop:run`.

## Workflow

- One branch per phase (`phase-0-foundation`, `phase-1-design`, ...), merged to `main` after review.
- Nothing is pushed to GitHub until Deepak says so.
