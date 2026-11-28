# Vega TV 1.2.0 — keyboard, quality/server crash, buffering, skip intro/outro

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

Context: 1.1.9 fixed the player-open crash and the WAF cursor, but three
problems remain. The WAF keyboard is slow or does not appear (the viewer says it
behaves like 1.1.6, while 1.1.7 worked). Changing quality or server can crash the
app. High-bitrate playback still buffers. The viewer also asked for the official
client's "skip intro"/"skip outro" button. Each is handled separately and the
APK is built only after everything is compile-verified.

No device is attached during this work (`adb devices` is empty and the LAN scan
finds no TV), so every step is compile-verified locally and marked [B] only
after the user's device test.

## Flow

1. [x] WAF keyboard: compare 1.1.7 (confirmed working) with 1.1.8/1.1.9 and
   identify the show-path changes that make the keyboard slow or absent.
2. [x] WAF keyboard: restore the 1.1.7 show/reveal/layout path and keep the
   1.1.9 cursor restore unchanged.
3. [x] Compile-verify the keyboard change (`:app:compileDebugKotlin`,
   BUILD SUCCESSFUL in 1m 17s, only the pre-existing deprecation warnings).
4. [x] Quality/server crash: trace every release/rebuild path, prove the crash
   candidate, fix defensively.
5. [x] Compile-verify the crash fix (`:app:compileDebugJavaWithJavac`,
   BUILD SUCCESSFUL in 1m 30s).
6. [x] Buffering: re-read Media3's allocator and the official `RNVLoadControl`;
   prove whether the byte gate can stop the loader permanently.
7. [x] Buffering: fix the proven cause (single connection below the film's
   bitrate) without undoing the official 5 s/2 s policy.
8. [x] Compile-verify the buffering fix (`:app:compileDebugJavaWithJavac`,
   BUILD SUCCESSFUL in 1m 54s).
9. [x] Skip intro/outro: read the official client's merge, validation, label,
   active-interval and seekbar rules.
10. [x] Skip intro/outro: port every source our models can carry, fix the label
    and the seekbar gaps.
11. [x] Compile-verify the skip changes (`:app:compileDebugJavaWithJavac`,
    BUILD SUCCESSFUL in 2m 56s, then 2m 8s after making the `skips` aliases
    nullable so a provider's explicit `null` cannot break parsing).
12. [x] Build `:app:assembleDebug` (BUILD SUCCESSFUL in 2m 8s); verified
    versionCode 12 / versionName 1.2.0, v1+v2 signature, 23,666,317 bytes,
    SHA-256 `B1B11A321A3CE7FF02DA4D87AC134A3C4983FC8C2A347FBC83B4B1C71149F485`,
    and `BufferSignals`/`skipLabel`/`collectSkips` in the DEX.
13. [ ] Device test: keyboard, quality/server switching, remux playback, skip
    intro/outro.

## 1. WAF keyboard — evidence

The viewer's reference is explicit: the keyboard worked in 1.1.7 and 1.1.9
behaves like 1.1.6 (it is slow to appear or never appears). That makes 1.1.7 the
only confirmed-good keyboard, so its show path is the target.

The 1.1.7 → 1.1.8 diff replaced that path:

| 1.1.7 (worked) | 1.1.8 / 1.1.9 (slow or absent) |
| --- | --- |
| `showIme()` always calls `requestFocus()` | only calls it when `hasFocus()` is false |
| `beginEditing()` shows on every entry | shows at most once per 600 ms |
| 12-pass loop re-asserts the IME on 10 passes while the page still reports an editable field | two focus-free `showImeSoft()` calls at passes 2 and 5 |
| `adjustResize` + `imePadding()` + visible-frame fallback | `SOFT_INPUT_ADJUST_NOTHING` + a probe publishing the inset to the page |
| reveal band derived from the viewport shrink | reveal band derived from `__vegaImePx` |

The two show-path changes explain the symptom directly. A TV IME often creates
its input connection after the first `showSoftInput`, so the show has to be
re-asserted for as long as the field is editable. 1.1.8 reduced that to two
focus-free attempts and also skipped `requestFocus()` when the WebView had view
focus, which is not the same thing as the window's focused view on TV firmware.
The keyboard then waits for a D-pad key or never comes up.

1.1.9 did not change that show path. It added the cursor recovery, which the
viewer confirms works after BACK.

## 2. WAF keyboard — fix

- Restore the 1.1.7 show path exactly: unconditional `requestFocus()` in
  `showIme()`, an unthrottled show on every `beginEditing()`, and the 12-pass
  reveal loop that re-asserts `showIme()` while `ACTIVE_TEXT_FIELD_JS` still
  reports an editable.
- Restore the 1.1.7 layout: `adjustResize` + `imePadding()` with the
  visible-display-frame fallback, and the reveal band that distinguishes a
  shrunken viewport from an overlaying keyboard.
- Keep the confirmed cursor restore: the pre-IME BACK capture, the
  window-focus recovery, the 4 s re-adoption suppression and the
  `requestFocus()` in `finishEditing()`. The window-focus recovery now
  requires the keyboard window to have been away for at least 1.5 s, so the
  IME's own opening bounce cannot restore the cursor (and take focus back)
  while the keyboard is still coming up.
- Remove 1.1.9's API 30+ "three hidden polls" recovery. 1.1.7's own comment
  identifies that exact "seen then unseen" path as the change that called
  `endEditing()` and hid the keyboard the viewer was typing on — the 1.1.6
  regression 1.1.7 removed. 1.1.9 reintroduced it. The cursor still restores
  through the two paths that do not depend on a false inset: the pre-IME BACK
  capture and the window-focus return.
- Compile-verified with `:app:compileDebugKotlin`, BUILD SUCCESSFUL in 1m 12s
  (only the pre-existing deprecation warnings).

## 3. Quality/server crash — evidence and fix

Changing quality or server rebuilds the Media3 item and closes the old
`ParallelRangeDataSource` while its chunk workers are still running.

The old engine had two independent ways to kill the process:

1. `Engine.close()` snapshotted and cleared `activeSources` under the engine
   lock, then closed the sources *outside* it. A worker inside `source.read()`
   or `source.open()` therefore had its OkHttp call closed under it, and
   OkHttp/Okio answers that with an unchecked `IllegalStateException("closed")`.
   `download()` caught only `IOException`, the cached `WORKERS` pool had no
   uncaught-exception handler, and Android's default handler kills the app.
   The same race fired on a parallel fallback, because `read()` called
   `engine.close()` after the engine had already thrown.
2. `download()` added a new source to `activeSources` outside the lock. A
   source added after `Engine.close()` had cleared the set was never closed by
   the engine, and its worker could open a request into a source the engine had
   already closed.

Fix, without touching the buffer policy or the single-request path:

- `ManagedSource` owns each chunk request and closes it through an
  `AtomicBoolean` CAS, so the worker and `Engine.close()` can race but the
  underlying source is closed exactly once.
- Sources are added under the engine lock only while the engine is not closed;
  a source added before `close()` is guaranteed to be in its snapshot.
- `Engine.download()` catches `RuntimeException` as a failed chunk instead of
  letting it reach the thread's uncaught handler. `runChunk()` adds a
  last-resort `Throwable` guard that marks the fallback and wakes the reader.
- Every worker thread gets an uncaught-exception handler as a final net.
- `Engine.read()` validates the chunk offset before `System.arraycopy`;
  `readBody()` rejects a source that returns more bytes than requested.
- A runtime failure in `Engine.read()` now degrades to one connection through
  the same `fallBackToSingle()` path as a deliberate fallback; unchecked
  failures from `open`, `read`, `close`, the range probe and `reopenSingle` are
  converted to `IOException`, so they surface as a normal playback error.

The cache of URL states and the 1.1.8 load control are unchanged.

Audit of the remaining paths: every worker entry point (`runChunk`, `download`,
`ManagedSource.closeQuietly`) catches unchecked failures, every close path
tolerates a source that is already closed, and `open`, `read`, `reopenSingle`
and `close` convert unchecked failures into `IOException`. No path on the
quality/server switch can now reach Android's default uncaught handler.

## 4. Buffering — evidence and fix

The 1.1.9 load control was not the cause. Media3 1.4.1's
`DefaultAllocator.release()` decrements `allocatedCount`, and
`getTotalBytesAllocated()` is `allocatedCount * individualAllocationSize`.
It therefore reports bytes **currently held**, not a high-water mark. The 5 s
byte gate re-opens as playback consumes the buffer; it is a read-ahead cap, not
a one-way stop. The earlier "sticky byte budget" theory is disproven.

The official client's `RNVLoadControl` uses the same two gates, and 1.1.9
matched them:

- stop when the forward buffer is full and more than 5 s are buffered;
- stop when the heap reserve is exhausted and more than 2 s are buffered.

Those gates, the 8 s / 10 min / 1.5 s / 3 s time policy, the 5 % heap reserve
and `targetBufferBytes = C.LENGTH_UNSET` all stay. An 8 s minimum was rejected:
at 90 Mbps a 64 MB budget only holds ~5.7 s, and forcing 8 s would overshoot a
low-RAM stick's budget to ~90 MB.

The remaining failure is throughput, not buffering policy: when one connection
delivers less than the film consumes, the buffer drains no matter how the loader
is tuned. The parallel range source already existed, but it only switched after
a slow **open** or a failed chunk; a connection that opened quickly and then
delivered below the bitrate was never measured.

Fix:

- `BufferSignals` publishes the selected video+audio bitrate from
  `onTracksSelected` and counts genuine network stalls. Media3 sets
  `rebuffering` only on a READY → BUFFERING transition while playing, so opening
  a film or seeking cannot be mistaken for a stall.
- `ParallelRangeDataSource` measures the single connection's real delivery rate
  inside `read()`: only time spent waiting for bytes counts. A link faster than
  the film parks the loader between refills, so its bursts measure high; a link
  slower than the film keeps the loader active and measures the true rate. This
  avoids switching a healthy film merely because the player consumes at
  playback speed.
- It switches to parallel ranges when the measured rate stays below
  `max(1.5 MB/s, bitrate × 1.25)` for a 1.5 s / 1 MB window, and immediately on
  a genuine stall with less than 2 s buffered.
- The existing 2 s / 16 MB / 115 % gain verification stays. A switch that does
  not deliver the gain is remembered per URL as `parallelUnhelpful`, and
  `fallBackToSingle()` remains the path for chunk or engine failures.
- `onTracksSelected` reads the selection array through a nullable view, because
  Media3 can legally pass null elements even though Kotlin types the override's
  elements as non-null.

A switch happens at the exact byte the player reached, so nothing is downloaded
twice and no buffered data is lost. Known tradeoff: the 1.5 MB/s absolute floor
can trigger an unnecessary switch on a healthy low-bitrate film served at about
1 MB/s. The 115 % verification and the per-URL memory bound the cost, and the
switch itself does not interrupt playback.

## 5. Skip intro/outro — official parity

The official client merges skip intervals from five places, in this order:

1. the active episode's `skip` and `skips`;
2. the selected stream's `skip` and `skips`;
3. the direct-link card whose `link` equals the active episode's link;
4. a matching download's `skip`;
5. a `skips_<key>` cache, used only when nothing above produced an interval.

An interval is kept only when `to > from` and `from >= 0`; duplicates within
one second are merged; a missing title becomes `Intro`; the list is sorted by
start time. The active interval is `currentTime >= from && currentTime < to`.
The button seeks to `to`. Its label is the title as-is when it already starts
with "skip" (case-insensitive), otherwise `Skip <title>`, and `Skip Intro` when
there is no title. The seekbar draws a black boundary mark at both ends of each
interval (only when the boundary lies strictly inside the track), so the skipped
section reads as a gap.

What our app had and what was wrong:

- `SkipChip` already showed an active interval and seeked to its end, but its
  label was always `Skip <title>`, so a provider title of "Skip Intro" rendered
  "Skip Skip Intro".
- Only the singular `skip` field existed. `ignoreUnknownKeys = true` silently
  discarded the plural `skips` alias that some providers send.
- `DetailViewModel` rebuilt direct-link episodes as
  `EpisodeItem(title, link, image)` and dropped their skip metadata, so
  direct-link playback had no button at all.
- The chip polled once a second, so it could appear a second late, and it
  recomposed on every tick.
- The scrubber drew a single amber tick at the interval start, not the official
  two black boundary gaps.
- Intervals were not validated or merged with the official one-second
  tolerance.

Fix:

- `VegaStream`, `DirectLink` and `EpisodeItem` now carry both `skip` and
  `skips`; the player merges both.
- Direct-link cards copy their `skip`/`skips` into the `EpisodeItem` they
  become, and `collectSkips` also matches the playing link against
  `PlaybackSession.info.linkList` exactly as the official client does.
- `collectSkips` applies the official validation, one-second dedupe, default
  `Intro` title and start-time sort.
- `SkipChip` polls at 250 ms but writes state only when the active interval
  changes, so it appears promptly without recomposing four times a second. The
  press hides the chip immediately and seeks to `to`.
- `skipLabel` implements the official prefix rule.
- The scrubber draws the official pair of black boundary marks instead of one
  amber tick.

Not ported, deliberately: the download source and the skip cache. This app has
no in-app download store, and every playback resolves fresh metadata, so the
cache's only job (offline/downloaded playback) does not exist here. Adding a
stale cache would risk showing an interval from a different encode.

## 6. Verification

- Keyboard: `:app:compileDebugKotlin` BUILD SUCCESSFUL; the 1.1.7 → current
  diff is the 1.1.7 show/layout/observer code plus the cursor additions only.
- Crash: `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL; every worker entry
  point and close path catches unchecked failures.
- Buffering: full Kotlin + Java compile BUILD SUCCESSFUL in 1m 54s after the
  nullable-selection guard.
- Skip: full Kotlin + Java compile BUILD SUCCESSFUL in 2m 56s, and again in
  2m 8s after the nullable `skip`/`skips` change. The nullable type is
  deliberate: kotlinx.serialization's `coerceInputValues` is off, so a
  provider sending `"skips": null` would otherwise fail the whole episode or
  stream list.
- APK: `app-debug.apk`, versionCode 12, versionName 1.2.0, 23,666,317 bytes,
  SHA-256 `B1B11A321A3CE7FF02DA4D87AC134A3C4983FC8C2A347FBC83B4B1C71149F485`,
  v1 + v2 signed with the same debug certificate
  (`5595d8d0…b22252`) used since 1.1.4. `classes10.dex` contains
  `BufferSignals`, `VegaLoadControl`, `ParallelRangeDataSource`, `skipLabel`
  and `collectSkips`.
- Released as tag `v1.2.0` (commit `b6bcc9c`):
  https://github.com/aashish1332/Vega-tv/releases/tag/v1.2.0 with asset
  `app-debug.apk`, label `VegaTV-1.2.0.apk`.
- Device verification is pending the user installing that APK. Items 13 and
  the `[B]` marks stay open until then.
