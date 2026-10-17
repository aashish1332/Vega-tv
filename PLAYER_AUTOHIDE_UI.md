# Player Auto-Hide UI Feature

## Implementation Summary

### Feature: Auto-Hide Player Controls
The player UI now automatically disappears after 5 seconds of inactivity, showing only the video. This provides an immersive viewing experience similar to Netflix and other streaming apps.

### Behavior

#### UI Visibility
- **On Player Load**: UI is visible
- **After 5 Seconds**: UI automatically hides, showing only the video
- **On Any Interaction**: UI becomes visible again and timer resets

#### Controls to Show/Hide UI
1. **OK/Enter Button**: 
   - When UI is hidden → Shows UI
   - When UI is visible → Play/Pause video
   
2. **BACK Button**:
   - When UI is hidden → Shows UI
   - When UI is visible → Hides UI (or exits if already hidden)

3. **D-Pad (Up/Down/Left/Right)**:
   - Shows UI if hidden
   - When UI is visible, LEFT/RIGHT seek ±10 seconds

4. **MENU Button**: 
   - Always shows UI and opens settings panel

#### What's Always Visible
- **Skip Intro/Outro Button**: Always shows when active (important for UX)
- **Error Messages**: Always visible when there's a playback error
- **Loading Indicators**: Show during initial buffering

#### What Auto-Hides
- **Bottom Control Bar**: Server, Speed, Resize, Subs, Audio, Settings, Exit buttons
- **Buffering Messages**: Hides with UI (only shows when UI is visible)
- **Loading Screen**: Initial loading only shows when UI is visible

### Timer Reset Events
UI timer resets (stays visible for another 5 seconds) on:
- Any button press (OK, BACK, D-pad, MENU)
- Server selection
- Speed change
- Resize mode toggle
- Opening subtitle/audio/settings menus
- Seeking (LEFT/RIGHT on D-pad)
- Skip button press
- Next episode button press

### Technical Details

**State Management**:
```kotlin
var showUI by remember { mutableStateOf(true) }
var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
```

**Auto-Hide Logic**:
```kotlin
LaunchedEffect(lastInteraction, menuOpen, showUI) {
    if (showUI && !menuOpen) {
        delay(5000) // 5 seconds
        if (System.currentTimeMillis() - lastInteraction >= 5000) {
            showUI = false
        }
    }
}
```

**Reset Function**:
```kotlin
fun showUIWithReset() {
    showUI = true
    lastInteraction = System.currentTimeMillis()
}
```

### User Experience Flow

1. **Start Playing**: User starts a video, controls are visible
2. **Watch Video**: After 5 seconds, controls fade away → full screen video only
3. **Want Controls?**: Press OK or BACK → controls appear
4. **Make Selection**: Choose server, adjust speed, etc. → controls stay for another 5 seconds
5. **Continue Watching**: Controls auto-hide again after 5 seconds

### Benefits
- **Immersive Experience**: Full-screen video without distractions
- **Easy Access**: Single button press to show controls
- **Smart Behavior**: Skip buttons always visible when needed
- **Familiar Pattern**: Matches behavior of popular streaming apps

## Files Modified
- `app/src/main/java/tv/vega/tv/ui/player/PlayerScreen.kt`

## Testing Checklist
- [ ] UI shows on player load
- [ ] UI auto-hides after 5 seconds
- [ ] OK button shows UI when hidden
- [ ] OK button plays/pauses when UI is visible
- [ ] BACK button shows UI when hidden
- [ ] D-pad shows UI when hidden
- [ ] Skip button always visible when intro/outro detected
- [ ] UI timer resets on any interaction
- [ ] Menu panel opens with MENU button regardless of UI state
