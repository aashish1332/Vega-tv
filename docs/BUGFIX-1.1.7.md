# Vega TV 1.1.7 — playback for every video, challenge keyboard

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

Context: 1.1.6 engaged parallel range requests deterministically for files whose
proven size was 4 GiB or more, yet the 80+ GB film still buffered and the
keyboard still disappeared after a text box was clicked. 1.1.7 replaces the
size-triggered engagement with a measured gate that runs on every video, and
removes the paths that could hide the IME.

No device is attached during this work, so every step is compile-verified
locally and marked [B] only after the user's device test.

## Flow

1. [x] Re-read the official parallel engine, OkHttpDataSource and the media
   load control.
2. [x] Replace the 4 GiB deterministic gate with a measured gate.
3. [x] Apply the gate to every video, not only 80+ GB files.
4. [x] Keep the switch at the exact byte the player reached; verify the gain.
5. [x] Persist the decision per URL and per host; fall back safely.
6. [x] Bound chunk memory and align the load control with the official client.
7. [x] Add the source line to Stats for on-device evidence.
8. [x] WAF: never hide the IME from polling or DOM blur; re-assert it while
   an editable is focused; keep the field above the keyboard.
9. [x] 4K renditions are offered only when the panel exposes a 4K mode.
10. [x] Compile-verify, build, commit, tag and publish 1.1.7.

## 1. Why 1.1.6 could fix nothing

- 1.1.6 switched on file size alone: a proven total of at least 4 GiB. A 2 GB
  episode, a large HLS byterange segment, or any title whose server did not
  expose a 4 GiB total never entered the engine, no matter how slowly it
  delivered bytes.
- The keyboard work still had a path that called `endEditing()` from a polled
  IME reading. On TV firmware `imeVisible()` can be false while the keyboard
  is visibly up, so that path could hide the keyboard the viewer was typing
  on. A DOM blur during a re-render could do the same.

## 2. The measured gate (all videos)

- Every open that has at least 16 MB left to stream is eligible — a 300 MB
  episode gets the same treatment as an 80 GB remux. The size of the file is no
  longer a trigger; only the measured delivery rate is.
- The first response stays on the caller's normal, header-aware factory and is
  used for playback. A 206 proves ranges and gives the total. A 200 at
  position 0 is trusted when `Accept-Ranges: bytes` is present. A server that
  sends neither gets one `Range: bytes=0-0` probe at byte 0. A 200 at a
  non-zero position means the server ignored the range and the URL is marked
  unsuitable forever.
- A whole-file response with no `Content-Length` (chunked transfer) now uses
  the same byte-0 probe to discover the total, instead of giving up.
- Measurement runs in rolling 3 s / 512 KB windows for as long as the open is
  on one connection, so a connection that starts fast and is then shaped still
  switches.
- The switch threshold is `max(16 MB/s, total / 5400)`. 16 MB/s is 128 Mbps, a
  comfortable margin over a 4K remux; the size term covers files longer than
  90 minutes. A connection delivering 5 MB/s switches for a 90 Mbps film.
- The switch starts at the exact byte the player has reached. Nothing is
  downloaded twice and no buffered byte is lost. A URL that already proved a
  gain engages immediately on a later open or seek, so playback does not pay
  the measurement again.
- Small bounded opens stay single-request: the 64 KB Matroska cue/header
  probes, subtitles, playlists and small HLS segments. A large HLS byterange
  segment (16 MB or more remaining) is measured like any other video data,
  because the 206 that delivered it has already proved ranges work.

## 3. Gain verification and safe fallback

- While chunks run, the engine accumulates network bytes and read time. After
  at least 2 s and 16 MB, it compares the aggregate chunk rate with the single
  rate measured at the switch. If parallel requests are not at least 15%
  faster, the URL is remembered as single and no later open tries parallel
  again; the current chunks keep running so the film does not stall a second
  time. If they are faster, the URL stays remembered as parallel.
- A 429 or 503 lowers that host's connection limit to the number of requests
  still working, never below two on the first cut and one afterwards. A limit
  of one falls back to the single request. Bursts within 3 s count as one cut.
- A chunk response without `Content-Range` is definitive proof that the server
  ignored the range; the URL becomes unsuitable and the open continues on one
  request. A shortened 206 is not an error: the rest is requested from the
  next byte.
- Any unexpected chunk failure falls back to a single request from the current
  byte, with the normal 429/503 retry. The fallback clears the remembered
  parallel flag so the next open measures again.

## 4. Memory and load control

- Chunks ramp 1 → 2 → 4 → 8 MB. The in-flight window is
  `clamp(heap / 8, 16 MB, 48 MB)`, and the connection count is reduced to what
  that window can feed. The first 512 KB is confirmed with two chunks, then the
  window opens up. No `largeHeap` is requested.
- Load control matches the official client:
  `setBufferDurationsMs(8_000, 600_000, 1_500, 3_000)` with the existing
  256 KB allocator, 64–256 MB byte budget, no back buffer, network wake mode
  and four-error policy. Because `prioritizeTimeOverSizeThresholds` stays
  false, the byte budget is the true read-ahead cap.

## 5. Diagnostics

- `VegaParallelStream` logs one decision line per URL whenever the decision
  changes. Query strings are never logged.
- Stats (INFO/MENU ▸ Stats) now carries a `Source` line, for example
  `parallel 4x - 22.4 MB/s (single 6.1)`, `single - measuring`,
  `single - server ignores ranges`, `single - parallel did not help` or
  `single - host limit`. This line is the evidence needed from the next device
  test.

## 6. WAF keyboard

- The IME observer may adopt a keyboard it can see; it never ends editing.
  `imeVisible()` is unreliable on TV firmware, under `FLAG_LAYOUT_NO_LIMITS`,
  and for an IME in its own window.
- DOM focus is the reliable editing signal. A page script reports the editable
  state on `focusin`/`focusout`; a blur during a re-render or a field-to-field
  move no longer hides the keyboard. Only BACK/MENU closes it.
- While the page still reports an editable, the native side re-asserts
  `showIme()` for about 2.4 s after focus. This covers a page that re-renders
  an input and drops the input connection.
- The scroll band distinguishes a shrunken WebView (`innerHeight` fell by more
  than 10%: target 40%, band 8%–75%) from an overlaying keyboard (target 25%,
  band 6%–40%). The pre-keyboard height is recorded at load and before a tap,
  never overwritten by a shrunk reading.
- Forced reveals run on focus, IME open, resize and the timed passes; typing
  only re-checks the band, so the page does not jump on every key. If the page
  cannot scroll, the active field is handed to `scrollIntoView`.

## 7. 4K renditions

- `DisplayCapability` reads the panel's supported display modes, not
  `displayMetrics` (Fire OS renders its UI at 1080p on a 4K television). A
  mode with a long edge of 3400 or more means the panel is 4K-capable.
- On a non-4K panel the player Quality tab and the detail-page quality cards
  hide 4K/8K renditions. A rendition already playing stays visible, and a
  provider that published only 4K copies keeps them, so a title never becomes
  unplayable. The video-track cap also allows the larger track in that case.

## 8. Build and release

- `:app:compileDebugJavaWithJavac`, then `:app:assembleDebug`.
- Verify versionCode 9 / 1.1.7, the same debug certificate, v1/v2 signatures,
  the new strings in the DEX, size and SHA-256.
- Commit, push, tag `v1.1.7`, publish the APK asset and its digest.

Verified build:

- `:app:assembleDebug` BUILD SUCCESSFUL in 3m 40s (2026-11-20).
- APK `app/build/outputs/apk/debug/app-debug.apk`, 23,661,668 bytes
  (22.6 MiB).
- `aapt dump badging`: `tv.vega.tv`, versionCode 9, versionName 1.1.7,
  minSdk 21, targetSdk 34, launchable `tv.vega.tv.MainActivity`.
- `apksigner verify`: v1 JAR true, v2 true; signer
  `C=US, O=Android, CN=Android Debug`, certificate SHA-256
  `5595d8d09c8b7e8cd9b261fb583a844889ec2957d23ee6e98ebefdc1d3b22252`
  (the same debug key used for 1.1.4–1.1.6, so it updates in place).
- DEX strings present: `VegaParallelStream`, `single - measuring`,
  `parallel did not help`, `server ignores ranges`, `single - host limit`
  (classes13), `__vegaRevealForce` / `__vegaBaseVh` (classes7),
  `DisplayCapability` (classes4/10/13/14).
- SHA-256
  `6E6F2A06CF0D0B740FB6B53BA8B5BE315BE04C3926B86A369D55F053B226234D`.

## Device test for 1.1.7

1. Play a title that used to buffer. Open Stats: the `Source` line must say
   `single - measuring` first, then either `parallel 4x - … MB/s (single …)`
   or one of the single reasons. Send that line if it still buffers.
2. Play a normal-size episode and a 4K title: the same line must appear, which
   proves the gate is not limited to 80+ GB files.
3. Open the challenge, focus a text field, and type. The keyboard must stay up
   until BACK, and the field must sit between the toolbar and the keyboard.
