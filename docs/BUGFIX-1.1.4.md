# Vega TV 1.1.4 — bugfix flow (device test of 1.1.3)

Status: [ ] pending · [~] in progress · [x] fixed & compile-verified · [B] verified on device

1. [x] Browser/WAF: text-field focus, BACK handoff, top-edge UI focus.
   - Tapping a text field shows the IME but the D-pad cursor stays active, so keys never
     reach the field and the top toolbar buttons cannot be reached.
   - Expected: tap a field → keep the IME up and type into the field (cursor suspended);
     BACK → dismiss IME and return focus to the cursor; when the cursor reaches the top
     edge, focus moves to the overlay's UI buttons.
   - After tapping a field the page scrolls to top; the focused field must stay visible
     between the keyboard and the app toolbar.
   - Implemented: explicit `editing` state shared by the activity key hook, the WebView
     key listener and the pointer; an IME observer that adopts a page-opened keyboard and
     restores the cursor when it closes; BACK routed through `WafKeyBus.onBack` before the
     view hierarchy; `adjustResize` + `imePadding()` with a pre-30 visible-frame fallback;
     JS save/restore of the page position plus a minimal reveal of the focused field;
     pointer UP at the top edge focuses the toolbar. Compile-verified.

2. [x] Network: large 4K HDR playback buffers too much on the CF storage server.
   - Root cause (not the server): the player kept a 5 s back buffer inside the same
     64 MB byte budget as the forward buffer. At 60–100 Mbps (a 4K HDR remux) that
     back buffer is 37–62 MB, so only 1–3 s of forward media remained. Playback
     drained it, rebuffered, and ExoPlayer then discarded the back buffer to
     unblock loading — the repeating stall. Content-specific, exactly as reported.
   - `PlayerScreen.kt` load control now: device-sized forward budget
     (`min(35% of app heap, RAM/32)`, floored at the old 64 MB, capped at 256 MB);
     no back buffer; start 1.5 s and resume-after-rebuffer 3 s (official client's
     values) instead of 2 s / 5 s. The 5 s resume threshold alone demanded 62 MB at
     100 Mbps — the entire old budget — just to show a frame again.
   - Checked and left alone: OkHttp already reuses the TLS connection across
     segment/range requests and sends `Accept-Encoding: identity` for media, so no
     header/timeout change was needed. Compile-verified.
   - Needs on-device confirmation: a large 4K HDR file on the CF server should now
     play with a ~5 s cushion on a 2 GB box and more on a larger-heap box, with a
     rewind re-fetching from the server instead of replaying from RAM.

3. [x] Build 1.1.4 APK, verify, commit, push, release.
   - `versionCode 6` / `versionName "1.1.4"`; `assembleDebug` clean.
   - APK `app-debug.apk`, 23,640,713 bytes, SHA-256
     `F45FA44D49E6A71C5B24BB30DF7810BB99CE61F4B1D8E62E76CC2C700B479DD0`,
     signed with the same debug certificate as 1.1.3
     (`5595d8d0…b22252`), so it installs over it.
   - `aapt` confirms `versionCode='6' versionName='1.1.4'`, minSdk 21, targetSdk 34.
   - Commit `47c2e02`, tag `v1.1.4`, release
     https://github.com/aashish1332/Vega-tv/releases/tag/v1.1.4 with the APK.
   - Still needs the user's device test for the two items above; no Android
     device/emulator was attached here, so runtime feel and network behaviour
     could not be measured locally.
