# Pending fixes (collected while testing, built together in the next build)

Not built yet. Added 23 Oct 2026 during Phase 4 testing.

- [ ] Remove the cloud icon from the Photos pill: Backup is already in the navigation bar.
- [ ] Every card that still shows sample data is labelled "Sample" (Backup: progress, schedule, folders, "Your phones").
      Applies from now on to any screen that shows made-up data.

- [ ] Photos: Day / Month / Year moves to its own pill at the top left, opposite the pill with search and ⋮.
- [ ] Photos: scrolling down hides both top pills and the bottom navigation bar; scrolling up (or tapping Photos
      again, or selecting photos) brings them back. Same as Samsung Gallery.

- [ ] Backup folders: only Camera is selected by default; the user can add any other phone folder (Screenshots,
      WhatsApp Images, ...) from a picker listing the phone's albums. Saved per phone.

- [ ] Join requests (decided 23 Oct): no push notifications and no Firebase. When the app is opened (and while it is
      open) it checks the drive; a dot shows on the Family tab while a join request is waiting, and goes away once
      the owner has approved or declined it. Owner's phone only.

- [ ] Rename the app to **Pixallery** (decided 23 Oct): home-screen name (was "Veyra Photos", preview build
      "Pixallery Preview"), and replace "Veyra" / "Veyra Photos" with "Pixallery" everywhere it appears in the app
      (screens, settings, About, strings, icon labels). Repo, package and code names stay PhotoVault for now;
      credits to the Veyra app's code in docs stay as history.

- [ ] Tablet side menu: the "PhotoVault" label at the top becomes "Pixallery" (name from the rename item above).
- [ ] Tablet side menu: more compact, and see-through like it floats on a sheet of glass in the Transparent theme.
- [ ] Tablet option (Settings > Appearance): move the navigation from the side to a floating bar at the bottom
      centre, as on phones. Side stays the default on tablets; saved per device.

- [ ] Default theme becomes Transparent (was Aurora Frost) for everyone. Open question: also switch people who
      already picked a theme, or only new installs and people who never chose one?
- [ ] Family > People card layout after setup is messy: tidy the person row (name, phones, space bar, "x of 100 GB",
      Change space button, phone name) so nothing overlaps or floats. Show "Counting..." instead of "..." while
      the used space is measured, and show the phone's real model name.

- [ ] Joining on a second device: when the chosen folder has no family, warn first ("No family found in this folder. If
      you already set one up on another phone, check the shared folder and PhotoVault folder names match") and put
      Create family behind it. Show the folder name clearly in Settings > Drive for comparing devices.
      (Tablet landed on Create family because its folder name differed from the phone's, 23 Oct.)

(More bugs from testing go here.)
