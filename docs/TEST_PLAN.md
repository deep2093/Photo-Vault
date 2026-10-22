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
| `:core:household` | Phase 3: `HouseholdStoreTest` (setup once, join with and without approval, six racing joins, owner-only actions, wrong PIN, limits, removal keeps photos, damaged newest version), `PinTest` | |
| `:core:smb` (real share) | Phase 3: `RealShareTest` against a throwaway Samba share started in CI: create-new never overwrites, two phones racing for one name, sizes and free space, paths can't escape the folder. Skipped (and reported) when no share is running | |

## Phase 3 device checks (with the real router drive)

- Settings → Drive → Connect: Find drives lists the router; wrong password and wrong share show clear messages.
- First phone: create the family, set a PIN. Second phone: "I'm new" → waits → owner approves on Family → joined.
- Turn "Approve new phones" off → a third phone joins straight away. Turn "Owner PIN" off → no PIN asked.
- Change someone's space; Backup shows the new limit and the real used space; Family shows the whole drive.
- Remove a phone and a person: their folder stays on the drive. Wrong PIN 5 times → wait shown.
- Forget the drive → connect again → same person, no new approval needed.
- Each section of Settings opens and closes; it remembers which are open after rotating.
- Back swipe from either edge on a full album (Camera) closes it without opening a photo (bug 22 Oct).
- Viewer filmstrip: scroll it and the photo follows; tap a thumbnail to jump; swipe the photo and the strip
  follows; one tap fades strip and actions away and back.
- App icon: after updating, only one icon on the home screen; Four Seasons and Bloom in Settings; label "Veyra Photos".

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

## Phase 4 device checks: review fixes from 23 Oct (built first on the Phase 4 branch)

- No glow or smear on any text or icon in any theme: details panel, Back up now, Daily, Day, tab labels, viewer bar.
- Transparent (was Translucent): cards clear, tab bar and pill lightly frosted, wallpaper corners take photo colours.
- The five adaptive themes look clearly different: Glow (bright blooms), Midnight (near black, one light at top),
  Vivid (bold diagonal), Light (pastel bands), Transparent (light, coloured corners).
- Details panel fits without scrolling; swiping down on the panel closes it; so does swiping on the photo.
- No top bars on Photos, Albums, Backup, Family, Settings. Photos: floating pill (cloud opens Backup, search,
  ⋮ shows counts and Day / Month / Year). Selecting photos shows the selection bar without the grid jumping.

## Phase 4 device checks: backup and the 24 Oct fixes

Backup (Wi-Fi at home, drive connected, family joined):
- Backup shows "x of y checked and saved". Back up now: progress, the file name, Stop works; History gets a line.
- Default folders: only Camera. Choose folders adds WhatsApp Images; numbers change. Videos switch off by default.
- On the drive: users/<id>/originals/2026/10/IMG_....jpg, a manifests/<phone id>/m-...json, nothing left in .incoming.
- Copy a photo with the same name but different content into that folder from a PC first: the backup saves
  "name (2).jpg" and the PC file is unchanged.
- Turn the router off mid-backup: "Couldn't reach the drive"; turn it on, Back up now continues without duplicates.
- Set your space to 1 GB: backup stops with "Your space on the drive is full".
- Change the drive password on the router: "The drive refused the password".
- Schedule: set Daily a few minutes ahead with Only while charging on and the phone unplugged: nothing happens;
  plug in: it starts. Weekly Sunday 2:00 am is the default on a new install.
- Scheduled failure (router off at the time): one retry an hour later, then the "Backup didn't finish"
  notification with the reason; tapping it opens Backup.
- Use mobile data asks first. On mobile data with it off, Back up now says it will wait for Wi-Fi.
- Storage saver on: a 50 MP or 200 MP photo arrives much smaller, rotated correctly, with date and location.
- Your phones: each of your phones with its last backup time. Restore: delete a backed-up photo on the phone,
  Check the drive shows 1, Restore brings it back into "Pixallery Restored"; checking again shows 0.
- Reinstall the app (or a second phone of the same person): the first backup uploads nothing already there.

24 Oct fixes:
- Home screen name "Pixallery" (preview build "Pixallery Preview"); no "Veyra" or "PhotoVault" text in the app
  (the drive folder default stays "PhotoVault" so existing families still match).
- Opening animation: on a fresh start the icon's pieces spiral in and join (about a second), matching the chosen
  icon; not when returning from the background; none with reduce motion.
- Photos: no cloud icon; Day / Month / Year pill at the top left; scrolling down hides both pills and the
  bottom bar, scrolling up / tapping Photos / selecting brings them back. Same for Albums (bar only).
- A new install starts on Transparent; someone who picked a theme keeps it.
- Family > People: tidy rows, "Counting..." while measuring, real phone name (e.g. Galaxy S26 Ultra), Change
  space on its own line, profile pictures (own without PIN; owner sets anyone's with the PIN).
- Owner phone: a dot on Family while a phone waits to join; it goes once approved or declined.
- Joining with a different folder name: "No family found in this folder" with the folder names, before Create.
- Settings > Drive shows the shared folder and the Pixallery folder on separate lines. Drive setup: Need help tips.
- Tablet: compact see-through side menu labelled Pixallery; Settings > Appearance > Menu at the bottom moves it
  to a floating bottom bar (that tablet only).

## Phase 4 device checks: second round of fixes (24 Oct, from testing)

- Tablet, Transparent theme: the side menu runs top to bottom with no app name, and looks like the same clear
  glass in Photos, Albums, Backup, Family and Settings (never white). Same for the bottom bar when moved there.
- Video: once it plays, the filmstrip and action bar go away; they come back after leaving the video.
- Backup: one compact card of rows. Tap Schedule (shows e.g. "Sun · 2:00 am · charging") for the schedule window;
  tap Folders for the folder list. History shows the last 3. Drive total only on the owner's phone.
- Family: "Family members" as pictures with a space bar under each (owner sees all; others only their own).
  Tap a picture: the person's page with a big picture, the full bar with "x of y GB" and "z left", phones,
  Change space and Remove (owner). Tap the big picture: Add picture, or Change / Remove; then move and zoom it
  in the circle before saving. Buttons never wrap.
- Family settings (PIN, approving phones, space for new members) are in Family, owner only, and no longer in
  Settings. Drive space card only for the owner.

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
(shrinker) builds, arm64 preview APK as an artifact. Since Phase 3 a throwaway Samba share
(`tools/start_test_share.sh`) runs before the unit tests, and the "Report test counts" step shows how many
real-share tests ran, so a skipped run can't pass silently.
