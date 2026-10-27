# User data backup

Manual export defaults to password-protected `.fcbak`. The dialog also offers an
explicit unencrypted ZIP for compatibility with older/upstream versions. Both can
be imported. Passwords have no complexity rule, are not saved in preferences or
instance state, and cannot be recovered. These passwords are independent of the
LAN web editor pairing code.

Android cloud/device-transfer backup includes only `system_backup_ui.xml`, generated
by `UiSettingsBackupAgent` from a typed allowlist of settings-shell appearance keys.
It excludes the main preferences, clipboard databases, sync credentials, input
history and Rime files. Use manual export to transfer the full user data.

## Envelope version 1

All integers are big endian. Header (42 bytes):

| Field | Size | Value |
| --- | --- | --- |
| Magic | 8 | ASCII `FCRIMEBK` |
| Version | 1 | 1 |
| KDF | 1 | 1: PBKDF2-HMAC-SHA1, 256-bit key |
| Iterations | 4 | 1,300,000; reader accepts 100,000–2,000,000 |
| Salt | 16 | Independent SecureRandom bytes per export |
| Base nonce | 12 | Independent SecureRandom bytes per export |

Record: 4-byte plaintext length, 1-byte final flag (0/1), then AES-256-GCM
ciphertext and 16-byte authentication tag. Data records contain 1–65,536 bytes.
An authenticated empty final record is mandatory. No trailing bytes are allowed.
The stream is limited to 4 GiB plaintext; the existing ZIP importer separately
limits extracted bytes, entries and destination paths.

For each zero-based record index, XOR its big-endian 64-bit encoding into the last
8 bytes of the base nonce. AAD is the complete header followed by the 8-byte
record index, 4-byte plaintext length and 1-byte final flag. This detects reordered,
modified, truncated and appended records. Small independent AEAD records bound
provider buffering, including providers which withhold plaintext until the tag.

Decryption uses a private cache file; no ZIP parsing or live-data replacement occurs
until every tag and the final record pass. Failed/cancelled imports delete the
temporary. The password-derived key and transient char/byte arrays are cleared
where possible; Android/JCA may make internal copies. Successful authentication
does not bypass the existing ZIP validation, restore destination selection or
restore confirmation. Old ZIPs remain plaintext and must be handled as sensitive.
