# Vega TV 1.1.3 — bugfix flow (device test of 1.1.2)

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

1. [x] Player: raw DRM / provider errors must not dead-end playback.
   - `ERROR_CODE_DRM_SCHEME_UNSUPPORTED / UnsupportedDrmException` came from a stream that
     declares a DRM scheme the box cannot open. Detect DRM-only streams before/at prepare,
     skip them, fail over to a non-DRM server, and never show the raw exception string.
   - HubCloud extract timeout is an upstream failure: surface it as one readable sentence and
     keep the other servers reachable.
   - Fix: `data/providers/ProviderErrors.kt` (`readableProviderError`) maps every provider
     failure class to one actionable sentence — timeout, empty result, wrong shape, runtime
     failure, module load, Cloudflare, HTTP 401/403/404/429/5xx, DNS/connection. Raw text and
     stack traces go to logcat, not the TV. Used by player resolve, detail, search and next
     episode failures.
   - Fix: `player/PlaybackErrors.kt` (`playbackErrorMessage`) maps Media3 error codes (HTTP
     status, decoder, container/manifest, cleartext, live window) and delegates DRM codes to
     `drmErrorMessage`, replacing `ERROR_CODE…: InvalidResponseCodeException` cards.
   - Fix: auto-failover and the "first server" (`preferBestQuality` off) pick now skip any
     rendition whose DRM this device cannot decrypt; when every rendition is unsupported the
     pre-prepare DRM check shows the readable reason with the server list and exit reachable.

2. [x] Browser cursor: visible but frozen at centre.
   - Root cause: the challenge was hosted in a Compose `Dialog`, i.e. a second window with its
     own key dispatch, while the cursor only listened on the WebView. Whenever the WebView did
     not hold Android focus (the usual case after a dialog opens, and always once a toolbar
     button or the Compose host took it) no key reached the pointer at all.
   - Fix: the challenge is now an overlay inside the activity window (the player underneath
     keeps composing, so a suspended provider call still resumes where it was blocked), and
     `WafKeyBus` hands every key to the browser before the focused view sees it. Pointer mode
     moves the cursor/taps; page mode forwards D-pad/OK to the WebView even when it is not
     focused, so the injected DOM navigation always runs. The toolbar yields (its buttons stay
     navigable), the IME owns keys while open, and BACK returns from the toolbar to the page.
   - Also: repeat now accelerates (30dp vs 18dp) instead of moving slower than a single press,
     and the overlay repaints only the cursor's old+new bounds instead of the whole screen.
   - Device check pending.

3. [x] Series episode model: four servers rendered as EP 1–EP 4.
   - Root cause: stale `rareanimes` bundle, never refreshed because the manifest version
     stayed `1.0`. Module version stamps (`// @vega-provider` / `// @vega-version`) are now
     validated; an installed file without the expected stamp forces a re-download.
   - Also fixed the real inverted rule: `episodesLink` now wins when it resolves episodes,
     `directLinks` only as fallback, and server/link groups no longer get an invented EP n.
   - Live module confirmed: `linkList -> {title:"Season 1", directLinks:[{title:"Episode N — name"}]}`.

4. [x] Seek is broken: ±10 s buttons and ±10 % scrubber restart playback at 0:00.
   - Root cause: provider streams are direct MKV, and Media3's MatroskaExtractor ignores a
     SeekHead entry that points to a *second* SeekHead. Muxers that write Cues only in that
     second SeekHead leave the extractor with `SeekMap.Unseekable`, and ExoPlayer answers a
     seek on an unseekable item by restarting at 0:00.
   - Fix: `player/MatroskaCueDataSource.kt` (port of the mobile app's
     `ChainedMatroskaDataSource`) walks the SeekHead chain with bounded range reads and
     rewrites the chained SeekID/SeekPosition in flight so Media3 sees a direct Cues entry.
     A 1-byte Range probe gates the chain walk so a range-ignoring server can never cause a
     whole-file skip. No-op for HLS/MP4/non-MKV.
   - Also: unseekable sources now show "Seeking is unavailable for this stream" (or the live
     variant) instead of silently restarting at 0:00; all seek entry points are gated on
     `isCurrentMediaItemSeekable`.
   - Byte-level simulation of the parser passed (chained SeekHead at rel 52 → Cues at rel 71,
     patched bytes `53 AB 84 1C 53 BB 6B` + SeekPosition = 71). Device check pending.

5. [x] Search: empty query shows `cannot find ""`; verify all installed providers contribute.
   - No request for a blank query; show a hint instead. Multi-provider merge + dedupe check.
   - Root cause (blank state): `SearchViewModel` started as `Success(emptyList())`, so the
     success branch rendered `No results for ""` before any search had run.
   - Fix: idle hint is a plain, non-focusable prompt (the query field already owns initial
     focus); `No results` is only shown for a query that actually ran. All-failed searches
     show the first readable provider reason and a retry; partial failures are stated next
     to the results instead of replacing them; an open challenge gets a "Solve challenge"
     action without hiding the rows other providers returned.
   - Fix (fan-out): `ProviderManager.searchTargets()` returns every installed provider,
     active first. `VegaRepository.search` asks each one with its own 25 s budget (upstream
     `runProviderSearches` value), isolates per-provider failures, dedupes by title across
     the whole fan-out (active provider's copy wins), and publishes results progressively as
     each provider answers.
   - Fix (origin): `VegaPost.origin` carries the `author:value` provider reference for each
     search row (app state, not part of the provider JSON contract, so it is `@Transient`);
     opening a result sets `PlaybackSession.pendingProvider` so the detail page resolves the
     link on the provider that actually listed it, not whichever was active.

6. [x] Build 1.1.3 APK, verify, commit, push, release.
   - `:app:assembleDebug` BUILD SUCCESSFUL; APK `app/build/outputs/apk/debug/app-debug.apk`,
     23,635,198 bytes, SHA-256 `7C5DF026FB60370871EE56A14AC56AD6AE5DA6DFBE28E310A57529ECC5D0C37D`,
     `versionCode 5` / `versionName 1.1.3`, minSdk 21, targetSdk 34, certificate
     `5595d8d0…b22252` (same key as 1.1.2).
   - Committed `8427f40`, pushed `main`, tagged `v1.1.3`, released:
     https://github.com/aashish1332/Vega-tv/releases/tag/v1.1.3 (asset `app-debug.apk`,
     23,635,198 bytes, uploaded).
   - Runtime feel checks (browser cursor, seek, search fan-out, episode list) still need a
     device pass: no Android device or emulator was attached during this build.
