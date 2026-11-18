# Vega TV 1.1.5 — large-file buffering flow (device test of 1.1.4)

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

Context: 1.1.4 fixed the normal-bitrate stream that used to stall. A movie that is
80+ GB still buffers constantly. Only the network/buffering path may change.

## 1. [x] Investigate 80+ GB playback

Findings (evidence, not guesses):

- **The bitrate math.** 80 GB over a 2 h film is ~89 Mbps average; a 4K HDR remux
  peaks well above its average. At 90 Mbps, 1 s of media is 11.25 MB.
- **The buffer is already as large as is safe.** `mediaBufferBudgetBytes()` is
  `min(0.35 × heap, .RAM/32)`, floored at 64 MB. On a 2 GB box that is exactly
  64 MB (the `RAM/32` rule binds), i.e. only ~5.7 s at 90 Mbps. That matches the
  official client (`deviceMemory.ts`, same 0.35 / 32 shares) and 1.1.4 already
  removed the 5 s back buffer and lowered the restart threshold to 3 s. There is
  no safe fixed-buffer increase left that would cover a 90 Mbps film; on a box
  with a larger budget the complaint surviving 1.1.4 rules the buffer out anyway.
- **What is left is throughput.** One TCP connection is the only remaining
  ceiling. The official app's own downloader exists for exactly this reason
  ("Many servers limit speed per connection, so this multiplies the speed",
  `HttpDownloadModule.kt`), and it ships a streaming counterpart,
  `ParallelDataSource` (patch lines 1232–1884): a normal single request that
  measures itself, then continues over 2–4 ordered range chunks (1→8 MB),
  keeping the server light (small window, 429/503 per-host connection cuts,
  fall back to single on any error). It is off by default and only switches
  below `SLOW_BYTES_PER_SECOND = 1.5 MB/s` — a threshold that never fires for a
  90 Mbps stream, so porting it unchanged would not fix this case.
- **Why parallel ranges can raise the ceiling.** Per-connection shaping (the
  usual CDN/Worker proxy limit), bounded chunks on servers that cut long
  responses, and TCP-flow limits under loss/RTT all stop being a single-flow
  problem. The range path is proven by the official client and by this repo's
  downloader; the engine is ordered, so playback sees a normal byte stream.
- **Servers that ignore Range are safe.** The single open proves range support
  (`Accept-Ranges` / `Content-Range`) before a switch; each chunk re-checks its
  `Content-Range`, and a 200 to a range request permanently marks the URL
  single-connection. A 64 KB cue/header probe is too small to ever parallelise.
- **The request volume stays modest.** 4 MB chunks at 90 Mbps are ~2.8
  requests/s; each connection is closed when its chunk completes, and while the
  player's buffer is full nothing is requested.

Decision: port the official ordered parallel-range source with a **bitrate-scale
switch** instead of the 1.5 MB/s one, a **4 GiB file gate**, and a **smaller
memory window** (3 × 4 MB = 12 MB, safe next to the 64 MB media budget); keep
all 1.1.4 load-control settings untouched. Only `PlayerScreen.kt`'s data-source
stack changes, plus the new source file.

## 2. [x] Implement the fix

- [x] `ParallelRangeDataSource.java`: official ordered engine, faithful port.
      - switches after **4 s of accumulated read() time** on the single open
        (not wall clock: wall clock would count the player's idle pauses while
        its buffer is full and could report a fast link as slow), when the
        measured rate is below 128 Mbps (16 MB/s) and ≥ 16 MB remains;
      - only for a known file ≥ 4 GiB, HTTP GET, proven range support;
      - if the first response did not advertise ranges, one **1-byte probe at
        byte 0** proves them before switching. Media3's HTTP source answers a
        200 to a *non-zero* range by reading and discarding every byte before
        that position, so the probe must never start at the current position;
        a 200 to the probe costs one response and permanently keeps the URL on
        one connection;
      - chunks 1→4 MB, window 3, per-host 429/503 cuts, fall back to single;
      - logs the switch with the measured rate (`VegaParallelStream`).
      - uses only API-21-safe calls: the official `computeIfAbsent` /
        `updateAndGet` were rewritten as `putIfAbsent` and a CAS loop, since
        those methods only exist from Android 8 (API 24) and `minSdk` is 21.
- [x] Wire it under `MatroskaCueDataSource` in `PlayerScreen.kt`
      (`matroska(parallel(okhttp))`), so the cue/header and one-byte probes
      stay single-request and only the main progressive load is parallelised.
- [x] Leave the load control, WAF, UI, providers and every other file untouched.
- [x] Compile-verified: `:app:compileDebugKotlin` and
      `:app:compileDebugJavaWithJavac` both succeed (the new Java source is the
      first in `app/src/main`, so javac was checked separately).

## 3. [x] Build 1.1.5 APK, verify, commit, push, release

- [x] `versionCode 7`, `versionName "1.1.5"`, `minSdk 21`, `targetSdk 34`.
- [x] `:app:assembleDebug` succeeds; APK at
      `app/build/outputs/apk/debug/app-debug.apk`, 23,720,177 bytes,
      SHA-256 `FC2E95715AEDD9CAC8FA4884B0FD747A8E0F1B91A7C8F999FB12BBEB2926A5B4`.
- [x] `apksigner verify` passes (v1 + v2, one signer, same debug certificate
      SHA-256 `5595d8d0…b22252` as 1.1.4, so it installs as an update).
- [x] The packaged dex contains `ParallelRangeDataSource` and the byte-range
      probe code (`classes13.dex`, verified by string search).
- [x] Permissions unchanged: INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK,
      REQUEST_INSTALL_PACKAGES.
- [x] Published: https://github.com/aashish1332/Vega-tv/releases/tag/v1.1.5
      (`app-debug.apk`). GitHub reports the stored asset as 23,720,177 bytes
      with digest `sha256:fc2e95715aedd9cac8fa4884b0fd747a8e0f1b91a7c8f999fb12bbeb2926a5b4`,
      matching the local build byte for byte. (A direct download from the
      release CDN timed out from this network, so the API digest — computed by
      GitHub from the uploaded bytes — is the verification.)

### Device test (still required)

No device or emulator is attached here, so throughput and buffering cannot be
measured locally. On the 80+ GB film, check `adb logcat -s VegaParallelStream`:

- `One connection gives N KB/s over 4000 ms; using parallel range requests`
  means the switch fired; then playback should stop rebuffering.
- `Server does not honour byte ranges; keeping one connection` means the
  server answered the byte-0 probe with 200 and the app correctly stayed single.
- `Back to one connection: …` means the range path hit an error, retried as a
  single request, and playback continues (slower but correct).

HLS and other segmented sources are intentionally not parallelised; only a
progressive file of at least 4 GiB can switch.
