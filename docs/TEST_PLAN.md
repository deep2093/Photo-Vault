# Test plan

Three levels, always reported separately: **compiles**, **tests pass** (CI output shown), **tested on a device**
(with the real router drive).

## Unit tests (CI, every push)

| Module | Tests now (Phase 0) | Added later |
| --- | --- | --- |
| `:core:security` | `PathRulesTest`: root normalisation, rejects `..`, `.`, empty segments | Per-user path containment (Phase 4) |
| `:core:smb` | `SmbErrorsTest`: transient vs permanent, no secrets in messages; `SmbProfileTest`: password never in `toString()` | Publish-without-overwrite, temp naming (Phase 5) |
| `:core:vpn` | `ConfigTextTest`: app-only rewrite (from Veyra) | Hold/back-off policy (Phase 7) |
| `:core:designsystem` | Phase 1: `ThemeContrastTest` (every text colour ≥ 4.5:1 on its worst background: glass over black and white, glass over wallpaper, bare wallpaper; for adaptive themes every hue the palette can make), `ThemeCatalogTest`, `AdaptivePaletteTest`, `AdaptiveLayoutTest` (tablets zoom out to ~48 dp tiles, saved sizes clamped on rotation) | |
| `:core:media` | Phase 2: `DetailsFormatTest` (Samsung-style labels), `TimelineTest` (day/month/year labels, time zone at midnight, durations, days left, 20,000 photos), `SearchAndAlbumsTest`, `MainColorTest` | |
| `:ui:shell` | Phase 1: `ShellStateTest` (battery saver turns blur off, reduce motion, settings saved, grid zoom limits, agreed warning text) | |

## Phase 2 device checks (on Deepak's phone and tablet)

- First launch: "Allow access" → all photos appear; Android 14+ "Select photos" → only those, with "Allow all".
- Timeline: Day/Month/Year, pinch from biggest to smallest on phone and tablet, smooth re-layout.
- Viewer: swipe, pinch and double-tap zoom, drag while zoomed, videos play and stop when swiped away.
- Select several → Share, Favourite, Delete (Android asks) → Recently deleted shows them with days left →
  Restore (Android asks) → back in the timeline.
- Albums: phone folders, Favourites, Videos; Search: "videos", a month, a year, an album name.
- Every theme, including Adaptive (wallpaper follows the photos), Translucent and Frosted.
- Details panel: swipe up and down, Details button, back closes it first; Samsung photo shows camera, lens, ISO,
  aperture, shutter and coordinates; screenshot shows only size; video shows length; location refused → allow card.
- Back from album → viewer → album → Albums never reopens anything (touch fall-through bug, fixed 22 Oct).
- Section jumps (Photos → Settings) slide one step, highlight glides straight across.

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
- 4K video, 20,000+ photo libraries, tablet, dark/light, adaptive themes, reduce motion. (2, 12)
- Delete → Recently deleted → restore, and items purge after 30 days (phone and drive). (2, 5)
- Space limit: backup pauses at the limit, warns at 90%, never deletes to make room; limit change takes effect. (3, 4)
- Places: no location permission → Places explains and asks; shared copies have no location. (7)
- Secured folder: never appears in timeline, search, Places, adaptive colours, thumbnails or recent apps;
  recovery code opens drive copies on a new phone; wrong PIN locks out. (8)

## CI

Unit tests and lint for every module, debug (preview-signed) and release
(shrinker) builds, arm64 preview APK as an artifact. Emulator tests with a throwaway SMB share are added in Phase 3,
reusing Veyra's `tools/start_test_share.sh` approach.
