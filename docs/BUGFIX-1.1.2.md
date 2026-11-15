# Vega TV 1.1.2 — bugfix flow

Status key: [ ] pending · [~] in progress · [x] verified in code/build · [B] needs device build

1. [x] Player UI appears immediately on Play, before stream resolve.
   - Stage (`PlayerView` + `PlayerChrome`) is now always composed; resolve state lives inside it.
   - Loading pill ("Starting stream… · MENU for settings") replaces the old full-screen loader.
   - Resolve failure is a `PlaybackError` card over the stage (Retry / Solve challenge / Back);
     it does not replace the screen, and MENU still opens the panel on top.
   - Transport controls are gated on `hasMedia`, not on `UiState.Success`; the top bar is
     always up while the chrome is shown, so title/Exit/MENU exist from the first frame.
   - Failure cards (`playError`, `nextError`, `resolveError`) now own the remote: OK/D-pad reach
     their buttons instead of being eaten as play/pause.
   - Empty Server/Quality tabs say whether the stream is still resolving or the provider failed.

2. [x] Provider screen: "Last error: catalog: StandaloneCoroutine was cancelled" never surfaces.
   - `ProviderJs.invoke` catches `TimeoutCancellationException` first (real provider timeout,
     recorded), then `CancellationException` (viewer moved on: trace only, rethrown, not recorded),
     then generic failures. Realm stays warm for cancellation; each invoke resets in-page and
     orphan results are dropped by `invokeId`.
   - Same split in `ProviderManager` (catalog/genres, search, episodes, settings schema) and
     `ExtensionsViewModel.snapshot()`/`test()`; a diagnostic `test()` timeout still names its stage.

3. [x] Browser (WAF/WebView) pointer: visible, moves with D-pad, never covered.
   - Cursor is a native `PointerOverlay : View` added to the WebView's `FrameLayout` *after* the
     WebView, so it paints above every page layer and cross-origin challenge iframe.
   - No `evaluateJavascript` per move: the overlay moves in the same frame as the key event.
   - Pointer is the default mode; MENU (or BUTTON_Y) focuses the toolbar, DOWN/OK returns to the
     page; BACK switches Pointer → Page → history → close.
   - Cursor is re-shown on page start, size change and toolbar round trip; never pinned to (0,0).

4. [x] Continue Watching resumes the exact last quality, not "Auto".
   - `resumeEntry` already restores `preferredStreamKey`/`savedStreams`; `PlayerViewModel` selects
     the matching `identityKey()` on both fresh resolve and saved-stream fallback.
   - `PlayerChrome.currentEpLink` now falls back to `PlaybackSession.pendingLink` (a resume has no
     episode list), so the Quality tab ticks and the chip names the real rendition.

5. [x] Progress bar seek with D-pad while focused (±10%).
   - The whole timeline row is one full-width focus target (the 16dp thumb was unreachable).
   - `scrubberFocused` + LEFT/RIGHT → `scrubByFraction(±0.1)` of full duration, with the seek OSD;
     coalesced 350 ms seek and `clampMs = duration` so a 10% step is never truncated.

6. [x] Season/episode model parity with the official app.
   - Clicked-season default: when a show has multiple season groups, `pendingTitle` (the catalog
     card the viewer tapped) is matched by season number, then by group label; S1 is still the
     fallback for a plain series page.
   - Episode order: composite `(seasonNumberFromTitle(title) ?: 0, episodeNumber(title))` stable
     sort, so a flattened all-season list reads S1E1…S1En, S2E1… instead of interleaving seasons.
     Single-season lists and unnumbered titles keep provider order.

7. [x] Build APK, verify version/signature/hash, push, release.
   - `:app:compileDebugKotlin` clean (no warnings) after all items; `:app:assembleDebug` green.
   - APK: `versionCode 4`, `versionName 1.1.2`, minSdk 21, targetSdk 34.
   - Cert SHA-256 `5595d8d09c8b7e8cd9b261fb583a844889ec2957d23ee6e98ebefdc1d3b22252`
     (same as 1.1.0/1.1.1, so in-place update works); v1+v2 schemes verified.
   - Size 23,589,203 bytes; SHA-256 `C54CECC6D56AD82EE2DAC72D7999FBA7C7A4A3D980502DDA5370C6E41C6BBAC2`.
   - Committed, pushed to `main`, published as GitHub release `v1.1.2`.
