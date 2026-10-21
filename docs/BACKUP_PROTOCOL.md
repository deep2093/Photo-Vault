# Backup protocol

A photo is marked VERIFIED only after the app reads the uploaded file back from the drive and its
SHA-256 matches the phone's copy. Nothing else counts as "backed up".

## States

`NOT_BACKED_UP → QUEUED → UPLOADING → VERIFYING → VERIFIED`, with `FAILED` (retryable) and
`CONFLICT` (needs a decision) as side states. Cleanup (Phase 6) continues from `VERIFIED`:
`RETENTION_WAIT → ELIGIBLE → CONSENT_PENDING → DELETED_LOCAL`.

## One file

1. **Discover** new items via MediaStore for the chosen folders and types.
2. **Fingerprint**: SHA-256 of the phone file. Already VERIFIED with the same hash → skip.
3. **Destination**: `users/<user-id>/originals|videos/YYYY/MM/<name>`, built from the owner's ID and
   checked for containment before any write.
4. **Upload** to `…/.pv-upload-<job-id>` (a unique temp name per job), creating the file only if absent.
5. **Verify**: read the temp file back, compare byte count and SHA-256.
6. **Publish**: rename temp → final name with replace=false.
   - Final name exists with the **same** hash → it is already backed up; delete only our temp.
   - Final name exists with a **different** hash → publish as `<name> (2)` etc. Never overwrite.
7. **Record**: append the entry to `manifests/<device-id>.jsonl` and to the local database; mark VERIFIED.

A crash at any step leaves either a temp file (cleaned up by the next run of the same job ID) or a
published, verified file whose manifest entry is re-derived by re-checking its hash.

## Scheduling and conditions

- Frequency: daily, weekly (weekday) or monthly (date 1–28), at a user-set time.
- Conditions: home Wi-Fi only (drive directly reachable), charging only. Not met → wait and run when met.
- Mobile data off by default; videos on mobile data separately off. Warning text:
  "Backing up on mobile data uses about twice the size of each photo or video, because the app checks
  every file after uploading. Large videos can use a lot of data."

## Failure handling

| Event | Behaviour |
| --- | --- |
| Wi-Fi drops, router restarts | Job stays UPLOADING/VERIFYING; retried with back-off; temp file reused by job ID |
| Drive full | Stop the run, keep the queue, tell the user |
| Read-back mismatch | Delete our temp, mark FAILED, retry once, then report |
| Two phones, same file name | Different temp names; publish never replaces; numbered name on hash difference |
| Two phones of one person upload the same photo | Same hash → second one is recorded as already backed up |
| Drive offline at schedule time | Wait for the conditions; never mark anything VERIFIED |

## Space limit (100 GB per person by default)

- Before each run and before each file: used space in the person's folder (originals, videos, recently
  deleted) plus the file's size must stay within their limit. Otherwise the file waits and the run reports
  "Your space is full".
- Used space is measured from the manifest and checked against the drive listing at the start of each run.
- Warn at 90%. Never delete anything to make room.

## Recently deleted on the drive

- "Delete from phone and drive" moves the drive copy into `users/<user-id>/recently-deleted/` by rename,
  recorded in the manifest with the date.
- Items older than 30 days are removed at the start of a backup run. Nothing younger is ever removed.
- Restore renames it back; the never-overwrite rule applies.

## Restore

Read from the drive into a new MediaStore entry, verify its hash, and never overwrite a newer phone file.
