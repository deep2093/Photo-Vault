# Pending fixes (collected while testing, built together in the next build)

Added 23-24 Oct 2026 during Phase 4 testing. Ticked items were built on 24 Oct with the Phase 4 backup engine
(answers that day: Day / Month / Year as three small buttons; hide on scroll in Photos and Albums; Transparent
only for people who never picked a theme; profile pictures: each person their own, the owner anyone's; bottom
bar option on tablets only; short setup tips now; videos off by default).

- [x] Remove the cloud icon from the Photos pill: Backup is already in the navigation bar.
- [x] Every card that still shows sample data is labelled "Sample" (Backup: progress, schedule, folders, "Your phones"). Done by removing it: Backup shows only real figures now.
      Applies from now on to any screen that shows made-up data.

- [x] Photos: Day / Month / Year moves to its own pill at the top left, opposite the pill with search and ⋮.
- [x] Photos: scrolling down hides both top pills and the bottom navigation bar; scrolling up (or tapping Photos
      again, or selecting photos) brings them back. Same as Samsung Gallery.

- [x] Backup folders: only Camera is selected by default; the user can add any other phone folder (Screenshots,
      WhatsApp Images, ...) from a picker listing the phone's albums. Saved per phone.

- [x] Join requests (decided 23 Oct): no push notifications and no Firebase. When the app is opened (and while it is
      open) it checks the drive; a dot shows on the Family tab while a join request is waiting, and goes away once
      the owner has approved or declined it. Owner's phone only.

- [x] Rename the app to **Pixallery** (decided 23 Oct): home-screen name (was "Veyra Photos", preview build
      "Pixallery Preview"), and replace "Veyra" / "Veyra Photos" with "Pixallery" everywhere it appears in the app
      (screens, settings, About, strings, icon labels). Repo, package and code names stay PhotoVault for now;
      credits to the Veyra app's code in docs stay as history.

- [x] Tablet side menu: the "PhotoVault" label at the top becomes "Pixallery" (name from the rename item above).
- [x] Tablet side menu: more compact, and see-through like it floats on a sheet of glass in the Transparent theme.
- [x] Tablet option (Settings > Appearance): move the navigation from the side to a floating bar at the bottom
      centre, as on phones. Side stays the default on tablets; saved per device.

- [x] Default theme becomes Transparent (was Aurora Frost) for everyone. Open question: also switch people who
      already picked a theme, or only new installs and people who never chose one?
- [x] Family > People card layout after setup is messy: tidy the person row (name, phones, space bar, "x of 100 GB",
      Change space button, phone name) so nothing overlaps or floats. Show "Counting..." instead of "..." while
      the used space is measured, and show the phone's real model name.

- [x] Joining on a second device: when the chosen folder has no family, warn first ("No family found in this folder. If
      you already set one up on another phone, check the shared folder and PhotoVault folder names match") and put
      Create family behind it. Show the folder name clearly in Settings > Drive for comparing devices.
      (Tablet landed on Create family because its folder name differed from the phone's, 23 Oct.)

- [x] Family > People: redo the layout so each person is a clean row (see the People card item above), and add profile
      photos: a person can pick a photo for themselves (their own phone), shown in a circle in place of the letter,
      and the owner can set one for anyone. Stored as a small cropped picture (about 256 px) on the drive next to the
      family file, so it appears on every phone. Open question: who may change a photo, see the question to the owner.

- [ ] Invites (later, not this build): see ROADMAP.md. Invite file + QR locked with a code, one-use, expires in days,
      cancel before use; after Phase 9. Play Store link version at release. The owner attaches a per-person
      WireGuard config made on the router (the app cannot create router users).

- [ ] In-app "How to set this up" guide (Phase 13, see ROADMAP.md): drive, WireGuard on the router, importing the config,
      adding family. Reachable from the drive setup screen.

- [ ] (Phase 5) Plan change (24 Oct): Remote access (old Phase 9) and Invites are merged into Phase 5 so 1-2 other people can
      join and see the library sooner. WireGuard rules: connects only while the app is on screen; disconnects as soon
      as the app is minimised, left or closed; Settings options "Use VPN for this app only" and "Connect
      automatically". Decided: per-phone toggle "Back up away from home in background" (off by default) lets scheduled backup start the VPN by itself and stop it after.

- [x] Storage saver (decided 24 Oct, Phase 4): see ROADMAP.md. Photos only, per phone, off by default; videos never
      compressed; warning pop-up before Free up space removes an original when it is on.

- [x] App opening animation (asked 24 Oct): the pieces of the current app icon (Four Seasons: 4 leaves; Bloom and
      Leaf: 5 petals and the stem; Petals: 4 rounded tiles) fly in from all sides of the screen in a spiral and join
      into the icon, then the app appears. Follows the chosen icon. Starts from Android's own splash (it always shows
      the plain icon for a moment first). Respects reduce motion (no animation). Decided 24 Oct: about 1 second;
      plays only on a fresh start (not when coming back from the background); no tap to skip.

(More bugs from testing go here.)
