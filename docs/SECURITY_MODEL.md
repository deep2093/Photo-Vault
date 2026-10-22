# Security model

## Decision: separate folders, enforced by the app (v1)

v1 keeps each person's photos in their own folder and the app only ever reads and writes inside the
signed-in person's folder. This is **separation, not secrecy**: anyone who has the drive password can
open every folder from a computer. Setup tells the family this once, in plain words.

## The three options compared

| | A. Router accounts and folder permissions | B. Encryption on the phone | C. Small gateway server |
| --- | --- | --- | --- |
| What it stops | Other users opening your folder, if the router supports per-folder permissions | Anyone without your key reading your photos, even with the drive password | Anyone the gateway hasn't authorised |
| Works with a typical router USB drive | Usually no: most routers offer one account or share-level access only | Yes | Yes, but needs an always-on device |
| Effort | Low, if supported | High: key recovery, thumbnails and search done on the phone, sharing needs key exchange | High: a second program to build and maintain |
| Lost phone | Nothing lost | Photos lost without the recovery code | Nothing lost |
| Status | Revisit with NAS support (a NAS gives real per-user logins) | Later, as optional private mode | Later, only for invite links outside home |

## Threats and what v1 does

| Threat | v1 protection |
| --- | --- |
| A bug writes one person's photos into another's folder | Every write path is built from the owner's user ID and checked for containment (`PathRules`, Phase 4 tests) |
| A family member browses another's folder in the app | The app lists only the signed-in person's folder |
| A family member browses with a computer and the drive password | **Not prevented in v1.** Documented and shown during setup |
| Drive password leaked from the phone | Stored only as Keystore-encrypted ciphertext; excluded from cloud backup and device transfer; redacted from `toString()` and error messages |
| VPN private key leaked | Same Keystore encryption, stored in `noBackupFilesDir` |
| Drive exposed to the internet | Never: no port forwarding of 445; remote access only through the app-only WireGuard tunnel |
| Location data leaked through sharing | Stripped from shared copies unless the owner allows it (Phase 8) |
| Logs or crash reports leak secrets | `SmbErrors.describe` never returns raw exception text; tests check it |

## Identity

- `householdId`, `userId`, `deviceId` are random UUIDs created on the phone.
- Choosing who you are on a phone is a convenience, not a security boundary. This is acceptable only
  because v1 makes no secrecy claim.

## Carried over from Veyra

Keystore AES-256-GCM for secrets, `allowBackup=false` plus data-extraction rules, path containment in
`SmbConnection.safePath`, SMB signing/encryption options, error messages that never include paths or
credentials, WireGuard config imported with a 64 KiB limit and never logged.

## Secured folder (Phase 8)

- Each secured photo is encrypted with AES-256-GCM. The folder key is wrapped by an Android Keystore key that
  needs the PIN or fingerprint (BiometricPrompt with device credential) to use.
- The folder key is also wrapped by a recovery code shown once at setup, so a new phone can open the drive
  copies. Lost phone and lost code = the secured photos cannot be opened by anyone, including the family.
- Drive copies are stored already encrypted in `users/<user-id>/secured/`; file names are random IDs.
- Excluded from: MediaStore, the timeline, search, Places, memories, adaptive colours, share sheets, recent
  apps previews (FLAG_SECURE) and every thumbnail cache outside the folder.
- Locks again when the app goes to the background or after a short timeout.
- Fingerprint unlock is a convenience on top of encryption, not a replacement for it.

## Places and the details panel

Reading photo location needs Android's ACCESS_MEDIA_LOCATION permission. Since Phase 2 it is asked together with
photo access (and again from the details panel if it was refused), because the details panel shows coordinates.
"Open in Maps" hands the coordinates to the phone's maps app only when tapped.
Coordinates are kept in the local database only; map tiles are fetched without sending photo locations.
