# Roadmap

Phone app first; each phase on its own branch and approved before the next starts. Re-planned 22 Oct 2026
after the Phase 1 review, to add Recently deleted, Places, the Secured folder, 100 GB per person, adaptive
themes and the Adaptive Light, Translucent and Frosted themes, and to put the most useful features first: a great gallery, then safe backup, then everything that
builds on backup.

| Phase | What gets built | Done when | Status |
| --- | --- | --- | --- |
| 0 Foundation | Plan documents, repo setup, SMB and VPN code copied out of Veyra | Clean CI build | **Done, merged** |
| 1 Design | Liquid Glass components, 4 glass themes + 3 adaptive themes (from Veyra), sample screens, phone and tablet layouts, pinch-only photo sizing | Readable on every theme, smooth, blur fallback works | **Done, merged** |
| 2 Phone gallery | Real photos and videos from the phone: timeline (day/month/year), pinch, viewer with zoom and video, favourites, phone albums, select, share, search, **Recently deleted** (30 days), adaptive colours from real photos, **three more themes: Adaptive Light, Translucent, Frosted**, **swipe-up details panel** (date, file, folder, camera, lens, exposure, size, location as coordinates with Open in Maps) | 20,000+ photos scroll smoothly; delete and restore work; every theme passes the contrast tests | **Done, merged** |
| 3 Drive and family | Connect to the drive (Veyra's SMB code), first-run setup, family on one drive, one folder per person, **100 GB per person by default, changeable in Settings** | Two people's files never mix; limits are enforced | **Built, waiting for device testing** |
| 4 Verified backup | Backup engine with read-back check, daily/weekly/monthly schedule at a set time, home Wi-Fi and charging conditions, mobile data with warning, space-limit checks, restore, history | Survives Wi-Fi drop, router restart, full drive, full space limit | |
| 5 One timeline | Phone and drive photos together, drive thumbnails cached, works offline, open drive-only photos, Recently deleted on the drive (30 days) | Offline and reconnect behave; nothing lost on delete | |
| 6 Free up space | Opt-in auto-cleanup after verified backup, 7/30/90-day wait, re-check, Android delete prompt | Nothing unverified can ever be deleted | |
| 7 Places and memories | **Photos by location** on a map (on the phone, no Google key), Places album, search by place, "On this day". **Details panel completed as in Samsung Gallery:** map preview with the photo pinned, street address, nearby place name, and tags (add, edit, search by tag) | Location never leaves the phone unless shared | |
| 8 Secured folder | **PIN/fingerprint locked folder**, encrypted on the phone and on the drive, hidden everywhere else, recovery code | Locked photos never appear outside the folder | |
| 9 Remote access | WireGuard (from Veyra), backup away from home, app-only routing | Reconnects; only app traffic uses VPN | |
| 10 Family sharing | Shared albums on the same drive, viewer/contributor, remove access | Removed members lose access | |
| 11 Editor and duplicates | Crop, rotate, light and colour, filters (saved as a copy), duplicate finder | Originals never changed | |
| 12 Motion and smoothness | A full pass over the whole app: shared-element transitions (photo grid to viewer), smoother pinch, 120 Hz scrolling, startup and scroll profiles, jank checks on a mid-range phone | No dropped frames in the main flows; every animation respects reduce motion | |
| 13 Release | Full tests, speed and battery check, security review, Play Store readiness | All critical tests pass | |

**Later:** NAS support (real per-person logins, second backup copy), the Windows PC app (paused; code stays
shared, see ARCHITECTURE.md), invite links for people outside home, optional encryption of the whole library.

## Why this order

- **Gallery before backup (2 before 4):** the app is useful from day one, and the gallery's media index is what
  backup reads from.
- **Recently deleted in Phase 2:** Android 11+ already keeps deleted photos for 30 days, so the phone side is
  cheap and makes every later delete (cleanup, editor, duplicates) recoverable.
- **Space limits with family setup (3), enforced by backup (4):** a limit only matters once uploads exist,
  but it has to be part of the folder layout from the start.
- **Cleanup only after the unified timeline (6 after 5):** freeing space is safe only when drive copies
  can be seen and opened.
- **New themes with real photos (2):** Adaptive Light, Translucent and Frosted only show their character over
  real photos, so they arrive with the gallery and are tested against it.
- **Animations everywhere, then a dedicated pass (every phase + 12):** each phase animates its own screens
  (asked 22 Oct). Phase 12 then tunes the whole app together, measured on a real mid-range phone, just
  before release.
- **Secured folder after backup (8):** its copies on the drive reuse the verified backup engine, encrypted.
