# What came from Veyra

Source: `deep2093/SmbVideoPlayer` at commit `8ae28bd` (22 Oct 2026). Packages renamed from
`com.deepak.videolibrary.*` to `com.deepak.photovault.core.*`.

| PhotoVault file | From Veyra | Changes |
| --- | --- | --- |
| `core/smb/.../SmbConnection.kt` | `smb/SmbConnection.kt` | Removed the video grouping option, recursive delete and tree copy (v1 never deletes on the drive). `SmbProfile.toString()` now hides the password |
| `core/smb/.../LanDiscovery.kt` | `smb/LanDiscovery.kt` | Package only |
| `core/vpn/.../WireGuardManager.kt` | `vpn/WireGuardManager.kt` | Playback hold generalised to `TunnelHold` (`beginHold()`); host read from `SmbCredentialStore`; tunnel name `photovault` |
| `core/vpn/.../WireGuardConfigStore.kt` | `vpn/WireGuardConfigStore.kt` | Keystore code moved into `SecretBox`; new key alias |
| `core/vpn/.../SmbReachability.kt`, `ConfigText.kt` | same names | Package only |
| `core/security/.../SecretBox.kt` | crypto in `SettingsStore` and `WireGuardConfigStore` | Extracted once, same IV + ciphertext layout |
| `core/security/.../SmbCredentialStore.kt` | `settings/SettingsStore.kt` | Video grouping removed; `SmbSettings.toString()` hides the password |
| Tests: `SmbErrorsTest`, `ConfigTextTest` | same names | Package and sample paths |
| `app/src/main/res/xml/data_extraction_rules.xml`, R8 keep rules, CI shape | same | Trimmed to what PhotoVault uses |

## Not taken (yet)

- Glass UI (`ui/Theme.kt`, Haze blur, floating navigation): reviewed again in Phase 1, because the four
  PhotoVault themes differ from Veyra's.
- `files/TransferWorker.kt`: built around video library entities; Phase 5 writes a backup engine to
  BACKUP_PROTOCOL.md and borrows its staging/identity ideas.
- Thumbnail pipeline, scan engine, player: video-specific.
- VPN settings screen and banner: ported with the Phase 7 UI.

## Keeping them in step

These files are copies, not a shared library. A fix in one app should be checked against the other.
If both apps keep growing, the shared modules can later move into a common repository.
