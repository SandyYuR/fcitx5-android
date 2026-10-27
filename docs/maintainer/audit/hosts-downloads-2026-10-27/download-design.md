# Hosts-mapped update downloads

On Android 10+, direct downloads using the configured hosts mapping run as unique
WorkManager jobs. Ordinary downloads and older Android versions retain DownloadManager.
WorkManager 2.11.2 preserves this app's Android 6 minimum; its startup initializer is
removed. The Work database opens on demand when the update feature needs it, not
when the keyboard starts.

The update Activity only subscribes to persisted work state. Closing it cancels the
subscription, not the job. Enqueue/cancel commands preserve submission order and
always dispatch database work to IO. Duplicate starts keep the active job. A retry
after terminal work gets a new identity, so an old result cannot replace it.

Each UUID owns a no-backup staging directory and file lock. Strong ETags enable
Range/If-Range resume. Missing/weak validators cause a full restart; HTTP 200 replaces
the partial, changed validators and HTTP 416 restart it, and malformed ranges fail.
Size and optional release SHA-256 are checked before publication. Every completed
file also records its computed digest for interrupted publication recovery.

The transfer is capped at 512 MiB. Space checks account for the remaining transfer
and publication copy, retaining a 32 MiB reserve for other user data. Progress is
throttled to once per second. Cancellation closes the HTTP call and waits for its
callback to close files before releasing the lock or deleting partial data.

MediaStore rows stay pending until the file is verified and copied with fsync.
The pending URI is saved before copying. A restart can recover the insertion window
by UUID name, or the publication window by owned URI and digest, including names
automatically suffixed by MediaStore. Cleanup only removes owned pending rows;
published downloads and foreign rows are retained. Terminal/stale private staging
is pruned when the update page opens.

Foreground progress/cancel and completion notifications do not retain an Activity.
Network constraints and exponential retries are managed by WorkManager, with five
automatic retry attempts for transient errors. Android may defer work; force-stop
prevents background execution until the app is reopened. Persistence begins once
enqueue has committed; an uncommitted tap is not a durable work record. Resume also
depends on the server honoring a strong validator. Unit tests recreate worker/file
instances and UI subscriptions; they do not claim a physical power-loss test.
