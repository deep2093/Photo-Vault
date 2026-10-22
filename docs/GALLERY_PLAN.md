# Gallery redesign plan (Apple Photos-inspired brief, 24 Oct 2026)

Source: Deepak's brief "Pixallery: Apple Photos-Inspired Gallery Experience, Animations and Backup UI" and the
reference video (Dan's Tutorials, "Browsing the iPad Photo Library", iPadOS 26). The video itself could not be
watched from here; its chapter list and written guides to the same Photos app were used instead.

Status: **plan only, nothing built.** Deepak's answers (24 Oct):
- Photos becomes **Years / Months / All** (replacing the Day / Month / Year pill).
- Phone navigation: **Photos | Collections | Backup | Family**, with Settings behind a button.
- Themes: add **Follow system** (a chosen light and dark theme that switch with the phone's dark mode).
- Timing: **wait**. Test Phase 4 first, then decide when to build.
- Built early on request (24 Oct): Years / Months / All with tap-through, pinch between levels and the zoom
  animation. The rest of Milestone A (aspect ratio, filter & sort, view options, badges) still waits.

## How Apple's Library works (what we learn from it)

| Apple | What it does | Pixallery today |
| --- | --- | --- |
| Years | One card per year, a highlight photo from that year. Tap a year → Months scrolled to that year | Day/Month/Year only changes the headings over the same grid |
| Months | One card per month with a few key photos. Tap a month → All Photos scrolled to that month | Same as above |
| All Photos | The continuous grid | Day grid |
| Pinch | Steps through Years ↔ Months ↔ grid sizes ↔ single photo | Pinch changes columns 2 to 7 (phone) |
| Aspect Ratio Grid | Uncropped thumbnails | Square only |
| Sort & Filter | Date captured / recently added; photos, videos, favourites, edited, screenshots on/off | Search only |
| View Options | Zoom in/out, aspect ratio, show screenshots, shared items | Day/Month/Year pill and ⋮ menu |
| Select | Button plus drag-select; bulk share, favourite, delete, add to album | Long-press and drag-select; share, favourite, Recently deleted |
| Search | Natural-language search (needs on-device AI) | Name, album, type, month, year |

## Audit: what already exists

- Gallery: `GalleryState` loads the whole MediaStore list into memory; `Timeline.group` (Day/Month/Year headings);
  `MediaGrid` (LazyVerticalGrid, pinch steps, drag-select, fast scroller, square tiles); `PhotosScreen` (two
  floating pills, hide on scroll); Search, Albums, Recently deleted.
- Viewer: pager, pinch and double-tap zoom, pan, tap to hide bars, filmstrip, details panel. No thumbnail-to-viewer
  shared-element transition and no swipe-down-to-close (planned for Phase 12).
- Backup: `core:backup` engine (verify, never overwrite, manifests, index), WorkManager schedule, retry, restore,
  Backup screen with live progress, History, phones. No per-photo badges yet; no Pause/Resume (Stop only, it
  carries on next time).
- Drive photos in the gallery (SMB library), shared albums, collections and the Room index are not built:
  Phase 5 (one timeline, remote access, invites) and Phase 10 (family sharing).
- Glass design system with blur fallback, reduce motion, phone bar and tablet rail.

## Plan, in the brief's milestones

### Milestone A: Library views, filters, density, view options (fits now, with Phase 4)
1. `core:media`: `LibraryIndex` (pure): Years and Months summaries (cover photo, count) computed once per library
   change; sort (date captured, date added, name, size, newest/oldest); filters (photos, videos, favourites,
   screenshots, recently added, backed up / not backed up). Unit tests with 50,000 fake items.
2. `ui/shell`: Photos gets **Years | Months | All** (replacing Day/Month/Year): Years and Months are card grids;
   tapping goes one level in with an animated zoom; back returns to the same place.
3. Pinch: Years ↔ Months ↔ All (columns), with a live scale during the gesture and a snap at the end, keeping
   the photo under the fingers in place. Column count saved between launches.
4. Square / original aspect ratio (aspect mode uses rows of equal height, built from MediaStore width/height,
   so nothing jumps while thumbnails load).
5. Filter & sort sheet with active filters shown as chips and one-tap clear; View options: density, aspect,
   video length, backup badges, screenshots, date headings, animations. All saved.
6. Per-photo backup badges (backed up / not yet / uploading / failed), from the backup index, never from file names.

### Milestone B: Viewer and motion (brings Phase 12 work forward)
Shared-element thumbnail → viewer and back; swipe down to close; spring physics for gestures, short easing for
controls; reduce motion respected; large images decoded at screen size (subsampling for very large ones).

### Milestone C: Backup status
Small status ring in the Photos pill (opens Backup) showing the real state: not set up, up to date, scanning,
waiting for Wi-Fi / charger, uploading x of y, verifying, space full, failed. Backup screen: scanning vs
uploading vs verifying, bytes, current file and its progress, remaining photos and videos, Pause/Resume, Retry
failed, time left only once it is reliable. Progress survives the app being closed (already true for the work
itself; the numbers would be saved too).

### Milestone D: Collections and search
Collections page (Albums renamed?) with Favourites, Videos, Screenshots, Recently added, Backed up, Not backed up,
your own collections, pinning and reordering. Search suggestions and recent searches. Drive (SMB) photos, Shared
with me and a Room-based index belong with Phase 5 / Phase 10, when that data exists.
Object search ("cats", "beach") needs an on-device classifier: not promised; to be evaluated separately (like
face search in Phase 7).

## Conflicts with earlier decisions (need a choice)

- Phone navigation: brief says Photos | Collections | Backup | Search; today Photos | Albums | Backup | Family |
  Settings.
- Tablet rail: brief says collapsible; earlier decision (22 Oct) was a fixed rail, now with a bottom-bar option.
- Day / Month / Year pill (built 24 Oct) would become Years / Months / All.
- Theme choice: brief asks Dark / Light / System; Pixallery has named glass themes (Transparent default).
- Room for the index: not used today; makes sense when drive photos join the timeline (Phase 5).

## Risks

Large libraries (whole list in memory today), pinch and drag-select fighting each other, aspect-ratio layout
jumping, blur cost over scrolling photos (kept to bars only), and the hide-on-scroll pills.
