# Google Drive sync overhaul — design

Status: approved 2026-09-27, revised 2026-09-28 (cloud backups removed: sync itself is the backup;
extension stores and source pins added). Replaces the single-snapshot sync from June 2026.

## Goals
- **Sync**: two or more devices converge on the same library, progress and settings, quickly and without
  reverting each other.
- **Backup**: the data survives a wiped/lost device — a new device signs in and gets everything back
  from the replicas in Drive.
- Existing sync users update without re-signing-in and without losing or clobbering data.

Never synced: downloaded chapters/local files, cookies, tracker (AniList/MAL…) logins,
the update-checker's per-device state (`tracks`), app lock/incognito, device paths and layout.

## Why the old design failed (summary)
Whole-bundle settings LWW decided by sync time; volatile keys (list checkpoints, update flags) in that
bundle; sync-on-process-start that read → downloaded → overwrote edits made meanwhile; deletions off
by default; no change timestamps for most rows; categories matched by title; every sync rewrote every
row incl. manga metadata; feed rows lost `chapter_ids`; tracker baselines synced; one shared multi-MB
JSON file (5 MB simple-upload limit, 60 s call timeout); only a 6 h timer pushed changes; no versioned
backups.

## Architecture
Drive `appDataFolder` (hidden):
- `replica_<deviceId>.json.gz` — one per device, written only by that device. Full synced state of
  the device (gzipped JSON, manga metadata stored once as a dictionary).
- `cover_<sha256>.<ext>` — custom cover images, content-addressed, uploaded once.
- `dropsauce_sync.json` — legacy file, read-only (see Upgrade).

Only the `drive.appdata` scope is requested.

Device id lives in `noBackupFilesDir` so an Android auto-backup restore can never make two devices
share a replica file.

## Change tracking (DB v38, additive only)
- `sync_rows(tbl, pk, v, deleted, sync_key)` — per-row version for favourites, favourite_categories,
  history, bookmarks, scrobblings, stats, track_logs, preferences. Maintained by SQLite triggers, so
  every write path (UI, restore, Mihon import, migration) is covered:
  - insert / content-changing update → `v = max(now, v + 1)` (monotonic per row, survives clock skew;
    no-op updates don't bump because the UPDATE trigger compares the content columns);
  - hard delete (bookmarks, scrobblings, stats, track_logs, preferences) → tombstone `deleted = 1`;
  - hard delete of soft-delete tables (their own GC) → tracking row removed.
  - categories get `sync_key` = cross-device uid: `t:<normalized title>` for categories that existed
    before the upgrade (so two devices' "Reading" match without a bootstrap step), random hex for new
    ones → renames are renames.
  - feed rows get `sync_key` = `manga_id:chapter_ids` (titles for legacy rows).
- `sync_prefs(file, key, hash, v)` — per-key baseline for synced SharedPreferences files. Each sync
  compares current values with the baseline; changed/removed keys get `v = max(now, v + 1)`. A file's
  first-ever baseline uses `v = 0` (unknown age).
- `sync_remote(file_id, md5)` — which remote replica contents were already merged (lives in the DB so
  it's always consistent with the data).
- Backfill on migration: versions from domain timestamps (history `max(updated_at, deleted_at)`,
  favourites/categories `max(created_at, deleted_at)`, bookmarks/feed `created_at`, others 0).

## Sync cycle (single mutex)
1. List `appDataFolder` (1 request).
2. Download only replicas (and the legacy file) whose md5 differs from `sync_remote`.
3. Detect local prefs changes; build the local view.
4. Merge (pure): per key, highest `v` wins. Ties: deletion wins, then larger content string
   (deterministic → convergence). Only a joining device (first sync with this account) lets remote
   win `v = 0` ties and drops its own never-synced keys the cloud's copy of that file lacks.
5. Apply winners only, in transactions, **compare-and-set**: a row is written only if its local `v` is
   still lower; a pref only if its current value still equals the detected value. Then the local
   version is set to the remote one. Manga metadata is inserted when missing, never downgraded.
6. Side effects for applied history: feed items covered by the new progress are marked read and the
   manga's new-chapter counter can only decrease (DB-only, no network).
7. Feed normalization for touched manga: an entry whose chapter set is a subset of another live
   entry's is dropped (dedupes updates detected independently on two devices).
8. Push own replica only if local data changed since the last push (fingerprint of `sync_rows` /
   `sync_prefs`), resumable upload when > 5 MB.
9. Housekeeping: tombstones & soft-deleted rows older than 180 days are GC'd; replicas not modified
   for 180 days and unreferenced cover files are deleted. Known ceiling: a device offline > 180 days
   may resurrect items deleted meanwhile.

"What to sync" is per device: a disabled type is neither published nor applied by that device.

## What syncs
| Data | Cross-device key | Notes |
|---|---|---|
| Categories | uid | rename/reorder/options/delete |
| Favourites | manga + category uid | pin, order, delete, move |
| History / progress | manga | delete/clear propagate |
| Bookmarks & highlights | manga + page | delete propagates |
| Tracker links | scrobbler + id + manga | status/rating/progress |
| Reading stats | manga + started_at | |
| Feed | manga + chapter ids | read/unread, delete, dedupe |
| Per-manga settings & custom covers | manga | covers as separate files |
| App settings | key | minus device-local keys (layout, paths, installers, caches, checkpoints, secrets) |
| Reader tap grid, source/extension settings, saved filters, novel plugin settings (`ln_*_db`) | file + key | not the plugins' local/session storage |
| Pinned sources, recently used sources | file + key | `source_state`, `source_usage` |
| Extension stores | store id (+ `@order`) | virtual file `extension_stores`; union on join; package→store ownership stays per device |

`fav_pinned_order_<categoryId>` is translated to the category uid on the wire.
Installed extensions themselves are not synced (APK installs need the user); the store shows what the
library needs.

## When sync runs
- App comes to foreground → sync; every 5 min while in foreground → sync (cheap when nothing changed).
- App goes to background with unpushed changes → expedited WorkManager job (survives process death).
- Background periodic (user frequency, default 6 h).
- Wi-Fi-only applies to all automatic syncs; "Sync now" always runs.
- Removed: "sync on app start" (replaced by foreground trigger), "don't sync deletions" (always sync).

## Data safety
- A device only ever writes its own replica; a replica that fails to download or parse is skipped,
  never read as "empty"; a newer format stops the sync instead of being half-merged.
- The device's own replica is merged back, limited to what the database has no versioned trace of:
  a wiped/corrupted database is refilled from its last upload before anything is uploaded (upload
  waits while that restore is incomplete).
- An app-settings file that comes back empty is treated as lost, not as "every setting removed".
- Deletions by the user propagate by design; the in-app undo is the only way back.

## Upgrade path for existing users
- Sign-in unchanged (GoogleSignIn grant reused; no re-login). Moving to AuthorizationClient is a
  follow-up (needed before play-services-auth 22).
- Existing prefs (interval, Wi-Fi only, what-to-sync, account) are kept; obsolete keys removed.
- The legacy `dropsauce_sync.json` is converted into a pseudo-replica (categories, favourites,
  history, bookmarks with versions from their domain timestamps) and merged like any replica whenever
  it changes — devices still on the old version keep contributing one-way. The screen shows a notice
  while an old-version device is still writing it.
- `v = 0` baselines + "a joining device adopts the cloud on 0-ties" mean the first sync after the
  upgrade never overwrites the cloud with a device's defaults.

## Testing
JVM unit tests for the pure merge (LWW, ties, tombstones, feed keys/dedupe) and extension-store records.
Device scenarios (user): setting change on A appears on B; same setting on both → newest wins;
unfavourite / move / rename category; read on A, leave app, open B → same page; feed read state;
bookmark delete; add an extension store / pin a source on A → appears on B; offline edits on both
devices merge; fresh install signs in → everything comes back.
