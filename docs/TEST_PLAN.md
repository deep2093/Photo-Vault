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

- Tablet, Photo Light theme: the side menu runs top to bottom with no app name, and looks like the same clear
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
- Photos: Years / Months / All pill. Years: a big card per year (photo from around today's date that year).
  Tap a year → its months; tap a month → All at that month. Spread two fingers to go in, pinch to come out
  (All at the smallest photos → Months → Years). Each change zooms; instant with reduce motion. Years ← Months
  keeps its place.

## Phase 4 device checks: third round (24 Oct, 22 points)

- Long-press a photo: the navigation bar rolls down and Share / Favourite / Delete rise in at the bottom; the top
  shows only "N selected" and ✕. Same in albums and Recently deleted (Restore / Delete forever at the bottom).
- Bottom bar: Photos | Albums | Family | Settings on phone and tablet (no side menu). Settings > Backup opens Backup;
  a backup notification opens it too.
- Settings > Albums: switch an album off → pop-up says it will be hidden from Photos and Albums → it disappears
  from both and from search; switch it on again to bring it back. Backup still includes it.
- App icons: Four Seasons, Leaf, Petals only; a phone that used Bloom moves to Four Seasons on the same background.
  The note under the icons is gone.
- Storage saver: "Photos compressed to 16 MP. Good-quality prints up to 24 x 16 in.", new icon.
- Backup: Schedule window has rolling hour / minute / AM-PM wheels with a tick on each step; Your space removed.
- Themes: new Crystal (plain light grey, white cards, clear glass bars). Transparent / adaptive themes keep the
  colours from Albums in Backup, Family and Settings. Adaptive Light: Recently deleted / Places / Secured folder
  tiles let the colours through.
- Video: no flash on Play (the still stays until the first picture). Tap shows the bar (play/pause, time, mute)
  and the frame strip; drag the strip to move through the video; while playing it all hides after 3 seconds.
- Photos pills smaller; the ⋮ button is gone.
- Family: under each picture only the name. Tap someone else's picture: "Only you will see this picture"; your
  picture of them shows on your phones only, everyone else still sees the family picture.
- Albums: the three tiles line up at the same height on phones and stay small on tablets; pinch to make album
  cards bigger or smaller (phone and tablet).

## Phase 4 device checks: fourth round (24 Oct, 12 points)

- Open and close a photo or video from an album: no glimpse of another screen (the "flash").
- Playing video: the photo strip stays; tapping the video's own thumbnail opens its frame strip (about 3/4 of the
  width) to scroll through it.
- Settings: About, Albums, Appearance, Backup, Drive, Storage (alphabetical), each opening its own page.
- Album page: rename (not Camera, Screenshots, Screen recordings, Download) after Android's permission prompt;
  delete moves everything to Recently deleted after Android's prompt.
- Settings > Albums: compact rows with an eye, hidden ones grouped, search when there are many; Favourites and
  Videos cards can be hidden.
- Settings > Storage: Phone / Drive; usage bar, file-type ring, by-year bars, biggest albums.
- Small vibration on buttons, switches, chips, tabs, pinch steps and the time wheels.
- Schedule weekly: several days can be picked.
- Photos, albums and search (23 Oct): photos run edge to edge with thin gaps and square corners; dates, the
  search box and notes keep their margin; the slider floats over the photos and still drags; slide-select picks
  the right photo, including in the first and last columns.
- Tap between sections fast (Photos ↔ Settings, back and forth, several times): slides stay smooth, never stop
  halfway between two sections and never land on a section you didn't tap. Family still updates while open.

## Phase 4 device checks: fifth round (23-24 Oct, 12 points)

- Open the app away from home Wi-Fi (or with the drive off): it opens normally (crash fixed 24 Oct).
- Drive moved to another address (e.g. .71.250 to .71.249) while the phone is home: the app still connects and
  Settings > Drive shows the new address. On another Wi-Fi or mobile data: no search, just "can't reach".
- Settings: Albums, Appearance, Backup, Drive, Storage, then Send feedback, then About last. Each page slides in
  from the right while the bottom bar rolls down, and slides back out to the right at the same speed.
- New install or never-chosen icon: Leaf on Light on the home screen.
- Photo viewer: tapping hides the buttons and the status and navigation bars together; tap again brings all back.
- Family: owner PIN then a space choice: the PIN box closes smoothly after the keyboard; the space dialog closes
  at once; the default-space chips change instantly.
- Owner's new phone: choose the owner's name and enter the owner PIN: joins as owner at once; can remove the old
  phone. Wrong PIN: refused. No PIN with approval off: only asks to join.
- Video: pressing play doesn't make the picture jump; the frame strip opens out of the thumbnail with the
  video's picture in each box until frames load; closing a playing video shrinks it smoothly with the page.
- Delete in the viewer, in a selection, deleting an album, Delete for good: after Android's prompt the photos
  shrink into the delete button; cancelling the prompt keeps them and the selection.
- Recently deleted: tap a photo to open it, with Restore and Delete for good; long-press still selects.
- Settings > Storage, Phone and Drive: both show a ring; tapping a ring slice, a legend row, a year bar or an album
  opens its files. Sort Name / Date / Size; tapping the active one flips the arrow and the order. Long-press and
  slide selects; Select all / Deselect all; Back clears the selection. Phone: share, delete (flies to the bin),
  tap opens the viewer. Drive: Save to phone (appears in "Pixallery Restored"); Delete from drive warns, then the
  files go from the list and the drive; a deleted photo still on the phone backs up again next time.
- Send feedback: Feedback or Bug, text, up to 5 photos or videos (warns over 300 MB). At home: "Sent"; the owner
  sees it under Feedback received with who, when, phone and attached names. Away: "Saved on this phone", and it
  goes by itself with the next drive check. After a crash: the page offers to add the details to a bug report.

## Phase 4 device checks: sixth round (24 Oct, 35 points)

- Test APK is now an optimised build: first opening of Settings pages and other screens is as smooth as later
  ones; keyboard slides smoothly; installs over the old one.
- GIFs and animated WebP move in the viewer. Settings > Drive > Find drive lists the drive at .71.x while the
  phone is on .68.x. Frosted and Crystal themes gone (someone on them moves to Transparent).
- Albums: press and hold a card, slide to pick more; bar has Share, Rename and Cover (one album), Delete; nav bar
  rolls down; Back clears. Cover: pick 1-4 photos, mosaic shows; Use newest resets.
- Settings > Albums: one list in Albums order; press and hold, drag to move (Albums follows); the eye hides with
  no pop-up and the row fades grey, tapping again brings it back.
- Inside an album: no top bar; big name at the top, small round back button, tiny count under the last photo.
- Delete anywhere: first time, "Nicer delete pop-ups?" offers Android's manage-media setting. Allowed: Pixallery's
  own pop-up in the theme ("Move this photo to the bin?", "Move these 5 photos to the bin?"), a back swipe
  cancels, no Android pop-up. Not allowed: Android's pop-up as before. The photo then pinches from the bottom and
  stretches into the delete button.
- Schedule: Save is blocked within an hour of another family phone (their time zone counted); "Use 3:00 am"
  offered; other phones' times listed. Two phones starting together: the second shows "Waiting for ... to finish",
  then backs up; the first person's picture in Family shows a soft pulsing dot meanwhile.
- New backups land in users/<id>/albums/<album>/YYYY/MM on the drive.
- Family > your page: phones with last backup time; Manage my backup opens Storage on Drive. Settings > Backup no
  longer has Your phones. Change space shows only for the owner (already so; check as a member).
- Pull down at the top of Photos: spinner under the pills, library and drive refresh.
- Storage saver row: icon lines up with the title, shorter text.

## Phase 4 device checks: seventh round (24 Oct, 8 points)

- Delete: the photo stays one piece, pinches at the bottom and pours into the bin like a genie into its bottle.
- Settings pages slide like Samsung's: the new page in from the right while the one under it drifts left; back
  reverses it at the same speed.
- Storage > Drive: owner sees yours / rest of the family / free on the drive (no "Other files") and the drive's
  total; a member sees their backup, the rest of the family as one total, and what's left of their space.
- Member's own page: Ask for more space (amount + note), then Waiting / Cancel. Owner: Space requests card in
  Family (give or decline, owner PIN), dot on Family, and within about 3 hours a phone notification with a dot on
  the app icon, but only while on home Wi-Fi.
- Recently deleted: tapping a photo opens it (Restore / Delete for good).
- GIF tiles show a small GIF badge in Photos and albums.
- Keyboard slides smoothly; Search on the keyboard closes it; Done closes it in text fields.
- Photos: Years / Months / All and Search stay on screen while scrolling.

## Round 8 (24 Oct): sliding pages

- Tap Settings, Backup, an album, Search or Recently deleted: the page slides in from the right edge like a door, the
  page under it drifts left and darkens, a soft shadow runs down the moving page's edge. The photo viewer still grows in.
- The back arrow plays the same movement backwards at the same speed.
- Android back gesture (swipe from the screen edge): the page follows the finger exactly. Stop half way and hold: the
  page stops there. Let go past half: it finishes; let go before: it slides back. Nothing flickers when it finishes.
- Same on a page opened from another page (Settings > Storage > a file list): the page under it appears as you pull.
- The bottom bar rolls back up as you pull a page off a section.
- With Reduce motion on, pages simply appear and disappear.
- Needs Android 14+ for the pull-along; on older phones or three-button navigation the page closes with the normal slide.

## Round 8b (24 Oct, night)

- Opening Settings, Backup, an album, Search...: the page comes in from the LEFT edge sliding right; leaving, it goes back out to the left. No flash of the page after it has gone (the flicker in the 10:27 recording).
- Back swipe from the right edge pulls the page left, from the left edge pulls it right; it holds where the finger stops; finishing leaves no flash.
- Years / Months / All and the Search button are smaller.
- Settings > Appearance: Light themes, Dark themes, Photo themes. Aurora Frost and Transparent are gone; an old choice of either becomes Photo Light.

## Round 9 (24 Oct, night): lens glass

- Settings > Appearance > pick Clear Light or Clear Night: a "Lens glass (experimental)" switch appears (Android 13+ only; hidden on other themes and on older Android).
- Switch on: the bottom bar shows the photos behind it bent and magnified toward its rounded edges, lightly blurred; labels still easy to read over a very bright and a very dark photo.
- Scrolling Photos stays smooth with it on (watch for stutter); battery saver or Glass blur off turns it off by itself.
- Switch off: the normal blur returns.

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

## Round 10 (25 Oct): Phase 5A
1. **One timeline:** back up a few photos, delete them from the phone, empty the phone's Recently deleted. They should still show in Photos and in their album (offline too, thumbnails once seen). Tap one: it opens; a video downloads first, then plays.
2. **Pull down on Photos** (top, All view): new drive photos appear after a backup from another phone.
3. **Delete a drive-only photo:** it goes to Recently deleted ("Deleted for good after 30 days"); Restore puts it back in the same album; Delete for good removes it from the drive.
4. **Share a drive-only photo:** "Getting the file from the drive…" then the share sheet.
5. **Family Album:** Albums shows a "Family Album" card first (only in a family). Select backed-up photos, tap the family button; also from the viewer. A second phone sees them. You cannot remove someone else's photo. Photos not yet backed up say so.
6. **What's new / Welcome tour:** a fresh install shows the tour once; an updated install shows What's new once. Both reopen from Settings > About.
7. **Storage rings** sweep in when Storage opens.

## Round 11 (26 Oct): Phase 5B-1 Remote access (needs your TP-Link router with WireGuard)
1. Settings > Remote access: "Import WireGuard file" with a .conf from the router. A bad file gives a plain message.
2. Tap Connect: Android asks once to allow a VPN; allow. Status shows Connected and "Your drive answers through the VPN" (on mobile data away from home).
3. Leave the app (home button): the VPN key icon goes away within seconds. Return: with "Connect automatically" on it reconnects; off, it stays off until you tap Connect.
4. At home on Wi-Fi with automatic on: it should not connect (the drive is already reachable).
5. "Use VPN for this app only" on: other apps (browser) are unaffected while connected.
6. "Back up away from home in background" on, on mobile data, backup due: the VPN turns on, backup runs, VPN turns off. If you never allowed the VPN, the backup says to open Remote access and tap Connect once.
7. Remove VPN setup clears it. Unverified so far: all of this needs a real router and phone.

## Round 12 (25 Oct): 5B-1 follow-ups
1. Ask for more space offers +25, +50, +100, +200 GB and +1 TB on top of what you have; the owner sees "asks for N GB more".
2. Welcome tour: the Next button is a smaller, centred button. After the tour (new installs only) a "Let's set up backup" pop-up leads through photo access, notifications, then the background-running setting.
3. Settings pages in Clear Light with lens glass: no pale band across the top bar title.
4. Owner: Settings > Send feedback > "Clear" removes all received notes and their files (asks first).
5. Open Settings > Drive (or an album), switch to another app, rotate or change the font, come back: the same page is open.
6. Settings pages slide in more slowly.
7. Lens glass: the bottom bar and the photo viewer toolbar both look through to the photo; tuck the bar away by scrolling and bring it back: the glass still lines up.
8. Backed-up files list (Settings > Backup > Drive): thumbnails show, a tap opens the photo, press and hold selects. "Save to this phone" on cloud photos (selection bar and viewer).
9. A member (not the owner) sees "Leave family" on their own page. Their photos stay on the drive.
10. A phone joining the family gets its own backup time (2:00, 2:30, 3:00 ...); change it by hand any time.
11. Owner: when someone sends feedback or a bug report, a dot appears on Settings (checked every minute while the app is open); opening Settings > Send feedback clears it. Every 3 hours in the background the owner's phone also posts "New bug report" or "New feedback" (needs notifications allowed and the drive reachable).
12. After the tour, the "Let backups run on time" screen shows four steps and opens Pixallery's page in Android settings.
13. Family Album: press and hold the card > Change cover. Each person's cover is their own.

## Round 13 (25 Oct): 5B-2 Invites and Open with
1. Owner: Family > Invite someone: pick who and how long, optionally attach the WireGuard file for that person, Make invite (asks the owner PIN). A code like K7QD-M2XP shows, with Share invite file and a QR.
2. Second phone (not set up yet): Settings > Drive (or Connect) > "I have an invite": Choose file (or Scan QR), type the code and your name > Join. It joins at once, even with approval on, and gets its own folder named after you. With a WireGuard file attached the VPN is set up too; away from home turn it on in Settings > Remote access, then try again.
3. Using the same invite on a second phone fails ("already used"); after the time is up it fails ("run out"); the owner's "Take back" makes it fail ("taken back"); a wrong code says so and nothing is joined.
4. The invite text shows no address or password if opened in a text editor.
5. Open with: in Files, tap a photo or video: Pixallery is in the list. It opens in Pixallery's viewer; a photo from your gallery lets you swipe through its folder, one from another app shows just that one.
6. Settings > Open with Pixallery explains and opens Android's default apps settings.

## Round 14 (25 Oct): invite files, join pop-up, fixes
1. Owner phone open on any screen; a second phone asks to join (approval on): a pop-up with a buzz appears with Approve, Decline, Later. Approve asks the PIN if locked.
2. Make an invite with "Lock with a code" off: no code shows; the joiner is not asked for one and joins once.
3. Share the invite file (WhatsApp or Files), tap it on the second phone: Pixallery should be in "Open with" and open the join screen with the invite loaded. (Android and WhatsApp decide the list; if Pixallery is missing, use Choose file, Paste or Scan QR.)
4. "Paste" on the join screen takes an invite copied with "Copy text".
5. What's new appears after the opening animation, not over it.
6. Enter and leave a Settings page very quickly several times: the slide animation always plays.
7. Storage: the ring starts empty and the whole ring fills round.
8. About shows Done items and Phase 6, 7, 8.
9. Header line: Settings > About > "Header test", tap to change mode, open a page (e.g. Open with) and see which mode removes the pale line. Tell me the mode number.

## Round 15 (25 Oct): gap fixes and Phase 4A (keys and recovery; no encrypted uploading yet)
Automated (run on every build): 16 crypto tests, including the public 24-word test vectors, tamper and wrong-key cases, seeking, identity and helper keys, and a full "fresh phone" recovery.
1. Open a photo from WhatsApp or Files in Pixallery: the bar shows only Share, Save (copies it to Pictures/Pixallery) and Details. No Delete, no favourite.
2. Join with an invite that has a WireGuard file, away from home (Wi-Fi off): Android asks "Allow VPN?" once, the VPN turns on by itself and the join completes.
3. Backup now before choosing: it says "Choose how your backup is kept first". Settings > Backup shows a "Backup privacy" card.
4. Choose Normal: backups run as before.
5. Choose Private > Make my key: 24 words appear only after "Show my words". "Save to password manager" may be accepted or declined by your phone. Check asks for 3 of the words (wrong word is refused). Finish writes keys to the drive (Pixallery/users/<you>/keys/): a recovery-v1.bin of random-looking bytes and identity-v1.txt of public keys only; open them in a text editor on a PC: no words, no key.
6. (Replaced by Round 16: Private now uploads encrypted.)
7. Second family member also sets up Private, then in your setup the "recovery helper" step lists them. Choosing one writes helper-<id>.bin. (Handing a key back through the helper is not built yet.)
8. Wipe the app data (or use another phone), join again, Backup > "Restore my key": typing your 24 words brings the key back; a wrong or misspelt word gives a plain message.
9. Change to Normal from the choice screen: a warning appears first.

## Round 16 (25 Oct): Phase 4B, encrypted backup
Automated (run on every build): 5 tests with a pretend drive: nothing readable lands on it (no file names, sealed manifests), restore needs the key, a changed file is never restored, someone else's key fails the check, trash and delete-forever keep names hidden and clean up thumbnails.
Not testable here: the real router, the phone's Keystore, the speed of encrypting large videos. Please test these on the device.
1. Choose Private (new key). Backup now with a few photos and one video. It finishes; the Backup screen shows them as backed up.
2. On a PC, open the USB drive > Pixallery > users > your folder. You should see only objects/, thumbs/, manifests/ with random-looking names (.pxo, .pxt, .pxm). No photo opens, no file name or date is readable.
3. In Photos on the phone, delete one backed-up photo from the phone. It stays in Photos (from the drive) with a normal thumbnail; tap it: it opens. Do the same with the video: it shows a thumbnail and plays.
4. Settings > Storage lists your Private photos with their real names and sizes.
5. Delete one from Storage (it goes to Recently deleted on the drive). On the PC the trash folder shows only random names. Restore it: it comes back. Delete forever: file and thumbnail disappear.
6. Restore to a new phone (or wipe the app): join, Backup > Restore my key with the 24 words, then restore. Photos come back with the right bytes. Without the key, Backup says the phone doesn't have your private key.
7. Family Album: choosing a Private photo says Private photos can't be shared yet.
8. Large video (over 500 MB) backs up without the app running out of memory. Tell me how long it took.
9. Someone with Normal still backs up as before, and the two people's folders do not mix.

## Round 17 (26 Oct): fixes and small changes
1. Photos > scroll down, hold one photo: only that photo is selected; swiping then selects more.
2. Open an album, scroll down, open a photo, go back: the album is where you left it.
3. Years > a year > a month, then Back: month, Months, Years, day view, then the app closes.
4. Storage > Drive, open Recently deleted on the drive and a photo, go back: still on Drive. The button is at the bottom of the Drive tab.
5. iPhone JPEG photos that are only on the drive: the thumbnails are sharp (the first time may be a little slower).
6. Backup has no History card. Settings ends with Open with Pixallery, Send feedback, About.
7. Storage rings show Photos, Videos, PNG and GIF only; Sort bar has Type; every video format counts as Videos.
8. Appearance: Leaf and Petals only (Petals is the old Four Seasons).
9. In Photos, scrolled far down, tap Photos on the bottom bar: one smooth glide, no pause.
10. What's new shows New, Changed, Removed and Fixed.

## Round 18 (26 Oct): wallpaper, move to album, storage, thumbnails, scroll
1. Open any photo (on the phone, and one only on the drive): three dots at the top right. Menu: Set as wallpaper, Details, Move to Secured folder (Soon). Videos have no wallpaper row. Details is no longer on the bottom bar.
2. Set as wallpaper: the pop-up asks Home screen, Lock screen or both; check the result on each. A portrait photo is upright.
3. Open an album, select photos: an album button appears. Tap it: pick an album or New album; Android asks once; the photos move and the grid updates.
4. Storage > Phone: the ring has Photos, Videos, Everything else, Free. Tapping Photos or Videos opens their files; Everything else explains and opens Android's storage page.
5. iPhone JPEGs that are on the phone are sharp in the grid.
6. Photos scrolled far down, tap Photos on the bottom bar: one smooth glide to the top without stopping.

## Round 19 (26 Oct): app cache, 3D rings, compact menu, saved thumbnails
1. Storage > Phone: ring shows Photos, Videos, App cache, Free. Tap App cache: dialog with size; Clear cache empties it; the ring updates; drive photos still open.
2. Settings > Appearance > Charts with depth: on gives thick shaded 3D rings, off gives flat rings. Both sweep round when the page opens (not with Reduce motion). Tapping a part of the ring still opens it.
3. Photo viewer: the three dots are the same size as the Search button; the menu is narrow and compact.
4. iPhone JPEGs (phone and drive): sharp, and scrolling back is instant the second time (saved). App cache does not remove them being rebuilt later, only time.
