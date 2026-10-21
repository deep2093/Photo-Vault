# Test plan

Three levels, always reported separately: **compiles**, **tests pass** (CI output shown), **tested on a device**
(with the real router drive).

## Unit tests (CI, every push)

| Module | Tests now (Phase 0) | Added later |
| --- | --- | --- |
| `:core:security` | `PathRulesTest`: root normalisation, rejects `..`, `.`, empty segments | Per-user path containment (Phase 4) |
| `:core:smb` | `SmbErrorsTest`: transient vs permanent, no secrets in messages; `SmbProfileTest`: password never in `toString()` | Publish-without-overwrite, temp naming (Phase 5) |
| `:core:vpn` | `ConfigTextTest`: app-only rewrite (from Veyra) | Hold/back-off policy (Phase 7) |
| `:core:designsystem` (Android + PC) | Phase 1: `ThemeContrastTest` (every text colour ≥ 4.5:1 on its worst glass background, accent ≥ 3:1, badges, blur tint strength), `ThemeCatalogTest`, `AdaptiveLayoutTest` | |
| `:ui:shell` (Android + PC) | Phase 1: `ShellStateTest` (battery saver turns blur off, reduce motion, settings saved, grid zoom limits, agreed warning text) | |

## Device and integration tests (from the phase that needs them)

- Two and three users upload the same file names at the same time; folders never mix. (4, 5)
- Kill the app, restart the router, unplug the drive, fill the drive during upload. (5)
- Corrupt a file on the drive after upload: it must not be VERIFIED or eligible for cleanup. (5, 6)
- Schedule: daily/weekly/monthly runs; conditions not met → waits; runs late but runs. (5)
- Deny Android's delete prompt: the original stays. (6)
- Switch Wi-Fi → mobile data during upload, during VPN connect, during restore. (5, 7)
- App-only routing: other apps' traffic does not enter the tunnel. (7)
- Remove a family member from a shared album while they view it. (8)
- Reinstall, new phone, lost phone. (10)
- 4K video, 20,000+ photo libraries, tablet, dark/light, reduce motion. (2, 10)

## CI

Unit tests (Android and PC targets) and lint for every module, debug (preview-signed) and release
(shrinker) builds, arm64 preview APK as an artifact, and a Windows build of the PC app. Emulator tests with a throwaway SMB share are added in Phase 3,
reusing Veyra's `tools/start_test_share.sh` approach.
