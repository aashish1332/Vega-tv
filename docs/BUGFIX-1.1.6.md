# Vega TV 1.1.6 — 80+ GB playback and the challenge keyboard

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

Context: 1.1.4 fixed the normal-bitrate stream that used to stall, and 1.1.5 added
ordered parallel range requests for very large files. An 80+ GB film still buffers.
Separately, the WAF/WebView keyboard opens and then closes on its own, and the
focused field can sit behind it.

No device is attached during this work, so steps are compile-verified locally and
marked [B] only after the user's device test.

## Flow

1. [x] Gather evidence for why 1.1.5 could stay on one connection.
2. [x] Engage parallel chunks deterministically for 4 GiB+ ranged files.
3. [x] Give every chunk a real socket (HTTP/1.1, not multiplexed HTTP/2).
4. [x] Discover the total size and range support with one byte-0 probe.
5. [x] Log exactly one decision line per URL for the next device test.
6. [x] WAF: adopt the keyboard from DOM focus; never poll it away.
7. [x] WAF: keep the focused field inside a fixed visible band.
8. [x] Compile-verify both modules.
9. [x] Bump to 1.1.6, build the APK, verify size/version/signature/digest.
10. [ ] Commit, tag, publish the release.

## 1. Evidence

- The 1.1.5 source starts on one request, measures four seconds of *blocked read
  time*, and switches only below 16 MB/s. A connection that delivers 20 MB/s
  (160 Mbps) stays single even if the film's peaks exceed it. More importantly,
  a server that answers the first response without `Accept-Ranges` or an exact
  `Content-Range` needs a probe; the probe only runs after the four-second
  measurement, so any probe failure silently keeps the film on one connection.
- `OkHttpDataSource` is built on `NetClients.http`, which leaves HTTP/2 enabled.
  Three chunk requests to the same host are normally three multiplexed streams
  on a single TCP connection. A server that shapes *connections* therefore sees
  no benefit at all: the "parallel" chunks share the same pipe as the single
  request they replaced. The official client's parallel source sits on
  `DefaultHttpDataSource` (HTTP/1.1), where each concurrent range is a separate
  socket.
- The current gate also requires the whole-file open to be unbounded and the
  initial response to carry a length. A chunked response (`Content-Length`
  absent) cannot switch even when a byte-0 range probe could discover the size.
- The player buffer matches the official client and is not the defect: on a
  2 GB box the budget is 64 MB (~5.7 s at 90 Mbps), the same as the official
  default, and 1.1.4 already removed the back buffer.

## 2. Deterministic engagement

- A progressive file whose proven total is at least 4 GiB, whose remaining open
  range is at least 16 MB, and whose host still allows more than one connection
  now starts the ordered range engine immediately after the first response.
  There is no four-second speed measurement and no "only if slow" gate.
- The engine still starts with two chunks until 512 KB has been delivered, then
  ramps to four. Chunks start at 1 MB and double to 4 MB. At most four chunks
  (16 MB) are held, well inside the 64 MB media budget on a 2 GB box.
- 429/503 handling is unchanged: a burst lowers the host limit, and a limit of
  one returns the open to the single request. A server that ignores a range
  still marks the URL unsuitable and falls back permanently.

## 3. Real sockets per chunk

- The single request, probes and fallback stay on the existing header-aware
  OkHttp/HTTP-2 client.
- The chunk engine gets a second, identical factory whose client is
  `NetClients.http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()`.
  Four concurrent chunk requests therefore open four HTTP/1.1 connections
  instead of multiplexing on one. Headers, cookies, DoH and timeouts are shared
  through one header snapshot, so provider authorization still applies.

## 4. Range and size discovery

- A 206 on the first open proves ranges and gives the total from
  `Content-Range`.
- A 200 on the first open at a non-zero position means the server ignored the
  range (Media3 would already have skipped those bytes); the URL is unsuitable.
- A 200 at position 0 is expected for a whole-file open. One `Range: bytes=0-0`
  probe then proves ranges and yields the total. It always starts at byte 0, so
  a range-ignoring server costs one response, never a whole-film skip.
- A chunked response with no length is handled by the same probe; if the total
  is still unknown, the open stays single.
- A response that already states a size below 4 GiB is never probed. HLS
  segments look exactly like that on every open, so the stream keeps its normal
  one request per segment and the log stays free of per-segment decisions.

## 5. Diagnostics

- One `VegaParallelStream` line is logged per URL whenever the decision changes:
  parallel (connections, total, start), single because ranges are not honoured,
  single because the file is below 4 GiB, single because the tail is short,
  single because the host was rate-limited, or single because the size is
  unknown. Query strings are never logged.

## 6. WAF keyboard lifecycle

- The 300 ms IME observer treated a negative `imeVisible()` reading as proof the
  keyboard had closed. On TV firmware, under `FLAG_LAYOUT_NO_LIMITS`, or when
  the IME is a separate fullscreen window, `imeVisible()` can report false while
  the keyboard is visibly up. After 2.4 s the observer called `endEditing()`,
  which really hid the keyboard: the observed "appears and disappears".
- DOM focus is the reliable signal, independent of insets. A page script now
  reports the editable state on `focusin`/`focusout` (after the event settles,
  so moving between two fields reports the second one). The native side adopts
  that state.
- The observer may still adopt an IME it sees. It only ends editing on a
  disappear when it previously saw that same IME; it never ends editing from a
  keyboard it has never seen. BACK remains the explicit close.
- A real blur (focus moves to a non-editable control) ends editing after a
  short re-check, so tapping a button closes the keyboard without a false
  negative closing it during typing.

## 7. WAF visible band

- The reveal targets 38% of `window.innerHeight`, with a band of 12%–50%. This
  is above a keyboard that covers the lower half whether or not the WebView
  itself shrank for the IME; no inset measurement is needed.
- The pre-tap scroll is restored once, then the field is moved only when it is
  outside the band, so typing and caret movement do not make the page jump.
- The reveal re-runs on IME show, `window`/`visualViewport` resize, `input`,
  `selectionchange`, and a short series of timed passes while the viewport
  settles.

## 8–10. Build and release

- `:app:compileDebugKotlin` and `:app:compileDebugJavaWithJavac`, then
  `:app:assembleDebug`.
- Verify versionCode 8 / 1.1.6, the same debug certificate, v1/v2 signatures,
  the probe/decision code present in the APK, size and SHA-256.
- Commit, push, tag `v1.1.6`, publish the APK asset and its digest.

Verified build:

- APK: `app-debug.apk`, 23,652,786 bytes, SHA-256
  `4FCF7BF40E8A332514C38D527014F58FC797295F3EA2ABEF59CA54E9A98E8EB9`.
- `aapt`: versionCode 8, versionName 1.1.6, minSdk 21, targetSdk 34.
- `apksigner`: v1 and v2 true, one signer, SHA-256
  `5595d8d09c8b7e8cd9b261fb583a844889ec2957d23ee6e98ebefdc1d3b22252`.
- DEX contains `VegaParallelStream` and `Parallel %dx range` in
  `classes13.dex`, and `__wafFocus` / `__vegaEditingBridge` in `classes7.dex`.

## Device test for 1.1.6

1. Play the 80+ GB title. In logcat, `VegaParallelStream` must print a
   `Parallel 4x range` line for its URL. Send the line if it still buffers.
2. Open the challenge, focus a text field, and type. The keyboard must stay up
   until BACK, and the field must sit in the upper-middle band above it.
