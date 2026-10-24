# Household protocol (Phase 3)

How phones share one family on one drive without a server. Code: `:core:household` (`HouseholdStore`),
over `:core:drive` (`DriveFileSystem`), with the SMB version in `:core:smb` (`SmbDriveFileSystem`).

## The one rule every write follows

The drive offers one safe step: **create a file only if that name doesn't exist yet**
(`DriveFileSystem.createNew`). On SMB it writes a temp file `.pv-tmp-<random>` and renames it into place
without replacing, so a reader never sees half a file and two phones can never both win.
Nothing in `system/household/` is ever overwritten.

## Files

```
PhotoVault/
  system/
    household/v000001.json   first version of the family
    household/v000002.json   each change is a new version; the newest readable one counts
    requests/<device-id>.json a phone asking to join
  users/<user-id>/
    originals/  videos/  manifests/  thumbnails/  recently-deleted/
```

- A household file holds: format version, household ID, revision, owner's user ID, the owner PIN hash,
  settings, and the members with their phones and space limits.
- Display names and limits live only in the household file; folder names are random user IDs.
- The newest 20 versions are kept, older ones removed. A version that can't be read is skipped, so a damaged
  newest file falls back to the one before.
- A newer format version than the app understands is refused, never rewritten.

## Changing the family

1. Read the newest version N.
2. Apply the change in memory.
3. `createNew("v<N+1>.json")`. If another phone made N+1 first, go back to 1 (up to 8 tries).

Six phones joining at once all end up in the family exactly once (`HouseholdStoreTest`).

## Setup and joining

- **First phone:** creates the family as `v000001`. If that file already exists, setup stops and offers
  to join instead.
- **Other phones:** write `requests/<device-id>.json` as "I'm new: <name>" or "I'm <existing person>".
  With **Approve new phones** on (default), the owner approves or declines on the Family page; with it off,
  the phone is added straight away.
- Each person's folders are created when they are added.
- **A lost or replaced owner phone (23 Oct):** a phone that chooses the owner and enters the **owner PIN** joins
  as the owner straight away, even with approval on (nobody else could approve it), then removes the lost
  phone in Family. Without the PIN it only asks to join and waits for another of the owner's phones; even
  with approval off, nobody joins as the owner unapproved. The owner PIN is required when a family is
  created; an owner without one is asked to set it. Wrong PINs count towards the same lock-out.

## Owner controls

Owner-only: approve or decline phones, change anyone's space, remove a person or a phone, the two settings
below, the default space and the owner PIN. Settings → Family shows them; others see them read-only.

| Setting | Default | What it does |
| --- | --- | --- |
| Owner PIN | On | Asks for the PIN before any owner action. Unlocked for 5 minutes at a time. Turning this off stops the asking but keeps the PIN for an owner's new phone |
| Approve new phones | On | New phones wait for the owner |
| Space for new people | 100 GB | Starting limit for each new person (1 GB to 100,000 GB) |

- The PIN is 4 to 8 digits, stored as PBKDF2-SHA256 (120,000 rounds, random 16-byte salt) in the household
  file. Wrong PINs lock for 30 seconds after 5 tries, doubling up to 15 minutes.
- Removing a person keeps their photos on the drive; only their place in the family goes.
  The owner can't be removed, and a phone can't remove itself.
- Space limits are enforced by backup (Phase 4). Phase 3 shows them and how much each folder uses.

## What this protects against, and what it doesn't

The PIN and owner checks stop **casual changes from inside the app**. They are not security against someone
with the drive password: they can edit the files on the drive from a computer. See SECURITY_MODEL.md.
