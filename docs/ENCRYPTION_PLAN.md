# Phase 4A / 4B: encrypted backups (plan for approval)

Status: **plan only, nothing built.** Based on Deepak's "Pixallery End-to-End Encryption Implementation Plan"
(PDF, 23 Oct 2026) and his answers on 24 Oct. Phase 4 (verified backup) is built on its branch without encryption
and is **not merged**; 4A and 4B come before the merge.

## Decisions (24 Oct)

| Topic | Decision |
| --- | --- |
| Timing | 4A (keys, recovery) and 4B (encrypted backup) before Phase 4 is merged. This round of UI fixes first, then this plan is approved before any code. |
| Default | **Each person chooses while setting up backup. Off by default.** Turning it on shows a warning pop-up: the photos can then only be opened in Pixallery (not from a PC, TV or Veyra straight off the drive), and losing both the phone and the recovery words means losing the backup. (The PDF made encryption the default; Deepak chose opt-in.) |
| Library | **Google Tink** (Android and JVM): AES-256-GCM-HKDF streaming AEAD for photos and videos (reads from any point, for scrubbing and Phase 5 playback), AES-GCM for small records. No home-made chunk format. |
| Recovery code | **24 simple words** (256 bits from a word list, with a checksum), shown once, offered to the password manager, write-down fallback. |
| Recovery helper | **Optional, off by default.** A person may choose one trusted family member whose key can unwrap theirs (for example a parent choosing a son). Never the owner automatically; no master key. |
| Owner | Owner PIN, approvals and space limits stay administration only; the owner never gets anyone's key. |

## What changes on the drive (encrypted people only)

```
users/<id>/
  keys/recovery-v1.bin      root key wrapped by the 24-word code (Tink, AAD = household, member, key ID)
  keys/helper-<userId>.bin  optional: root key wrapped to the recovery helper's public key
  keys/identity-v1.json     public signing / encryption keys, key ID, fingerprint
  objects/ab/<random>.pxe   each photo or video: Tink streaming ciphertext, header with format version
  thumbs/ab/<random>.pxt    small encrypted thumbnail (Phase 5)
  manifests/<device>/m-....pxm  encrypted + signed records (names, dates, album, place, checksums)
  .incoming/<device>-<random>.part   staging, as today
```

People who leave it off keep today's readable layout (originals/YYYY/MM/name).

## Keys

- **Root key (MRK):** 32 random bytes made on the person's first phone; on the phone it is kept wrapped by an
  Android Keystore key (existing `SecretBox`, new alias), usable while locked so scheduled backups keep working.
- **Per-file key:** Tink derives a fresh key per object (streaming AEAD with a random per-object salt) under a
  data key derived from MRK with HKDF; AAD binds format version, household, member, object ID.
- **Signing key:** Ed25519 (Tink) derived/stored under MRK; signs manifests and identity changes.
- **Recovery words** wrap MRK; changing them only re-wraps MRK.
- **New phone:** recovery words, or a QR hand-over from an already-signed-in phone of the same person. Owner
  approval, a matching user ID or the drive password never hand out keys.

## Backup engine changes (4B)

The Phase 4 engine stays (stage → read back → compare → publish without replacing → record). For encrypted people:
stream phone file → Tink encrypt → stage; read back and **decrypt-verify** (AEAD tags) and compare the plaintext
SHA-256 with the phone's; publish under a random name; write an encrypted, signed manifest. Restore decrypts and
checks the same way. Storage saver compresses first, then encrypts.

## Files (first pass)

| Where | Change |
| --- | --- |
| `core/crypto` (new, KMP; Tink on Android/JVM) | Key hierarchy, 24-word encoding (word list + checksum), envelope formats, streaming encrypt/decrypt, manifest sealing and signing. Tests: round trips, tampering (header, chunk, tag, order), wrong key, nonce uniqueness. |
| `core/identity` (new) | Member identity records, fingerprints, signed device grants, helper wrapping. |
| `core/backup` | `ObjectCodec` interface: plain (today) or encrypted; encrypted catalog; decrypt-verify step. |
| `core/drive`, `core/smb` | Ranged reads (for Phase 5 seeking). |
| `core/household` | Join can't imply key access; identity link per member. |
| `app` | Keystore wrap, Credential Manager save/restore of the words, QR hand-over, onboarding and warning pop-up. |
| `ui/shell` | Backup set-up step "Encrypt my backup" (off), recovery words screen and check, helper choice, labels. |
| docs | SECURITY_MODEL, BACKUP_PROTOCOL, HOUSEHOLD_PROTOCOL, TEST_PLAN, ROADMAP, PROJECT_PLAN rule 5. |

## Migration

Readable backups already on the drive stay as they are. A person who turns encryption on is offered "Encrypt my
existing backup": copy → verify → only then remove the readable copy, with their permission. Test backups made
during Phase 4 testing can simply be wiped instead.

## Release gate (from the PDF, kept)

A backed-up photo, the USB drive opened on a PC: nothing readable for an encrypted person; a fresh phone restores
from the 24 words and the photo matches byte for byte.


## Review of the PDF "Optional encryption + Google Drive backup" (25 Oct 2026)

The PDF agrees with this plan on: optional per person for the whole backed-up library, the owner never holding
another person's key, recovery code wrapping the master key, a proven library (no home-made crypto),
decrypt-verify before VERIFIED, signed identity and manifests, truthful wording in the app, and cleanup counting
only verified copies. Changes made after reading it:

1. **Folder layout stays.** The PDF moves normal files into a `standard/` tree. We keep today's readable layout
   (`originals/YYYY/MM/name`) so existing backups do not move and PC browsing still works for people who do not
   encrypt. Encrypted objects sit beside it, as above.
2. **Default stays off.** The PDF recommends Private. We keep opt-in (decided 24 Oct), but the choice is a clear
   screen during backup setup (Standard / Private, with the plain-words explanation), not a hidden toggle.
3. **Switching both ways.** Added Private -> Standard: warn that secrecy is lost, decrypt locally, upload normal
   objects, verify, never delete the encrypted originals automatically. Standard -> Private stays as
   "Encrypt my existing backup" (copy, verify, then remove readable copy only with permission). If one
   destination is Standard and another Private, the app says so and never claims full privacy.
4. **Thumbnails are required, not optional, for encrypted people.** The "real thumbnails stored on the drive at
   backup time" item moves from optional to part of 4B: small encrypted thumbnails, so the drive timeline never
   downloads full photos.
5. **Phase 5 features must go through the codec for encrypted people:** combined timeline, Open from drive, Save to
   phone, restore, Family Album (per-photo key sharing as already planned), Open with. 4B includes this pass.
6. **Google Drive stays at 10A** (later), but the backup code is written for several destinations from the start:
   a destination interface, per-destination VERIFIED receipts. When both a drive and a cloud destination are on,
   cleanup eligibility is revisited in 10A (the PDF wants both verified; Phase 6 only warns).
7. **Order:** 4A and 4B come before Phase 6, because cleanup depends on what "verified" means for encrypted
   backups, and before Phase 4 is merged and device-tested.
8. Segment size for Tink streaming AEAD: pick 1 MiB, benchmark seeking on Wi-Fi and mobile data (the PDF suggests
   4 MiB chunks; to be measured, not guessed). No decrypted temporary files outside app-private storage.

Questions to ask Deepak, one at a time, before any code: how the Standard / Private choice screen should look and
read; whether the recovery helper stays in; what happens to test backups already on the drive (wipe or keep
readable); the Credential Manager save of the recovery words.

### Answers from Deepak (25 Oct)
- Choice screen: two big cards (Normal / Private), nothing pre-selected; the person must pick.
- Recovery helper: stays, optional, off unless a person chooses one member.
- Test backups from Phase 4 testing: wiped and started fresh when encryption arrives.
- Recovery words: offer "Save to password manager" plus the write-down option.
All four questions are answered; the plan still waits for Deepak's final approval before any code.
