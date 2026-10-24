# Backup protocol

A photo is marked VERIFIED only after the app reads the uploaded file back from the drive and its
SHA-256 matches the phone's copy. Nothing else counts as "backed up".

## As built (Phase 4, 24 Oct 2026)

Code: `core/backup` (shared, tested with an in-memory drive and against a real Samba share in CI) and
`app/.../backup` (Android: MediaStore reading, WorkManager scheduling, notifications).

## One file

1. **Choose**: photos from the chosen phone folders (Camera only by default; Backup > Choose folders adds more,
   saved per phone). Videos only when the Videos switch is on (off by default, 24 Oct).
2. **Skip what's done**: a photo is known by name + size + capture time. If this phone's index has it, skip.
   A new install or new phone first reads the person's manifests from the drive, so nothing is uploaded twice.
3. **Upload** to `users/<user-id>/.incoming/<device-id>-<random>.part` with create-new, working out the
   SHA-256 while streaming. The original file is read (with "photo locations" access, location included).
4. **Verify**: read the temp file back from the drive, compare SHA-256 and byte count. Mismatch → delete our
   temp, count it as failed, try again next run. It is never recorded.
5. **Publish**: rename temp → `users/<user-id>/originals|videos/YYYY/MM/<name>` with replace=false.
   - Name taken by a file with the **same** size and SHA-256 → already there; delete only our temp.
   - Name taken by a **different** file → `<name> (2)`, `(3)`... Never overwrite.
6. **Record**: add to this phone's index (app storage), and every 25 files (and at the end, even after an error)
   write a new manifest `users/<user-id>/manifests/<device-id>/m-<time>-<random>.json` with create-new.
   Manifests are never edited.

A run cut off at any point leaves at most this phone's own temp files, which the next run deletes (other
phones' temp files are left alone; they may be uploading).

## Scheduling and conditions (decided 24 Oct)

- Default: **every Sunday at 2:00 am**, Wi-Fi (any Wi-Fi) and charging. Daily, weekly (any weekday) or monthly
  (day 1–28), time in 15-minute steps.
- Android (WorkManager) starts it at or after that time once the conditions are met: a phone not charging at
  2 am backs up as soon as it is charging on Wi-Fi.
- "Back up now" needs no charger; it waits for Wi-Fi unless mobile data is allowed.
- Mobile data off by default; turning it on asks first with the warning:
  "Backing up on mobile data uses about twice the size of each photo or video, because the app checks
  every file after uploading. Large videos can use a lot of data." Videos on mobile data is a separate switch.
- Long backups run as a foreground job with a progress notification (Android may refuse that when the app is
  in the background; the backup still runs and continues next time if Android stops it).

## Failure handling

| Event | Behaviour |
| --- | --- |
| Scheduled backup fails | Retry once, one hour later (same conditions) |
| The retry fails too | Notification "Backup didn't finish" with the reason in plain words |
| "Back up now" fails | The reason shows on the Backup screen and in History |
| Person taps Stop | Ends straight away (an unfinished upload is deleted); nothing retried; verified files stay backed up |
| Wi-Fi drops, router restarts, drive unplugged | Run ends with "Couldn't reach the drive"; what was verified stays; next run continues |
| Read-back mismatch | Temp deleted, not recorded, tried again next run |
| Space limit reached | Stops with "Your space on the drive is full" |
| Drive nearly full (less than 1 GB would be left) | Stops with "The drive itself is full" |
| Password changed on the router | "The drive refused the password" |
| Password not saved on the phone | "Turn on Remember password" (automatic backup can't sign in without it) |
| Photo deleted on the phone mid-run | Skipped quietly |
| Two phones, same file name | Different temp names; publish never replaces; numbered name on difference |

## Storage saver (photos only, decided 24 Oct)

Off by default, per phone. JPEG and HEIC photos are decoded at most 16 megapixels (good-quality prints up to 24 x 16 in) and saved as JPEG quality 85,
with date, camera, lens, exposure and location details copied and the orientation set upright. If the result
isn't smaller, the original is uploaded. The checksum is of the uploaded copy. Videos are never compressed.
The manifest marks these files `storageSaver: true` so Free up space (Phase 6) can warn before removing the
full-quality original.

## Space limit (100 GB per person by default)

- At the start of a run the person's folder is measured; before each file, used + file size must stay within
  the limit, and at least 1 GB must stay free on the drive.
- Not built yet: a warning at 90%.
- Never delete anything to make room.

## Recently deleted on the drive

- "Delete from phone and drive" moves the drive copy into `users/<user-id>/recently-deleted/` by rename,
  recorded in the manifest with the date.
- Items older than 30 days are removed at the start of a backup run. Nothing younger is ever removed.
- Restore renames it back; the never-overwrite rule applies.

## Restore (Phase 4)

Backup > Restore > Check the drive lists the person's backed-up files (all their phones' manifests) that aren't
on this phone: neither the original (name, size, time) nor an earlier restored copy (name and size). Each one is
downloaded into a new, hidden MediaStore entry under Pictures/Pixallery Restored (videos: Movies/...), its
SHA-256 checked, and only then made visible. A mismatch is removed. Nothing on the drive changes.

Settings > Storage > Drive (23 Oct) can also **save chosen backups to the phone** the same way (only the ones
not already on the phone).

## Deleting backups from the drive (23 Oct)

Settings > Storage > Drive lets a person delete **their own** backups, after a warning. First a manifest with
`removed: [paths]` is written (create-new, like every manifest), then the files are deleted. The catalog reads
all manifests in time order (names start with the time), so a deletion hides only what was saved before it.
The phone's backup index forgets those photos, so any copy still on the phone is backed up again next time
(the warning says so). Paths outside `users/<own id>/` are refused. Nothing on the phone is touched.
