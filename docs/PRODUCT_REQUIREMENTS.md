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
| Encryption | Later, as an optional private mode |

## Must have (v1)

**Gallery**
- Timeline of phone photos and backed-up photos, by day/month/year, with local/backed-up badges.
- Pinch to change grid density. Albums, favourites, multi-select, sort/filter, search (filename, date, album, type).
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
- Glass on controls, never on photos. Blur off on battery saver, slow devices and reduce motion.
- Phone and tablet layouts; tablet uses a fixed side menu.

## Later
Encryption (private mode), private vault, NAS backup (true per-user logins, second copy),
invite links for people outside home (needs a gateway), editor, memories, map, duplicate finder.

## Open questions
- Should videos back up automatically on Wi-Fi, or only when chosen?
- What happens to a shared photo someone already saved, after the owner removes them?
- Does the router drive support SMB3 or only SMB2? (Veyra's connection can confirm.)
- Repository: new `PhotoVault` repo (assumed) or inside the Veyra repo?
