# Roadmap

Eleven phases, each on its own branch, each approved before the next starts.

| Phase | What gets built | Done when | Status |
| --- | --- | --- | --- |
| 0 Audit | Plan documents, repo setup, SMB and VPN modules copied out of Veyra | Clean CI build; decisions written down | **Done, merged** |
| 1 Design | Glass components, 4 themes, sample screens, phone and tablet layouts | Readable, smooth, blur fallback works | **Built, awaiting CI and approval** |
| 2 Local gallery | Phone photos timeline, viewer, albums, favourites, search | Large galleries scroll fast | |
| 3 SMB | Drive connection (from Veyra), browse, thumbnails, reconnect | Works offline and reconnects cleanly | |
| 4 Household | User and phone IDs, per-person folders, setup flow | Two users' files never mix | |
| 5 Backup | Upload queue, schedule (daily/weekly/monthly), checksum check, manifest, restore, mobile data toggle | Survives Wi-Fi drop, router restart, full drive | |
| 6 Cleanup | Wait period, re-check, Android delete prompt, restore | Nothing unverified can be deleted | |
| 7 Remote | WireGuard (from Veyra), home Wi-Fi detection | Reconnects; only app traffic uses VPN | |
| 8 Sharing | Shared albums on the same drive, roles, remove access | Removed members lose access | |
| 9 Extras | Editor, memories, map, duplicates (one at a time) | Originals never changed | |
| 10 Release | Full tests, speed check, security review | All critical tests pass | |

Windows PC app: paused until the phone app is finished and refined. Code stays shared so the port stays a small step (ARCHITECTURE.md).

Later: encryption (private mode), private vault, NAS support, invite links via a gateway.
