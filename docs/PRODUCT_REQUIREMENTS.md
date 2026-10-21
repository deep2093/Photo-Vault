# Product requirements (v1)

PhotoVault is an Android 11+ gallery that shows phone photos and backed-up photos together, and backs up
each family member's photos and videos to their own folder on one shared SMB drive.

## Decisions (from PhotoVault Plan v2, 22 Oct 2026)

| Topic | Decision |
| --- | --- |
| Code base | Reuse Veyra's SMB (smbj) and WireGuard code |
| Privacy between users | Separate folder per person, kept apart by the app. No encryption in v1 |
| Mobile data | Off by default; toggle with a data-use warning. Videos on mobile data: separate toggle, off |
| Android version | Android 11+ (API 30) |
| Git workflow | One feature branch per phase |
| Second backup copy | None yet. NAS support later |
| Encryption | Secured folder in Phase 8; whole-library encryption later |
| Space per person | 100 GB each by default, changeable per person in Settings |
| Photo size control | Pinch only, no zoom buttons; tablets zoom out to ~48 dp tiles like phones |
| Themes | 4 glass themes plus 3 adaptive themes (wallpaper follows the photos on screen, as in Veyra); Adaptive Light, Translucent and Frosted in Phase 2 |
| Windows PC app | Paused until the phone app is finished |

## Must have (v1)

**Gallery**
- Timeline of phone photos and backed-up photos, by day/month/year, with local/backed-up badges.
- Pinch to change photo size (no zoom buttons). Phones and tablets both zoom out to about 48 dp tiles. Albums, favourites, multi-select, sort/filter, search (filename, date, album, type).
- Viewer: zoom, swipe, video playback, details (EXIF), share, restore from drive.
- Works when the drive is offline (shows what is on the phone, marks remote items unavailable).

**Drive (SMB)**
- SMB2/3, test connection, LAN discovery, reconnect, saved password encrypted with the Android Keystore.

**Household**
- Each person and each phone gets a permanent ID; a folder per person is created during setup.
- The app shows each person only their own folder. Setup shows the v1 privacy warning once.

**Backup**
- Manual or scheduled. Schedule: every day, once a week (pick weekday) or once a month (pick date 1 to 28),
  at a time the user sets.
- Conditions: only on home Wi-Fi (default on), only while charging (default off). If not met at the set time,
  the backup waits and runs when they are. Times are shown as "around", never promised exactly.
- "Home Wi-Fi" = the drive answers directly (no location permission, no Wi-Fi name matching).
- Photos only, or photos and videos; choose phone folders.
- Queue, pause/resume, retry, history, per-phone progress, next scheduled run and last result.
- A file is "backed up" only after read-back verification (see BACKUP_PROTOCOL.md).

**Auto-cleanup** (off by default)
- Wait 7, 30 or 90 days after verification (30 recommended). Photos first; videos separate toggle.
- Single-copy warning that must be confirmed. Re-check before delete. Android's own delete prompt.
- Never cleans favourites, unfinished uploads or items with unclear owner. Thumbnail and restore stay.

**Remote access**
- Import WireGuard `.conf`; auto-connect only when the drive isn't directly reachable (user opts in);
  app-only routing; backups hold the tunnel open while running.

**Family sharing** (same drive only)
- Shared albums as copies in `shared/<album-id>/`, viewer/contributor roles, remove access,
  location stripped from shared copies unless the owner allows it.

**Design**
- Four Liquid Glass themes: Aurora Frost (default light), Midnight Prism (dark), Pearl Mist, Ocean Glass.
- Three adaptive themes from Veyra: Adaptive Glow, Midnight and Vivid. The wallpaper takes its colours from the
  photos on screen and eases between them; every hue it can produce is tested for readable text.
- Phase 2 adds three more:
  - **Adaptive Light:** the adaptive wallpaper in light mode: soft pastel colours from the photos on screen, dark text.
  - **Translucent:** clearer, more see-through glass so photos show through the bars; text stays readable by
    adjusting its colour and a faint backing to what is underneath.
  - **Frosted:** heavy, milky frost with a fine grain; the most private-looking and calmest bars.
  All three must pass the same contrast tests as the other themes, including over the busiest photos.
- Glass on controls, never on photos. Blur off on battery saver, slow devices and reduce motion.
- Phone and tablet layouts; tablet uses a fixed side menu.

**Recently deleted** (Phase 2 phone, Phase 5 drive)
- Deleting from the gallery moves photos to Recently deleted, not straight to permanent deletion.
- Phone: Android 11+'s own trash, which deletes items for good after 30 days. The app lists them with
  Restore and Delete now.
- Drive: deleting a backed-up photo asks "From this phone" or "From phone and drive". Drive copies go to the
  person's `recently-deleted/` folder and are removed after 30 days. Restore puts them back.
- Cleanup (Phase 6) is not deleting in this sense: the photo stays on the drive and in the timeline.

**Space per person** (Phase 3, enforced in Phase 4)
- 100 GB per person by default; changed per person in Settings > Family (whoever set up the drive).
- Counts that person's originals, videos and Recently deleted on the drive.
- Warn at 90%; at the limit, backup pauses with a clear message. Never deletes to make room.
- Setup warns if the total of all limits is larger than the free space on the drive.

**Places** (Phase 7)
- Map of photos by where they were taken (from the photo's own location data), clustered, with date filter.
- Places album (by city/area) and search by place. Works offline for photos; map tiles need internet.
- Location stays on the phone; shared copies have location removed unless the owner allows it.

**Secured folder** (Phase 8)
- A folder locked with PIN or fingerprint; photos moved in are removed from the normal gallery.
- Encrypted on the phone (key in the Android Keystore) and backed up to the drive encrypted.
- Never shown in the timeline, search, Places, memories, widgets or adaptive colours; no thumbnails outside it.
- A recovery code is shown once at setup; without it, a lost phone means the secured photos can't be opened.

## Later
NAS backup (true per-user logins, second copy), Windows PC app, invite links for people outside home
(needs a gateway), whole-library encryption.

## Open questions
- Should videos back up automatically on Wi-Fi, or only when chosen?
- What happens to a shared photo someone already saved, after the owner removes them?
- Does the router drive support SMB3 or only SMB2? (Veyra's connection can confirm.)
