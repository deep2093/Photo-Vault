# Pixallery (code name PhotoVault)

An Android 11+ photo and video gallery that backs up each family member's phone into their own folder
on one shared SMB drive (for example a USB drive on the home router), and optionally to their own Google Drive. Built on the SMB and WireGuard
code from Veyra (`deep2093/SmbVideoPlayer`).

**Status (16 Nov 2026): Phases 0 to 11 and Family roles are built and on `main`, being tested on phones.** That
covers the gallery, verified backup to the family drive, backup to the person's own Google Drive (optional; the
app only sees the files it saves there), the family with roles, Family Album, remote access by WireGuard, People,
Places, Memories, the Secured folder, the photo editor with video trim, and the duplicate finder. Still to come:
smoothness and foldables (Phase 12) and release (Phase 13).
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
