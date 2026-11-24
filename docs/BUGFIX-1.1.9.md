# Vega TV 1.1.9 - player-open crash and WAF cursor restore

Status: [ ] pending / [~] in progress / [x] fixed & compile-verified / [B] verified on device

Context: 1.1.8 fixed the WAF keyboard and the 4K buffer policy, but introduced
two regressions: the app force-closes the moment the player opens, and the WAF
pointer cursor does not come back after BACK closes the keyboard. Both are
addressed here without changing the 1.1.8 keyboard technique or the 1.1.8
buffer policy.

No device is attached during this work, so every step is compile-verified
locally and marked [B] only after the user's device test.

## Flow

1. [x] Read the 1.1.7 -> 1.1.8 diff for the player path, the WAF state machine
   and the load control; compare with the decompiled Media3 1.4.1 classes.
2. [x] Prove the player-open crash statically before changing code.
3. [x] Fix `VegaLoadControl`'s super-constructor argument order.
4. [x] Prove the WAF cursor loss statically from the state machine.
5. [x] Add a pre-IME BACK capture for the Fire TV keyboard, plus state-only
   recovery that never hides an IME on its own and never changes how the
   keyboard is shown or focused.
6. [x] Compile-verify both changes (`:app:compileDebugJavaWithJavac`).
7. [x] Bump to 1.1.9 (versionCode 11), build, verify the APK, commit, tag,
   push and publish.
8. [ ] Device test: open the player (no crash), play the remux, open a WAF text
   field, type, press BACK and confirm the cursor returns.

## 1. Player-open crash

### Root cause

`DefaultLoadControl` exposes a protected 9-argument constructor whose Java
parameter order is **not** the builder order:

```
(DefaultAllocator, minBufferMs, maxBufferMs, bufferForPlaybackMs,
 bufferForPlaybackAfterRebufferMs, targetBufferBytes,
 prioritizeTimeOverSizeThresholds, backBufferDurationMs,
 retainBackBufferFromKeyframe)
```

This was confirmed from the actual 1.4.1 artifact in the Gradle cache:

- `javap -c androidx.media3.exoplayer.DefaultLoadControl` shows local 2 checked
  against local 4 as `minBufferMs >= bufferForPlaybackMs`.
- `javap -c DefaultLoadControl$Builder` shows `build()` pushes `minBufferMs`,
  `maxBufferMs`, `bufferForPlaybackMs`, `bufferForPlaybackAfterRebufferMs`.

`VegaLoadControl` passed the builder order instead:

```kotlin
super(allocator, 1_500, 3_000, 8_000, 600_000, ...)
```

So the constructor received `minBufferMs = 1_500` and
`bufferForPlaybackMs = 8_000`. The assertion `minBufferMs >=
bufferForPlaybackMs` failed and threw `IllegalArgumentException` inside the
`ExoPlayer.Builder` chain, before playback could start. That is exactly "the
app crashes whenever the player opens", and it did not exist in 1.1.7 because
that version used `DefaultLoadControl.Builder`, whose setters are named.

### Fix

Reordered the positional arguments to the Java constructor order:

```kotlin
super(
    allocator,
    /* minBufferMs = */ 8_000,
    /* maxBufferMs = */ 600_000,
    /* bufferForPlaybackMs = */ 1_500,
    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
    /* targetBufferBytes = */ C.LENGTH_UNSET,
    /* prioritizeTimeOverSizeThresholds = */ true,
    /* backBufferDurationMs = */ 0,
    /* retainBackBufferFromKeyframe = */ false,
)
```

The values are unchanged: 8 s minimum, 600 s maximum, 1.5 s to start, 3 s after
a rebuffer, no back buffer, time priority. All six constructor assertions now
hold. `shouldContinueLoading(Parameters)` and the 5 s byte-cap floor, the 5%
free-heap reserve and the 2 s floor are untouched.

## 2. WAF cursor does not return after BACK

### Root cause

The 1.1.8 keyboard fix introduced an `editing` gate: the native cursor is
hidden on `beginEditing` and only `endEditing()` puts it back. On TV boxes the
keyboard is frequently its own window or activity. BACK then finishes the IME
window, the Activity never receives the key, and `editing` stays `true`. The
keyboard visually disappears while `finishEditing` never runs, so the cursor
stays buried. The same happens if a floating IME consumes BACK itself.

The old IME observer could not recover from this: it only adopted a visible
IME and deliberately never ended editing, because older firmware can report
"hidden" while the keyboard is visibly up.

### Fix

- Pre-IME BACK capture (the primary fix): the WebView now overrides
  `onKeyPreIme`. On Fire TV the system keyboard is a modal overlay that takes
  BACK before the activity's `dispatchKeyEvent` runs; the focused view still
  receives the key through the pre-IME stage first, which is the hook native
  EditTexts use to close that keyboard. When `editing` (or a visible IME) is
  true, BACK is consumed there, `endEditing()` closes the keyboard and the
  cursor is restored in the same key press. The matching key-up is swallowed so
  the activity never sees a stray BACK-up after editing has ended. On Android
  TV, where the activity normally receives BACK, this is the same action the
  Activity path already took, so there is no behaviour change.
- Split the old `endEditing()` into:
  - `endEditing()`: the explicit BACK/MENU path; hides the IME exactly as
    before, then calls the state restore.
  - `finishEditing()`: state only; clears `editing`, suppresses re-adoption
    for 4 s, clears the page's IME band and restores the cursor. It never
    calls `hideSoftInput`, `hide(Type.ime())` or any other IME API, so it
    cannot close a keyboard the viewer is typing on.
- Window-focus recovery: a TV IME that is a separate window/activity shows up
  as a window-focus loss and return on the WebView. If focus returns while
  editing, the IME is polled for up to 2 s and `finishEditing()` runs as soon
  as it is verifiably gone. If the IME is still visible, nothing happens.
- API 30+ inset recovery: the existing observer now remembers that it saw the
  IME up during this editing session. If the IME then reports hidden for three
  consecutive 300 ms polls (and editing has lasted at least 1.5 s), the cursor
  is restored. This covers a floating IME that consumes BACK itself.
- Older firmware without reliable IME insets keeps the previous behaviour and
  relies on the window-focus path or the explicit BACK path.
- The keyboard show/focus path is untouched: `beginEditing`, `showIme`,
  `showImeSoft`, the 12-pass reveal loop, the tap probe and the JS bridge all
  behave as in 1.1.8. Passive recovery only ever runs with
  `!imeVisible()` on the inset path, or after window focus has returned.

### Why this cannot break typing

- The pre-IME capture only acts while `editing` or a visible IME is true, and
  only for BACK. Every other key, and BACK when no keyboard is involved, goes
  to `super.onKeyPreIme` exactly as before.
- Passive recovery never calls an IME hide/show API.
- While an IME is still visible, `handleWafKey` and the WebView key listener
  both keep declining the D-pad because `imeVisible()` is checked
  independently of `editing`.
- A mistaken recovery would only paint the native cursor while the IME owns
  key input; it would not close the keyboard or steal the IME's keys.

## 3. Verification

- [x] `:app:compileDebugJavaWithJavac` succeeds.
- [x] APK metadata: versionCode 11, versionName 1.1.9, minSdk 21, targetSdk 34.
- [x] Signature: v1+v2, same debug key as 1.1.4-1.1.8, certificate SHA-256
  `5595d8d09c8b7e8cd9b261fb583a844889ec2957d23ee6e98ebefdc1d3b22252`.
- [x] Size: 23,663,843 bytes. SHA-256:
  `D4FF35D4FC8004E8FFCC4F939E6ABDA459DA2F97FB111ACB2D32015FA47E00BE`.
- [x] DEX: `VegaLoadControl.<init>` pushes `8000, 600000, 1500, 3000, -1, 1,
  0, 0` into `DefaultLoadControl.<init>(DefaultAllocator;IIIIIZIZ)V`, so the
  constructor assertions hold.
- [x] DEX: the WebView subclass
  `WafScreenKt$WafScreen$15$3$1$1$1` overrides `onKeyPreIme`; both recovery
  log strings, `__vegaImePx`, `__vegaRevealForce` and
  `SLOW_BYTES_PER_SECOND = 1500000` are present; `DisplayCapability` is absent.
- [x] Release published: https://github.com/aashish1332/Vega-tv/releases/tag/v1.1.9
  (tag `v1.1.9` at `1b1c6fb`, asset `app-debug.apk`, label
  `VegaTV-1.1.9.apk`). The asset downloaded back from GitHub is 23,663,843
  bytes with the same SHA-256, so the published bytes are the verified build.

## 4. Files

- `app/src/main/java/tv/vega/tv/player/VegaLoadControl.kt`
- `app/src/main/java/tv/vega/tv/ui/waf/WafScreen.kt`
- `app/build.gradle.kts`
