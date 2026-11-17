# Pixallery release checklist and action plan

Written 16 Nov 2026 from a full release audit of the code, build files, manifest, privacy policy and roadmap notes.
This file is the single list of what is left before an official release.

**How to use it.** When Deepak asks "what's left", read this file first. Tick an item (`[x]`) in the same change
that finishes it, with the date and the branch. Add new loose ends here as they are found. Never tick an item
that was only built: an item is done when it was also tried on a phone, unless it says "paperwork".

**Who.** Claude = code or text Claude can write. Deepak = needs Deepak's own accounts, phone or decision.
Both = Claude prepares, Deepak approves or carries out.

**Status on 17 Nov 2026.** 1 of 53 items done (E1). `feature/cleanup` tried on a phone and merged into `main`.
Stage 0 is finished apart from Deepak's housekeeping (D17). Stage 1 is answered apart from two things waiting on
Deepak: the new support address (B7) and whether the Private DNS notice still shows (D9).

---

## Part 1. What the audit found

### A. Can hurt a user (fix before anyone outside the family uses it)

- [ ] **A1 · Recovery helper is half-built.** Who: Claude builds. Decided 17 Nov: finish it (stage 2).
  Private setup lets a person pick a family member as "recovery helper" and saves a key for them on the drive, but
  no screen lets the helper hand the key back. Someone relying on it could lose their backup. Finish it, or hide
  the option until it is finished.
- [ ] **A2 · Google PIN is too easy to guess.** Who: Claude.
  A Google-only Private backup is locked by a PIN of 6 or more characters; six digits is allowed. Anyone inside the
  Google account can copy the locked key and try every 6-digit PIN on a fast computer in hours. Require a longer
  passphrase (or many more digits), show its strength, and let existing users change it.
- [ ] **A3 · Privacy policy says the wrong thing.** Who: Claude writes, Deepak approves.
  It says Private backups can be opened by "members of your family". In the app only the person themselves can.
- [ ] **A4 · Privacy policy leaves things out.** Who: Claude writes, Deepak approves.
  Face matching (People); the offer to save recovery words to the phone's password manager; the cut-out model
  Google Play services downloads; the optional face model from Hugging Face; the VPN.
- [ ] **A5 · The real release build has never been installed or run.** Who: Both.
  Every build installed so far is the "preview" type, which keeps code names readable. The release type scrambles
  names, which can break saved settings, encryption and Google sign-in. The automatic checks do build an unsigned
  release each time (so it compiles and shrinks), but nobody has run one. Sign it and run the whole test plan on it.
  (Corrected 16 Nov: the first version of this list wrongly said it had never been built.)
- [ ] **A6 · No release signing key, no release Google sign-in.** Who: Deepak (accounts), Claude (build set-up).
  A release key must be made and kept safe outside the repository. Google sign-in needs a second client in the
  Google Cloud project tied to the release package name and that key.
- [ ] **A7 · Private-to-Normal leftovers after an interruption.** Who: Claude.
  One unscrambled photo can be left in the app's private storage, and a few scrambled files can be orphaned on
  the drive. Clear the phone folder at the start of every run; remove private files already recorded as converted.
  Built 17 Nov on `feature/cleanup` (with tests), waiting for a phone test: turn the drive or Wi-Fi off part-way
  through a conversion, then let it carry on.
- [ ] **A8 · Unscrambled copies kept on the phone.** Who: Claude.
  Private drive-only photos that are opened, Secured folder items being played or shared, edits and trims are
  written unscrambled into cache folders. Check each folder: give it a size limit and a guaranteed clean-up
  (when the app starts, and when the Secured folder locks). Built 17 Nov on `feature/cleanup`, waiting for a phone
  test. Found on the way: shared Secured folder copies and shared drive photos were never deleted; now after 15 minutes.
- [ ] **A9 · Moving each phone from the test app to the release app.** Who: Claude, then Deepak tests. (Added 17 Nov.)
  The family phones run the test app (`com.deepak.photovault.preview`). To Android the release app is a different
  app, whatever its package name, so nothing kept only inside the test app comes across: settings, the drive
  connection, the WireGuard set-up, the Private backup key on the phone, and possibly Secured folder photos (kept
  in the app's own folder, so uninstalling the test app deletes them). Claude: list everything that lives only on
  the phone, then add a way to carry it over (for example a locked export to the drive) or a plain
  "before you uninstall" guide. Must be ready before the first phone moves to the release app.

### B. Store rules (can block the Play listing)

- [ ] **B1 · VPN rule.** Who: Claude builds, Deepak fills in the form. Decided 17 Nov: declare it.
  Play's rule, read on 17 Nov 2026: only apps whose main job is a VPN may use one, with listed exceptions that
  include "network tools (for example remote access)". Play also asks for: a mention in the store listing; the
  data encrypted (WireGuard does this); an in-app notice that says what the VPN does and asks for a clear "yes"
  before first use, shown on its own (the privacy policy alone is not enough); a declaration form in Play
  Console with a demo video of 90 seconds or less; and never steering other apps' traffic.
  Plan: declare it as remote access to the person's own home drive. In the Play version, the tunnel carries only
  Pixallery's own traffic (today that is the default, but a switch lets the whole phone use it: remove the
  switch). Add the notice-and-yes screen before the first connection (stage 4). Keep a one-line way to build
  without the VPN, so a refusal in review does not hold up the launch (then the person uses the WireGuard or
  Tailscale app beside Pixallery).
- [ ] **B2 · Target Android version.** Who: Claude.
  The app targets Android 15 (level 35). Check Play's current minimum and move to Android 16 (36) if required;
  re-test edge-to-edge, back gesture and background limits after the move.
- [ ] **B3 · 16 KB memory pages.** Who: Claude.
  Required by Play for apps targeting Android 15 and up. OpenCV 4.10, WireGuard, MapLibre and LiteRT ship native
  code. Check each, update the ones that fail, and test on a 16 KB emulator.
- [ ] **B4 · Long backups.** Who: Claude.
  Android 15 stops a background "data sync" job after 6 hours a day. A first backup of a big library can pass
  that. Make the backup stop cleanly and carry on later, and tell the person. Play also wants this job type
  declared with a short demo video.
- [ ] **B5 · Declarations and forms.** Who: Both (paperwork).
  Photo and video permission form; "manage media" access; Data safety form; content rating; target audience.
- [ ] **B6 · Face data notice.** Who: Claude builds, Deepak approves wording.
  Face matching counts as biometric data in the EU and some US states. People needs a clear notice before the
  first scan, and its off switch must delete the face data (on the phone and the drive copy).
- [ ] **B7 · Support contact.** Who: Deepak makes the address, Claude adds it.
  The policy's only contact is "open a GitHub issue". Play needs a support email address. Decided 17 Nov: a new
  address just for Pixallery (Play shows it publicly). Waiting for Deepak to make it and send it.
- [ ] **B8 · App identity is permanent.** Who: Claude changes (stage 3). Name and version decided 17 Nov.
  The package is `com.deepak.photovault`, the version `0.0.1`, the app is called Pixallery. The package can never
  change after release. Decided 17 Nov: the name stays **Pixallery**, the package is **`app.pixallery`**, the
  first public version is **1.0.0**. Only the package (what Android and Play see) changes; the code's own folder
  names can stay as they are.
- [ ] **B9 · Closed testing first.** Who: Deepak.
  New personal Play developer accounts must run a closed test (currently 12 testers for 14 days) before
  production. Check the current rule when the account is made and line up the testers early.

### C. Legal and housekeeping

- [ ] **C1 · Public repository without a licence.** Who: Deepak (on GitHub), Claude prepares. Decided 17 Nov:
  make it private, later. Before that day: the website with the privacy policy must move to its own small public
  repository, because GitHub's free plan only runs websites from public repositories; and the automatic builds
  start counting against the free plan's 2,000 minutes a month (the pace since 1 Nov, about 96 builds of about
  11 minutes in 17 days, is close to that limit).
- [ ] **C2 · Third-party notices are incomplete.** Who: Claude.
  The file lists 3 of about 20: add OpenCV, Tink, Coil, Haze, MapLibre, ZXing, Media3, LiteRT, Bouncy Castle,
  SLF4J, kotlinx libraries, the YuNet and SFace models, AuraFace, GeoNames. Show them in the app (Settings >
  About > Licences) and show the OpenStreetMap / OpenFreeMap credit on the map.
- [ ] **C3 · Build downloads are not pinned.** Who: Claude.
  The face models and place names are fetched at build time and checked only by size. Check them against a
  fixed SHA-256 so a changed file upstream fails the build.
- [ ] **C4 · A beta library in the release.** Who: Claude.
  Google's subject cut-out library is a beta. Move to a stable release if there is one, or accept and note it.
- [ ] **C5 · Test signing key is public.** Who: Deepak decides.
  Fine for test builds, but anyone could make an "update" the test phones would accept. Acceptable while the
  repository is public only if testers install only from Deepak. Goes away when the repository is made private (C1).

### D. Unfinished or undecided features

- [ ] **D1 · AuraFace test model: keep or remove.** Who: Claude. Decided 17 Nov: remove it before release, after
  more testing (stage 5).
- [ ] **D2 · Tester feedback feature: ship or remove.** Who: Claude. Decided 17 Nov: remove it before release
  (testers use Play's own feedback). It sends notes and photos to the drive and checks every 3 hours.
- [ ] **D3 · Private-to-Normal for Google Drive copies and Recently deleted.** Who: Claude. Decided 17 Nov: not in
  version 1. Check the app says plainly that these stay private for now.
- [ ] **D4 · Private photos in the Family Album.** Who: Claude. Decided 17 Nov: not in version 1.
  Not possible today; say so clearly in the app where a person would try it.
- [ ] **D5 · Removed members keep the drive password.** Who: Claude.
  The app only suggests changing it. Add a guided "change the drive password" step that updates every phone.
- [ ] **D6 · Owner PIN limits said plainly.** Who: Claude (text).
  It stops casual changes only; someone with the drive password can get past it. Say so where the PIN is set.
- [ ] **D7 · Drive connection signing and encryption.** Who: Claude.
  Off by default. Add an option (or turn on when the drive supports it) and a note.
- [ ] **D8 · In-app set-up guide.** Who: Claude. Drive, WireGuard on the router, importing the config, adding family.
- [ ] **D9 · Private DNS notice.** Who: Deepak checks, Claude builds. Raised 30 Oct (Deepak uses an ad-block
  DNS); the details were only in an older chat. 17 Nov: Deepak will check whether the notice still shows when
  remote access is on. Claude's likely fix if it does: the tunnel stops setting its own DNS, since the app
  reaches the drive by number (.250).
- [ ] **D10 · People tuning.** Who: Both. Black-and-white photos, faces in videos, children, look-alikes.
- [ ] **D11 · Secured folder viewer dark theme.** Who: Claude.
- [ ] **D12 · Tablets and foldables (Phase 12).** Who: Claude. Animations everywhere, foldable layouts, two panes.
- [ ] **D13 · Old "not done" notes checked.** Who: Claude.
  Some older roadmap notes for the Secured folder and People say "not done", and one says moving from Photos to
  Settings can stick on Family while the drive answers. Check each against today's code and add anything real
  to this list.
- [ ] **D14 · Leftovers removed.** Who: Claude.
  8 retired app-icon entries (after checking no phone still uses them), one unused VPN flag, the last two
  copies of the bytes-to-text helper. `desktop/` stays.
- [ ] **D15 · Conversion and editor rough edges.** Who: Claude.
  Closing a zoomed photo jumps; the flying photo looks soft for a moment before the full one takes over; the
  status lines for Private-to-Normal are lost when the app closes; a trimmed video can start up to a second early.
  Added 17 Nov: Log videos are recognised by a guess (10-bit wide colour, no HDR tag); checked on a real Log video
  on 17 Nov and it shows "Log (probably)". Messages use Android's toast, which shows only two lines (an in-app
  message bar would not cut off).
- [ ] **D16 · A real depth model for living photos.** Who: Claude, later. Decided 17 Nov: not in version 1.
  Today's effect uses a subject cut-out only.
- [ ] **D17 · Deepak's housekeeping.** Who: Deepak.
  Delete the nine old, fully merged branches on GitHub (Claude's session is not allowed to); decide what happens
  to `codex/experimental-preview-glass`, `feature/experimental-preview-glass` and `round5-part2-wip` (they hold
  work that is not in `main`); set the family drive's address to .250 once on each phone.
- [ ] **D18 · Pixallery is offered for every unknown file.** Who: Claude. (Added on the recheck, 16 Nov.)
  So that invite files open, the app lists itself for all files of unknown type. Narrow it to invite files only,
  or it will look like a nuisance in "Open with" lists.
- [ ] **D20 · HDR photos, Save over the original, saved copies in the viewer.** Who: Claude built them, Deepak
  tests. (Added 17 Nov, Deepak.) HDR photos open in HDR (Android 14+); the editor has Save beside Save copy (the
  original goes to Recently deleted for 30 days); a saved copy shows in the viewer at once. Built 17 Nov on
  `feature/cleanup`, not yet tried on a phone.
- [ ] **D19 · Finding a drive that moved: a switch, explained during set-up.** Who: Claude built it, Deepak tests.
  (Added 17 Nov, Deepak.) "Find the drive if its address changes": on the set-up page and in Settings > Drive.
  Off for new set-ups, on for phones already set up. When off and the drive doesn't answer, the message says to
  type the new address or turn the switch on. Built 17 Nov on `feature/cleanup`, not yet tried on a phone.
  17 Nov (Deepak): text made much shorter and simpler; it says the search goes above and below (it always did:
  nearest first, either side, the whole .1 to .254 range). Built on `feature/cleanup`, waiting for a phone test.

### E. Quality and testing

- [x] **E1 · Today's work tried on a phone.** Who: Deepak. Done 17 Nov, `feature/cleanup`.
  Photo animation, straighten, undo and redo, HDR copy, video trim, family roles and removal, Private-to-Normal,
  the Backup privacy window, and the clean-up (saving edits, restores, Secured folder restores, Storage lists).
- [ ] **E2 · Full test plan on the release build.** Who: Both. Every feature, on a phone and a tablet (needs A5).
- [ ] **E3 · Accessibility pass.** Who: Both.
  87 pictures have no spoken description. Run TalkBack, the largest font size and high contrast through every page.
- [ ] **E4 · Battery and speed.** Who: Both.
  Six background jobs run every 3 to 24 hours. Measure a week of battery use; merge or slow the jobs if needed.
- [ ] **E5 · Hidden errors reviewed.** Who: Claude.
  46 places ignore errors on purpose. Check each one cannot hide a failed backup, restore or delete.
- [ ] **E6 · Old and new versions together.** Who: Claude.
  Test an older app version against a drive written by a newer one (and the other way) with real files.
- [ ] **E7 · On-device automated tests for the riskiest paths.** Who: Claude.
  A few tests that drive the real app: back up, restore, delete, free up space. 350 logic tests exist; none open
  a screen.
- [ ] **E8 · Languages.** Who: nobody for version 1. Decided 17 Nov: English only for version 1; move the text
  out of the code later, when a second language is wanted.
- [ ] **E9 · Crash reports.** Who: Claude. Decided 17 Nov: use Play's own crash reports, and add a "send to
  developer" button on the crash screen that the person chooses to tap (it emails the details to the B7
  address). Crashes today show only on the person's phone. The privacy policy must mention both (A4).
- [ ] **E10 · Android's own code checks: review what they let through.** Who: Claude. (Added 16 Nov, corrected 17 Nov.)
  The automatic build does run Android lint and stops on its errors (it caught one on 17 Nov). Still to do: read
  its warnings once and decide which should also stop the build (wrong-version calls, missing permissions).

---

## Part 2. Action plan

Seven stages, in order. Inside a stage, the items are done together because they touch the same code or the same
forms. A stage starts when the one before it is finished, except where "can run alongside" says otherwise.

| Stage | What | Who leads | Depends on |
| --- | --- | --- | --- |
| 0 | Test and merge today's work | Deepak | nothing |
| 1 | Decisions | Deepak | nothing (can run alongside 0) |
| 2 | Safety fixes | Claude | stage 0 |
| 3 | Release build foundations | Both | stages 1 and 2 |
| 4 | Policy, notices and licences | Claude drafts | stage 1 (can run alongside 3) |
| 5 | Remaining features | Claude | stage 3 |
| 6 | Full quality pass | Both | stage 5 |
| 7 | Play Console and launch | Deepak | stages 4 and 6 |

### Stage 0. Test and merge today's work

Why first: everything later is built on top of it, and nothing in it has run on a phone.

1. Deepak installs the latest `feature/cleanup` build and goes through **E1**.
2. Claude fixes what he reports, one build at a time.
3. When Deepak is happy, Claude merges `feature/cleanup` into `main`.
4. Deepak: **D17** housekeeping (old branches, the three experimental branches, the drive address).

Done when: E1 is ticked and `main` holds the work.

### Stage 1. Decisions (Deepak, about an hour, no code)

Why now: each answer changes what gets built later, and three of them can never be changed after release.

Answer these together, in one sitting:

1. **B8** final package name and first version number (permanent). Answered 17 Nov.
2. **C1** open source or private repository (also settles **C5**). Answered 17 Nov.
3. **B1** VPN in the Play version: declare it, or leave it out. Answered 17 Nov.
4. **A1** recovery helper: finish it, or hide it for version 1. Answered 17 Nov.
5. **D1** AuraFace: keep or remove. Answered 17 Nov.
6. **D2** tester feedback: ship or remove. Answered 17 Nov.
7. **D3**, **D4**, **D16** Google conversion, private photos in the Family Album, a real depth model: version 1 or
   later. Answered 17 Nov.
8. **E8** English only for version 1? Answered 17 Nov.
9. **E9** crash reports: how. Answered 17 Nov.
10. **B7** the support email address. Answered 17 Nov; Deepak is making the address.
11. **D9** the Private DNS question. Deepak is checking whether it still happens.

Done when: each of these has an answer written next to it in this file.

### Stage 2. Safety fixes (Claude, one or two builds)

Why before anything else is built: these are the only items that can lose or expose someone's photos.

Build together (all touch keys and temporary files):

1. **A2** stronger Google PIN, with a way to change an existing one.
2. **A7** clean up after an interrupted Private-to-Normal.
3. **A8** size limits and clean-up for every folder of unscrambled copies.
4. **A1** finish the recovery helper (decided 17 Nov).
5. **E5** review of ignored errors (same files, done while there).
6. **A9** carry-over from the test app to the release app (it touches the same keys and phone-only files).

Then Deepak tests: Private backup set-up on Google only, a conversion interrupted on purpose (turn the drive
off part-way), the Secured folder after locking.

Done when: A1, A2, A7, A8, A9 and E5 are ticked.

### Stage 3. Release build foundations (Both)

Why together: they all change the build, and each needs the others to be tested properly.

Order inside the stage:

1. Claude: **B8** apply the package name and version (first, because the signing key and Google client depend on it).
2. Deepak: **A6** make the release key (Claude gives exact steps), store it and its passwords somewhere safe
   outside the repository, and give the build access to it as secrets.
3. Deepak: **A6** add the release Google sign-in client in Google Cloud Console (Claude gives the values).
4. Claude: **B2** target Android version, **B3** 16 KB pages (library updates), **C3** pinned downloads,
   **C4** beta library, **D14** leftovers, **D18** offered only for invite files.
5. Claude: **A5** the release build in the automatic checks signed with the release key; **E10** Android lint in the same checks.
6. Deepak: installs the release build and does a short run through every section (the full pass is stage 6).
7. Claude: **B4** long-backup handling (needs the new target version to test against).

Done when: a signed release build installs, signs in to Google and backs up, and B2, B3, B4, C3, C4, A5, A6, B8,
D14, D18 and E10 are ticked.

### Stage 4. Policy, notices and licences (Claude drafts, Deepak approves; can run alongside stage 3)

Why together: it is all text about the same facts, and the store forms in stage 7 are copied from it.

1. **A3**, **A4** rewrite the privacy policy; publish it on the website.
2. **B6** face data notice in People, and the off switch deletes the data.
3. **C2** full third-party notices, a Licences page in the app, map credit.
4. **C1** licence file or private repository, as decided.
5. **D6** owner PIN wording.
6. **B7** support email on the website and in the app.

Done when: A3, A4, B6, B7, C1, C2 and D6 are ticked and Deepak has read the published policy.

### Stage 5. Remaining features (Claude, several builds, each tested by Deepak)

Why after the foundations: so each is built and tested on the release build, not redone later.

Suggested order (biggest risk first, look-and-feel last):

1. **D5** guided drive password change, **D7** connection signing and encryption (both touch the drive connection).
2. **D8** set-up guide, **D9** Private DNS notice (both are help and first-run pages).
3. **D3**, **D4**: not in version 1 (decided 17 Nov), so only the plain "not yet" wording in the app.
4. **D13** check the old "not done" notes; **D15** rough edges.
5. **D10** People tuning, **D1** remove AuraFace after more testing. (**D16** is not in version 1.)
6. **D11** Secured folder dark theme, **D12** tablets and foldables (last: they touch every screen).
7. **D2** remove the feedback feature; **E9** the "send to developer" button. (**E8**: English only, nothing to do.)

Done when: every D item is ticked or marked "not in version 1" with Deepak's agreement.

### Stage 6. Full quality pass (Both)

Why last before launch: testing before the features are finished would have to be done twice.

1. Claude: **E6** old-and-new version test, **E7** on-device automated tests.
2. Deepak with Claude's checklist: **E2** the full test plan on the release build, on a phone and a tablet.
3. Both: **E3** accessibility pass; **E4** a week of normal use for battery and speed.
4. Claude fixes what turns up; repeat the affected parts of E2.

Done when: E2, E3, E4, E6 and E7 are ticked and no known fault is open against sections A or B.

### Stage 7. Play Console and launch (Deepak, with Claude's drafts)

1. Deepak: make the Play developer account (if not made) and the app entry with the final package name.
2. Claude drafts, Deepak pastes: store description, what's new, screenshots list, **B5** forms and declarations
   (photo and video permission, manage media, Data safety, content rating), **B1** VPN declaration if kept,
   **B4** the background job declaration and its demo video script.
3. Deepak: upload the release build to closed testing; invite testers (**B9**).
4. Both: fix anything Play's review or the testers raise.
5. Deepak: apply for production once the closed test period is over.

Done when: the app is live on Play.

---

## Part 3. Checking in

- **"What's left?"** Claude reads this file, counts ticked and unticked items by section, names the current
  stage and the next three things to do, and says who each is waiting on.
- **After every build.** Claude ticks what the build finished (only after Deepak has tried it, unless it is
  paperwork), adds the date and branch, and adds any new loose end found on the way.
- **Rules that may have changed.** The Play rules in section B were read from the code's point of view on
  16 Nov 2026, not from Play's own pages. Before stage 3 and again before stage 7, check each against Play's
  current wording and correct this file.
- **What this audit did not cover.** It did not run the app or read every screen line by line. Stage 6 is where
  that gap closes.

## Decisions log

**17 Nov 2026, stage 1 part 1 (Deepak):**

- **B8.** The app's name is final: **Pixallery**. First public version: Deepak left it to Claude. Claude's choice:
  **1.0.0** for the first upload to Play's closed test; fixes after that 1.0.1, 1.0.2 and so on; new features
  1.1.0. The hidden build number keeps rising by itself with each automatic build, as it does now. Package name
  still open.
- **C1.** The repository will be made private, but not now (see C1 for what to do first).
- **B1.** Declare the VPN in Play (see B1 for Play's rule and the plan).
- **A1.** Finish the recovery helper.
- **D1.** Remove AuraFace before release, after more testing.
- **E1.** "The last fixes work, tick it." Log video detection checked on a real Log video.

**17 Nov 2026, stage 1 part 2 (Deepak):**

- **B8.** Package name **`app.pixallery`** (Deepak did not want his name in it).
- **D2.** Remove the tester feedback feature before release.
- **D3, D4, D16.** None of them in version 1; all can come in later updates.
- **E8.** English only for version 1.
- **E9.** Play's own crash reports plus a "send to developer" button.
- **B7.** A new email address just for Pixallery; Deepak will make it.
- **D9.** Deepak will check whether the Private DNS notice still shows.

## Done log

- 17 Nov 2026 · `feature/cleanup` · **E1** Deepak tried the build on his phone and said the fixes work; the branch
  was merged into `main` the same day.
