# PhotoVault

An Android 11+ photo and video gallery that backs up each family member's phone into their own folder
on one shared SMB drive (for example a USB drive on the home router). Built on the SMB and WireGuard
code from Veyra (`deep2093/SmbVideoPlayer`).

**Status: Phase 2 (phone gallery).** Your real photos and videos: timeline, viewer, albums, search,
favourites, sharing and Recently deleted, in ten glass and adaptive themes. Backup and family screens still
show sample data until Phases 3-4. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Layout

| Path | What it holds |
| --- | --- |
| `app/` | The Android app |
| `desktop/` | Windows PC app, paused until the phone app is finished (not built) |
| `core/designsystem/` | Shared: Liquid Glass components and the four themes |
| `core/media/` | Shared: photo model, timeline, search, albums; Android MediaStore source |
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
