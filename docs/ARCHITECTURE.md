# Architecture

Kotlin, Compose Multiplatform 1.8 (Jetpack Compose on Android), Material 3, Coroutines/Flow, Room,
WorkManager, MediaStore, Media3, Coil, Android Keystore. Same Android toolchain as Veyra (AGP 8.9.2,
Kotlin 2.1.21, compileSdk 36, targetSdk 35), with minSdk 30.

## Running on a PC (decided 22 Oct 2026)

PhotoVault may be ported to a Windows PC, so it is built now to make that a small step:

- **Shared by default.** Screens, design system, state and all logic that isn't tied to a device live in
  Kotlin Multiplatform modules (`commonMain`). Today they build for Android only; the PC target is paused.
- **Platform pieces behind interfaces.** Anything only one platform has gets a small interface in shared code and
  one implementation per platform. Example from Phase 1: `AppearanceStore` (Android SharedPreferences,
  PC Java preferences). Planned ones:

| Need | Android | Windows PC |
| --- | --- | --- |
| Secret storage (drive password) | Keystore (`SecretBox`) | Windows DPAPI / Credential Manager |
| Finding photos | MediaStore | Chosen folders (Pictures, OneDrive, phone imports) |
| Background backup | WorkManager + foreground service | Background task while running, Windows Task Scheduler for schedules |
| VPN | In-app WireGuard tunnel | The official WireGuard for Windows app (no in-app tunnel) |
| Delete after backup | MediaStore delete request | Move to Recycle Bin, with confirmation |
| Battery saver / reduce motion | PowerManager, animator scale | Windows "Animation effects" setting, power mode |

- **SMB is already portable.** smbj is plain Java; when the PC app needs the drive, `SmbConnection`,
  `SmbErrors` and the path rules move from `:core:smb` (Android library) into a shared JVM module. Only
  `LanDiscovery` and `SmbReachability` use Android network APIs.
- **Screens are written for both.** Mouse hover, keyboard focus and shortcuts (Ctrl+1..5, Ctrl +/-), resizable
  windows from 420 dp wide, and no Android-only APIs in `commonMain`.
- `:desktop` is the PC app. **Paused (22 Oct 2026)** until the phone app is finished and refined: it is not
  built, the shared modules have only the Android target, and CI builds no Windows app. The shared-code
  rule above still applies, so resuming means re-adding `jvm("desktop")` and `include(":desktop")`.

## Modules

Phase 0 creates the first four. The rest are added by the phase that needs them, so no module exists empty.

| Module | Phase | Responsibility |
| --- | --- | --- |
| `:app` | 0 | Android host: activity, platform signals, Android implementations of shared interfaces |
| `:desktop` | Paused | Windows PC host: window, keyboard shortcuts, PC implementations of shared interfaces |
| `:core:security` | 0 | `SecretBox` (Keystore AES-GCM), `SmbCredentialStore`, `PathRules` |
| `:core:smb` | 0 | `SmbConnection`, `SmbErrors`, `LanDiscovery` (from Veyra) |
| `:core:vpn` | 0 | `WireGuardManager`, `WireGuardConfigStore`, `SmbReachability`, `ConfigText` (from Veyra) |
| `:core:designsystem` | 1 | **Shared.** Liquid Glass components, 4 themes, contrast checks, motion, adaptive layout rules |
| `:ui:shell` | 1 | **Shared.** App scaffold, navigation, screens (sample data until Phase 2) |
| `:core:database` | 2 | Room: media index, albums, backup jobs, manifests cache, audit |
| `:core:media` | 2 | MediaStore discovery, thumbnails |
| `:feature:gallery`, `:feature:viewer`, `:feature:albums`, `:feature:search` | 2 | Local gallery |
| `:data:remote` | 3 | Remote index and thumbnail cache over `:core:smb` |
| `:core:household` | 4 | User/device IDs, per-person folder layout and containment |
| `:data:backup`, `:feature:backup` | 5 | Backup engine, schedule, restore, dashboard |
| `:feature:cleanup` | 6 | Retention, re-verification, delete consent |
| `:feature:remoteaccess` | 7 | VPN settings UI over `:core:vpn` |
| `:feature:sharing` | 8 | Shared albums |

Dependency direction: `feature → data → core`. Features never talk to each other directly.
Dependency injection: a small hand-written service locator, as in Veyra, until it stops being enough.

## Drive layout

```
PhotoVault/
  system/                 format version, household file
  users/<user-id>/
    originals/YYYY/MM/    photos
    videos/YYYY/MM/
    manifests/            one manifest per phone, append-only records
    thumbnails/
  shared/<album-id>/      shared album copies (Phase 8)
```

IDs are random UUIDs. Display names are stored in the household file, never used as paths.

## Background work

- Scheduled backups: WorkManager unique periodic work, with constraints for unmetered network and/or
  charging, plus a direct-reachability check of the drive before starting. Android may run it late.
- Long transfers run as a foreground service (`dataSync`) with a notification.
- A backup over the VPN takes a `WireGuardManager.TunnelHold` for its whole run.

## Known technical risks

| Risk | Plan |
| --- | --- |
| The WireGuard tunnel lives in the app process. If Android stops the process, a backup over VPN stops too. | Backups over VPN run only in a foreground service; resumable jobs make a stop safe. Validate in Phase 7. |
| Monthly/weekly schedules are not native WorkManager periods. | Daily periodic work that checks whether today's slot is due; or one-time work re-armed after each run. Decide in Phase 5. |
| Routers often speak SMB2 only, without server-side copy or hashing. | Verification reads the file back (BACKUP_PROTOCOL.md). |
| Read-back doubles traffic. | Wi-Fi by default; the mobile-data warning says so. |
