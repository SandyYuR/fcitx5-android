# User data restore and process interruption

Whole backups are extracted into a bounded private quarantine before confirmation.
Protected backups are authenticated before extraction. Clipboard WAL recovery,
database version checks, preference XML validation and external destination checks
take place before replacing installed data.

## Ownership and process isolation

After confirmation the main process saves and stops the input engine. It writes a
private request and opens the non-exported `:restore` Activity. That process does
not initialize AppPrefs, Room, themes, native input or background application
writers. It acquires the restore file lock, stops the old main process, and waits
for it to exit before copying any live root.

The main application acquires the same lock in `attachBaseContext`, before content
providers and `onCreate` initialize cached settings or databases. A replacement
main process therefore cannot open data while a restore is running. If the lock
is busy, startup routes back to the recovery screen instead of waiting on the main
thread. Once live roots are consistent, recursive cleanup of large retained
directories runs on IO; it does not delay normal application initialization. Each process
also serializes its own callers to avoid overlapping JVM file locks.

The background worker only cleans roots already marked resolved. It does not
retry pending live roots after a conflict disappears or an optional volume
returns; startup, recovery UI or profile access performs those decisions under
the restore lock. Test fixtures wait for the worker before deleting their roots.

The main process remembers which roots were ready at startup, so the input engine
does not queue behind deletion of hidden leftovers. This is safe because another
restore must terminate that main process before changing any live root. Pending
optional roots are rechecked under the lock before use when storage returns.

## Durable decision

`noBackupFilesDir/user-data-restore.json` records all roots and one global phase:

| Phase | Recovery decision |
| --- | --- |
| STAGING | Leave live data unchanged; remove owned incomplete staging. |
| REPLACING | Roll all affected roots back to the originals, in reverse order. |
| COMMITTED | Keep all imported roots; finish removing owned originals/staging. |

Each root is staged beside its destination on the same filesystem. The journal
registers its UUID before creating staging. Before moving an original, it records
whether the destination existed and adds an ownership marker. Imported roots
carry a separate marker. The global commit is written only after every rename
succeeds. Journal/marker writes use a synced temporary file and atomic rename.

Recovery writes each resolved state before removing evidence, and removes
ownership markers last during cleanup. It can therefore run repeatedly after
further process exits. It never recursively follows a child symlink, accepts a
redirected root, or deletes a nonempty foreign destination to make rollback fit.

Unavailable optional storage remains pending without recreating its volume.
Internal application roots must recover before normal initialization. Native
profile loading, switching, creation, renaming and deletion also check pending
paths, including storage that becomes available after startup. Conflicts retain
both directories and open a minimal recovery screen with retry and a diagnostic
document containing paths/errors, not file contents. Do not clear application
data when originals are retained for recovery.

## Preferences and SQLite

Android's `.xml.bak` preference file is authoritative over its corresponding XML.
Import normalizes and validates that snapshot before staging; replacing a main
preference also removes its old installed `.bak`. This prevents the next startup
from loading the old value over a successful import.

The old process has exited before database copying. Incoming clipboard databases
are recovered from their own WAL first. Stale installed sidecars are removed only
for databases being replaced. WAL files belonging to databases omitted by the
archive are retained with their original base file.

## Limits of the guarantee

This protects process interruption and ordinary I/O failure. It is not a claim
of physical power-loss durability across multiple volumes: Android storage and
hardware may have additional caches, and all directory metadata is not synced as
one hardware transaction. Filesystem loss, removed volumes and externally edited
ownership markers can require manual recovery. No cleanup discards unrecognized
original data to hide such an error.
