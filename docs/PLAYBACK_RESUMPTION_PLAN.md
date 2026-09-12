# Android playback resumption plan

Status: Emulator-validated; signed physical-device matrix remaining

## Goal

Restore the authoritative live playback state after `PlaybackService` process death, an app update, or a device reboot. On the next service start, the phone UI and Android Auto should see the previous queue, current item, position, shuffle mode, and repeat mode without requiring the user to use the separate saved-queue slot.

Resumption must never start audio automatically. If Android keeps the existing foreground playback service alive, playback continues normally; this plan only covers reconstruction after that service and its ExoPlayer instance were destroyed.

## Product behavior

- Automatic resumption and the manual **Save/Load** queue slot remain separate features and use separate files.
- A restored session is always paused. Pressing Play prepares the player if necessary and starts at the restored item and position.
- Preserve at most 500 queue items, their order, and duplicate IDs.
- Preserve current index, current position, shuffle enabled, and repeat Off/All/One.
- If the previous player had reached the end, restore its current item at position zero rather than immediately ending again.
- **Clear** and disconnect delete the automatic checkpoint. They do not change the manual saved-queue slot.
- Pairing to another device/server cannot restore an old checkpoint. Checkpoints are scoped by the existing credential-derived cache namespace or opaque device ID.
- Corrupt, oversized, unsupported-version, or credential-mismatched checkpoints are ignored safely and removed.
- Restoration should not require metadata HTTP requests. Streaming still requires valid current credentials and a reachable server when Play is pressed.

## Persisted format and security boundary

Add a versioned app-private `PlaybackResumeStore`, separate from `SavedQueueStore`. Use an atomic staging-file replacement and strict decoding limits.

Persist only:

- format version;
- credential/cache namespace, never the bearer token;
- up to 500 queue entries containing opaque track ID, title, artist, album, audio format, and optional cover ID;
- current queue index;
- current position in milliseconds;
- shuffle enabled;
- repeat mode;
- update timestamp for diagnostics and future migrations.

Do not persist bearer tokens, authorization headers, stream URLs, artwork bytes, arbitrary URLs, filesystem paths, or Android `Bundle`/`MediaItem` serialization. Rebuild stream and artwork URLs through `PlaybackMediaItemFactory` using current credentials.

`PlaybackMediaItemFactory` should place the safe format and cover ID in private metadata extras so both phone- and Android-Auto-originated queue items can be projected into the checkpoint without parsing URLs.

## Work packages

### 1. Define and test the durable record — Implemented 2026-09-11

The resume record models and synchronous `PlaybackResumeStore` are implemented. Service integration will call its file operations from an IO dispatcher in package 2.

Implementation requirements:

- versioned JSON envelope;
- strict ID/text/format/index/position bounds;
- only `flac` and `mp3` format values;
- atomic write through a sibling temporary file;
- read, write, delete, and existence operations off the main thread;
- deterministic handling of an empty queue as checkpoint deletion.

Tests:

- full round trip including duplicate IDs and all playback modes;
- malformed JSON, unknown version, invalid index, invalid format, oversized queue, and excessive strings;
- atomic replacement leaves either the previous or new valid record;
- empty queue deletes the record;
- no secret or URL field is present in serialized JSON.

### 2. Add a bounded checkpoint coordinator — Implemented 2026-09-11

`PlaybackService`, not Compose, now owns automatic checkpointing because the service owns the authoritative ExoPlayer queue. A single IO writer conflates pending updates; player state is captured on the main thread, and player release cannot erase the final queue checkpoint.

The coordinator should:

- capture immutable snapshots on the player/main thread;
- perform all file writes on one IO coroutine/actor so older writes cannot overtake newer writes;
- conflate rapid updates;
- persist timeline, media transition, shuffle, repeat, play/pause, and ended-state changes promptly;
- checkpoint position approximately every five seconds only while playing;
- write a final already-captured snapshot during orderly service teardown without blocking the main thread on normal operation;
- detach before `player.release()` so release-time timeline clearing does not erase a valid checkpoint;
- expose an explicit ordered delete operation for Clear and disconnect.

Queue mutation writes may be debounced briefly, but Clear/delete must not be overtaken by a stale pending write.

Tests:

- rapid updates persist only the newest snapshot;
- a pending write cannot resurrect state after delete;
- position polling stops while paused or idle;
- release does not replace the checkpoint with an empty queue;
- network-loss `player.stop()` preserves the queue checkpoint.

### 3. Restore the ExoPlayer session locally — Implemented 2026-09-11

`PlaybackService` now reads the current credential namespace asynchronously, rebuilds media items only through `PlaybackMediaItemFactory`, and applies queue/index/position/shuffle/repeat on the main thread without preparing or playing. Queue-mutation callbacks and a generation guard ensure a user-supplied queue wins a delayed restore. Phone Play prepares an idle restored queue.

During `PlaybackService` startup:

1. Load current credentials.
2. Read only that credential namespace's checkpoint on IO.
3. Rebuild bounded `Track`/`MediaItem` values through `PlaybackMediaItemFactory`.
4. On the main thread, restore shuffle, repeat, queue, current index, and position.
5. Leave ExoPlayer idle and paused; do not call `play()`.

Modify phone Play handling so an idle restored player is prepared before playback. This avoids opening a stream merely because the app or a media browser connected.

Protect startup against races: discard a delayed restore if credentials changed, the service is closing, or a controller already supplied a non-empty queue.

Tests:

- queue/index/position/modes restore correctly;
- restored state never autoplays;
- Play prepares an idle restored queue;
- a new user queue wins over delayed restoration;
- missing credentials and mismatched namespaces do not restore;
- corrupt records leave an empty usable session.

### 4. Implement Media3 system playback resumption — Implemented 2026-09-11

`AutoLibrarySessionCallback` now implements Media3's current `onPlaybackResumption` contract through the same validated local loader used by proactive phone restoration. It returns the validated local items, start index, and position through `MediaItemsWithStartPosition` so notification, Bluetooth, and Android Auto resumption use the supported Media3 path.

Requirements:

- no main-thread disk or network access;
- use a single validated restore provider shared with proactive phone restoration;
- return a failed/empty result safely when unpaired or no checkpoint exists;
- retain authenticated URL construction through current credentials;
- do not duplicate or race proactive restoration.

Tests:

- callback completes with the expected ordered queue and start position;
- cancellation cancels its coroutine work;
- empty and invalid checkpoints do not crash external controllers;
- Android Auto and notification controllers receive no token-bearing metadata.

### 5. Wire explicit lifecycle semantics — Implemented 2026-09-11

A trusted app-only Media3 custom command now performs an awaited, generation-ordered checkpoint deletion in the service. Clear reconciles the final player timeline and requests that deletion; disconnect waits for it before credential removal and retains a direct credential-scoped fallback. Credential namespace changes detach the old listener/writer, delete its checkpoint, clear old media items, and start a new isolated restore scope.

- `PlaybackViewModel.clearQueue()` clears both the live player queue and automatic checkpoint through the service-owned timeline/delete path.
- Disconnect clears the old namespace's automatic checkpoint before credentials are removed.
- Manual saved-queue Save/Load remains unchanged.
- A normal queue replacement, reorder, removal, enqueue, shuffle, repeat, seek, pause, and track transition updates the automatic checkpoint.
- Activity destruction or removal from recents does not clear automatic state.

Regression tests cover ordered deletion, credential-namespace isolation, and independence from `SavedQueueStore`; signed-device Clear/disconnect/re-pair scenarios remain in package 6.

### 6. Device validation and documentation — Emulator-validated 2026-09-12; signed physical-device remaining

Emulator validation on 2026-09-12 used a paired debug build on `emulator-5554`. `am force-stop` plus launcher relaunch is the working process-death stand-in: `am kill` left the prior pid, and `kill -9` / `adb root` are blocked on this production-image emulator, so in-place service death while DHU stays attached was not simulated. Automatic checkpoints are `files/playback-resume-<64-hex>.json`; the independent manual slot `saved-queue-*.json` must not be globbed as a resume file.

A first pass while the paired server was unreachable for streaming restored a 21-item queue paused after force-stop in 1.395 seconds and after a full emulator reboot in 2.800 seconds with index 0, shuffle, and Repeat All intact. Both restores remained in Media3 state `NONE` with no autoplay. Clear removed the checkpoint and a second relaunch stayed empty. A generated 500-item checkpoint (134,504 bytes) restored index 499 and modes in 1.884 seconds at about 176 MiB emulator PSS (about 150 MiB in the 21-item sample; indicative, not a controlled memory benchmark). A mixed FLAC/MP3 checkpoint restored the MP3 at index 1 and position 12,345 ms exactly with Repeat One. Phone Play moved the restored player from `NONE` to `BUFFERING`, and the Media3 notification exposed title, artist, 126 px artwork, and transport actions.

A later pass with a reachable paired server used a live 21-item album queue:

- Force-stop while playing restored paused with 0 ms drift: 34,992 ms in 1.485 seconds, 59,921 ms in 1.482 seconds, and index 1 at 40,997 ms in 1.483 seconds (Repeat Off, shuffle off, queue size 21, Media3 `NONE`). Playing-restore PSS was about 183 MiB (182,808 kB). Phone Play and `cmd media_session dispatch play` prepared through `BUFFERING` to `PLAYING` at the restored position (897 ms to `PLAYING` on the 40,997 ms sample). Live position then advanced (34,992→62,400 ms; 130,812→136,758 ms).
- Repeat Off plus a near-end seek auto-advanced from Overcompensate (Live In Mexico City) to Holding on to You (Live In Mexico City).
- After `KEYCODE_SLEEP` / `KEYCODE_WAKEUP`, `cmd media_session dispatch pause` then `play` paused at 21,027 ms and resumed at 21,105 ms. The Media3 notification (`id=1001`, category transport, three actions) remained. The emulator did not keep the keyguard showing (`mDreamingLockscreen=false`), so this is a media-key/notification stand-in rather than a visible lock-screen UI. Bluetooth audio is unavailable on this emulator.
- Force-stop without launching the phone UI still restored through Gearhead rebind (`gearhead_rebind=1`) at 59,921 ms, size 21, paused; `dispatch play` went `PLAYING` at that position. `CAR.AUDIO.MEDIA` granted bottom-half streaming; emulator `pcmRead` I/O warnings remain. Auto Dashboard (`screencap -d 11529215047558456619`, 304×390) and Facet (`11529215048179745490`, 800×80) captured Velin Now Playing; integer display id `-d 3` is invalid.

Disconnect/re-pair isolation was skipped to keep the current pairing. A reachable-server reboot was not re-run (the unreachable-server reboot already restored paused state). Signed physical-phone validation, Bluetooth, and a visible lock-screen UI remain.

Validate a signed release build on a physical phone:

1. Start a multi-track queue, seek into a track, enable shuffle/repeat, pause, kill the process, relaunch, and compare all state.
2. Repeat while playing; after process reconstruction it must be paused and must not emit audio until Play.
3. Repeat after device reboot.
4. Verify Play resumes near the saved position and automatic advance still works.
5. Verify Clear followed by process death restores nothing.
6. Verify disconnect/re-pair cannot expose or restore the previous device's queue.
7. Verify a 500-item queue restores promptly without metadata requests or excessive memory.
8. Verify notification, lock screen, Bluetooth, and Android Auto resumption.
9. Verify FLAC and MP3 entries and artwork fallback.

Measured emulator restore times, PSS, and position error are recorded above. `ARCHITECTURE.md`, `DECISIONS.md`, `PROGRESS.md`, `ROADMAP.md`, and `android/README.md` now describe that implemented emulator-validated behavior. Remaining: signed physical-phone matrix, optional disconnect/re-pair, Bluetooth, and a visible lock-screen UI.

## Acceptance criteria

The work is complete when:

- relaunch after process death and reboot exposes the prior bounded queue, item, position, shuffle, and repeat state;
- no restoration path autoplays;
- Play from phone, notification, Bluetooth, and Android Auto works from the restored state;
- no metadata network request is required to reconstruct the queue;
- Clear and disconnect delete the correct checkpoint and stale writes cannot recreate it;
- manual Save/Load remains independent;
- persisted data contains no secrets or arbitrary URLs;
- unit tests, Android lint, debug/release builds, and physical-device scenarios pass.

## Recommended implementation order

Implement packages 1–3 first and validate phone relaunch behavior. Then add Media3 callback integration in package 4, lifecycle edge cases in package 5, and complete the physical-device matrix and documentation in package 6. Keep each package buildable and tested; do not defer Clear/disconnect safety until after rollout.
