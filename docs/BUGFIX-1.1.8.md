# Vega TV 1.1.8 — load control, parallel gate, 4K revert, WAF keyboard

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

Context: 1.1.7 aligned the buffer durations with the official client but kept
the stock `DefaultLoadControl` byte ceiling and a measured parallel threshold
derived from file size. High-bitrate 4K still buffered, and the WAF keyboard
still vanished after a text box was clicked. 1.1.8 replaces the load control
with the official stop rules, lowers the parallel gate to the official 1.5 MB/s
floor, reverts the 4K renditions hiding, and restores the keyboard technique
that worked before 1.1.4.

No device is attached during this work, so every step is compile-verified
locally and marked [B] only after the user's device test.

## Flow

1. [x] Re-read the official `RNVLoadControl`, its `DefaultLoadControl`
   constructor and the decompiled Media3 1.4.1 `DefaultLoadControl`.
2. [x] Confirm the 1.1.7 byte ceiling could stop the loader below the 8 s
   minimum on a high-bitrate film.
3. [x] Add `VegaLoadControl` with the official stop rules and wire it into the
   player; keep the same heap/RAM-derived budget.
4. [x] Replace the size-derived parallel threshold with the official
   `SLOW_BYTES_PER_SECOND = 1_500_000L`, measured only while blocked in
   `read()`.
5. [x] Revert the 4K limitation: every provider quality is offered again and
   the panel size cap is restored; delete `DisplayCapability`.
6. [x] WAF: restore `SOFT_INPUT_ADJUST_NOTHING` and position the page only by
   scrolling; never resize the platform WebView while the IME opens.
7. [x] WAF: publish the real IME height to the page from a zero-sized insets
   probe (API 30+), keep the legacy visible-frame fallback, keep the top-band
   fallback when no inset exists.
8. [x] WAF: throttle show requests, never adopt a disappearing IME, and force
   the active field into the band for ~2.4 s with focus-free rescues.
9. [x] Compile-verify, build, commit, tag and publish 1.1.8.

## 1. Why 1.1.7 could still buffer

- 1.1.7 used `DefaultLoadControl` with `prioritizeTimeOverSizeThresholds`
  false. In Media3 1.4.1 that makes the allocated-byte target a hard ceiling:
  once the allocator reaches the budget, `shouldContinueLoading()` returns
  false even when the buffered duration is far below `minBufferMs`.
- The budget is 64–256 MB. At 90 Mbps a 64 MB budget is only about 5.7 s, so a
  4K remux reached the ceiling below the 8 s minimum. The loader stopped, the
  player drained those 5.7 s, and playback stalled — repeatedly.
- The 1.1.7 parallel gate required `max(16 MB/s, total / 5400)`. Measurement
  only advances while the player actually reads, and a healthy player reads at
  playback speed once its buffer is full: a 90 Mbps film measures ~11 MB/s, not
  the link's capacity. The gate therefore classified most 4K films as "fast
  enough" and never engaged parallel ranges, while a genuinely starved
  connection could still sit above the size-derived floor on a smaller file.

## 2. `VegaLoadControl`

- Same durations as before and as the official client: 8 s minimum, 600 s
  maximum, 1.5 s to start, 3 s after a rebuffer, no back buffer.
- `targetBufferBytes = -1` and `prioritizeTimeOverSizeThresholds = true`: the
  stock byte target no longer stops the loader below the time minimums.
- The heap/RAM budget remains the read-ahead cap, but it is now a release
  valve rather than a hard floor: the loader stops at the cap only when more
  than 5 s is already buffered, so it never quits with almost nothing.
- Free-heap reserve: below 5% of `maxMemory` the loader stops only when more
  than 2 s is buffered. The process is protected without starving playback.
- Everything else defers to `DefaultLoadControl`, so allocations, eviction and
  the time-based max still behave exactly as Media3 intends.

## 3. Parallel gate

- The threshold is the official constant `1_500_000L` bytes/s (~12 Mbps).
  Below it no video bitrate can be sustained, so a switch is unambiguous.
- Measurement accumulates only the time spent blocked inside `read()` and the
  bytes that read returned, in 3 s / 512 KB windows. Time the player spends
  not reading (full buffer, decoding) no longer dilutes the rate, so the
  measurement reflects the connection rather than playback consumption.
- The gate still runs on every open with at least 16 MB left, still switches at
  the exact byte the player reached, still verifies that chunks beat the single
  request by 15% before remembering a URL as parallel, and still lowers a
  host's connection count on 429/503 and falls back safely on any chunk error.
- Small HLS segments, subtitles, playlists and the bounded Matroska probes stay
  single-request.

## 4. 4K revert

- The 1.1.7 restriction is removed. The player Quality tab and the detail-page
  quality cards list every rendition the provider published, exactly as they
  did before 1.1.7.
- The video track cap is `setMaxVideoSize(displayWidth, displayHeight)` again,
  so the player selects the best track the panel can present.
- `DisplayCapability.kt` is deleted; no code reads display modes any more.
- The 1.1.7 note that hid 4K on non-4K panels is marked reverted there.

## 5. WAF keyboard

- Root cause: 1.1.3 kept the window at its own size with
  `SOFT_INPUT_ADJUST_NOTHING`; 1.1.4 replaced that with `imePadding()` and
  manual IME padding, which resizes the platform WebView while the IME opens.
  On several TV firmware builds that resize ends the input connection and the
  keyboard closes a moment after it appeared. 1.1.8 restores
  `ADJUST_NOTHING` and does all positioning by scrolling the page.
- API 30+ publishes the real IME height in CSS pixels as `window.__vegaImePx`
  from a zero-sized insets probe added to the decor view. The probe reads the
  inset without touching Compose's own inset handling.
- API 29 and TV firmware that reports no IME inset use the visible display
  frame. When even that does not shrink (because the window does not resize),
  the page uses a top-40% band, which sits above every TV keyboard.
- The page keeps the active field between a 6% top margin and the keyboard
  edge (or 40% of the viewport when the keyboard height is unknown). A field
  hidden behind page scroll is brought in with `scrollIntoView`.
- `beginEditing` requests `showIme()` at most once per 600 ms and injects the
  bridge idempotently. The reveal loop runs 12 forced passes over ~2.4 s and
  issues two focus-free `showImeSoft()` rescues; it never requests focus again.
- The IME observer may adopt a keyboard it can actually see, but nothing hides
  the keyboard: only BACK/MENU ends editing. A disappearing IME is not treated
  as the viewer closing it.
- Soft input mode is restored on dispose, and `__vegaImePx` is cleared so a
  later page does not inherit a stale band.

## 6. Build and release

- `:app:compileDebugJavaWithJavac`, then `:app:assembleDebug`.
- Verify versionCode 10 / 1.1.8, the same debug certificate, v1/v2 signatures,
  the new strings in the DEX, size and SHA-256.
- Commit, push, tag `v1.1.8`, publish the APK asset and its digest.

Verified build:

- `:app:assembleDebug` BUILD SUCCESSFUL in 1m 47s (2026-11-24).
- APK `app/build/outputs/apk/debug/app-debug.apk`, 23,658,875 bytes
  (22.6 MiB).
- `aapt dump badging`: `tv.vega.tv`, versionCode 10, versionName 1.1.8,
  minSdk 21, targetSdk 34, launchable `tv.vega.tv.MainActivity`.
- `apksigner verify`: v1 JAR true, v2 true; signer
  `C=US, O=Android, CN=Android Debug`, certificate SHA-256
  `5595d8d09c8b7e8cd9b261fb583a844889ec2957d23ee6e98ebefdc1d3b22252`
  (the same debug key used for 1.1.4–1.1.7, so it updates in place).
- DEX strings present: `VegaLoadControl` and
  `MIN_BUFFER_AT_BYTE_CAP_MS` (classes10/13), `__vegaImePx` (classes7),
  `VegaParallelStream`, `single - measuring`, `parallel `, `MB/s (single`,
  `single - parallel did not help` (classes13). `DisplayCapability` and
  `playableQualityLinks` are absent, which confirms the 4K revert.
- SHA-256
  `3CF802B341AF8CEBFCB6B8CC96591356FC4043B3A64F7A8C448EF6C0417705C9`.
- Released as https://github.com/aashish1332/Vega-tv/releases/tag/v1.1.8
  (asset `app-debug.apk`). The uploaded asset reports the same size
  (23,658,875 bytes) and SHA-256.

## Device test for 1.1.8

1. Play the 89 Mbps remux. Open Stats: the `Source` line must change from
   `single - measuring` to `single - N MB/s` or `parallel 4x - … MB/s (single …)`.
   Send that line and say whether it still buffers.
2. Play a normal episode and a 4K title: every quality the provider offers must
   appear in the Quality tab, and a 4K rendition must be selectable again.
3. Open the challenge, focus a text field, and type. The keyboard must stay up
   until BACK, and the field must sit between the toolbar and the keyboard
   while typing, including after moving between fields.
