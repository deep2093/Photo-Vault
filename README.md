# Pixallery (code name PhotoVault)

An Android 11+ photo and video gallery that backs up each family member's phone into their own folder
on one shared SMB drive (for example a USB drive on the home router). Built on the SMB and WireGuard
code from Veyra (`deep2093/SmbVideoPlayer`).

**Status: Phase 4 (verified backup), built and waiting for testing.** Each phone backs up into its person's folder:
every file is read back and checked before it counts, nothing on the drive is ever overwritten, scheduled
backups (Sunday 2 am by default) wait for Wi-Fi and a charger, and backed-up photos can be restored.
The app was renamed Pixallery on 24 Oct 2026; the repository, package and code keep the PhotoVault name for now.
See [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/BACKUP_PROTOCOL.md](docs/BACKUP_PROTOCOL.md).

## Layout

| Path | What it holds |
| --- | --- |
| `app/` | The Android app |
| `desktop/` | Windows PC app, paused until the phone app is finished (not built) |
| `core/designsystem/` | Shared: Liquid Glass components and themes |
| `core/media/` | Shared: photo model, timeline, search, albums; Android MediaStore source |
| `core/drive/` | Shared: drive file access the app needs (create without overwriting) |
| `core/household/` | Shared: the family on the drive, owner PIN, space limits, profile pictures |
| `core/backup/` | Shared: verified backup engine, schedule, restore |
| `ui/shell/` | Shared: navigation and screens |
| `core/security/` | Keystore encryption helper, drive credential store, path rules |
| `core/smb/` | SMB2/3 connection, error messages, LAN discovery (from Veyra) |
| `core/vpn/` | WireGuard tunnel manager, encrypted config store, reachability (from Veyra) |
| `docs/` | Requirements, architecture, security model, backup protocol, roadmap, test plan |
| `PROJECT_PLAN.md` | Standing instructions for Claude Code |

## Building

CI (`.github/workflows/android.yml`) is the build authority. It runs unit tests and lint for every
module and builds the preview APK. Install `PhotoVault-preview-arm64-apk` from a run's artifacts.

Locally: `./gradlew testDebugUnitTest :app:assembleDebug` with JDK 17 and the Android SDK (platform 36).

## Workflow

- One branch per phase (`phase-0-foundation`, `phase-1-design`, ...), merged to `main` after review.
- Nothing is pushed to GitHub until Deepak says so.
