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
| 3 Drive and family | Connect to the drive (Veyra's SMB code), first-run setup, family on one drive, one folder per person, **100 GB per person by default, changeable in Settings** | Two people's files never mix; limits are enforced | **Done, merged** |
| 4 Verified backup | Backup engine with read-back check, daily/weekly/monthly schedule at a set time, home Wi-Fi and charging conditions, mobile data with warning, space-limit checks, restore, history | Survives Wi-Fi drop, router restart, full drive, full space limit | |
| 5 One timeline, remote access and invites | Phone and drive photos together, drive thumbnails cached, works offline, open drive-only photos, Recently deleted on the drive (30 days). **Plus (moved from Phase 9, 24 Oct):** WireGuard (from Veyra) so family away from home can use the drive; **connects only while the app is on screen and disconnects the moment it is minimised, left or closed**; Settings: "Use VPN for this app only" and "Connect automatically". **Plus invites:** invite file and QR locked with a code, one-use, expires, owner attaches a per-person WireGuard config; so 1 or 2 other people can join and see the library | Offline and reconnect behave; nothing lost on delete; VPN is off whenever the app is not in view; only app traffic uses it | |
| 6 Free up space | Opt-in auto-cleanup after verified backup, 7/30/90-day wait, re-check, Android delete prompt | Nothing unverified can ever be deleted | |
| 7 Places and memories | **Photos by location** on a map (on the phone, no Google key), Places album, search by place, "On this day". **Details panel completed as in Samsung Gallery:** map preview with the photo pinned, street address, nearby place name, and tags (add, edit, search by tag) | Location never leaves the phone unless shared | |
| 8 Secured folder | **PIN/fingerprint locked folder**, encrypted on the phone and on the drive, hidden everywhere else, recovery code | Locked photos never appear outside the folder | |
| 9 Remote access | **Moved into Phase 5** | | Merged into 5 |
| 10 Family sharing | Shared albums on the same drive, viewer/contributor, remove access | Removed members lose access | |
| 11 Editor and duplicates | Crop, rotate, light and colour, filters (saved as a copy), duplicate finder | Originals never changed | |
| 12 Motion and smoothness | A full pass over the whole app: shared-element transitions (photo grid to viewer), smoother pinch, 120 Hz scrolling, startup and scroll profiles, jank checks on a mid-range phone | No dropped frames in the main flows; every animation respects reduce motion | |
| 13 Release | Full tests, speed and battery check, security review, Play Store readiness, **in-app setup guide** (drive, WireGuard on the router, adding family; see below) | All critical tests pass | |

**Invites (decided 23 Oct, moved into Phase 5 on 24 Oct):** the owner makes an invite (drive details, optional WireGuard config) locked with a code and
shared as a file and QR over WhatsApp; the new person joins in one step with no approval wait. Built in Phase 5
alongside remote access, because the VPN part has to be real. The Play Store link version (installs the app, carries the invite
over) comes at release, once there is a store listing. See the notes in docs/PENDING_FIXES.md.

**In-app setup guide (decided 23 Oct, Phase 13):** a "How to set this up" page reachable from the drive setup screen and
Settings > Drive, with plain steps and pictures: connecting the router's USB drive, turning on SMB, folder names that
must match on every device, setting up WireGuard on the router (Home Network Only, one account per device, export the
config), importing it in the app, and adding family. Written for common routers, starting with TP-Link, with a generic
fallback. Text is shipped in the app so it works offline.

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
