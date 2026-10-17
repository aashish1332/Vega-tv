# Vega TV Player UI Update

## Changes Made

### Before
- Player controls were hidden behind the MENU button on the remote
- Users had to press MENU to access server selection, speed, subtitles, audio, and other settings
- Controls were only visible in a side panel when MENU was pressed

### After
- **Always-visible bottom control bar** similar to the mobile app
- Controls are now directly accessible without pressing MENU button
- Quick access buttons for:
  - **Server** - Switch between different video servers/qualities
  - **Speed** - Adjust playback speed (0.5x - 2x)
  - **Resize** - Toggle between Fit/Fill/Zoom modes
  - **Subs** - Open subtitle selection (opens detail panel)
  - **Audio** - Open audio track selection (opens detail panel)
  - **More** - Access additional settings (opens detail panel)
  - **Next Ep** - Jump to next episode (when available and at 80%+ progress)
  - **Exit** - Return to previous screen

### Control Bar Features
1. **Server Selection Dropdown**: Click the Server button to see all available servers with quality labels
2. **Speed Selection Dropdown**: Click the Speed button to choose from 0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x
3. **Quick Toggle**: Resize mode cycles through Fit → Fill → Zoom with a single click
4. **Deep Dive Options**: Subs, Audio, and More buttons open the detailed settings panel when needed
5. **Smart Next Button**: Only shows "Next Ep" when >80% through and another episode exists
6. **Skip Button Integration**: Skip Intro/Outro button appears above the control bar when active

### UI Design
- Semi-transparent dark background (80% opacity) for visibility over video
- Consistent Material 3 TV styling
- Proper spacing and touch targets for TV remote navigation
- Server and speed selections appear as dropdown menus above the main control bar

### Backwards Compatibility
- MENU button still works to open the full settings panel
- All existing features (subtitles, audio tracks, download, etc.) remain accessible
- Skip intro and auto-next episode features work as before

## Files Modified
- `app/src/main/java/tv/vega/tv/ui/player/PlayerScreen.kt`

## Testing
Build and test on Android TV to verify:
1. Control bar appears at the bottom during playback
2. D-pad navigation works smoothly between buttons
3. Server and speed dropdowns appear and close properly
4. Skip button appears when intro/outro segments are active
5. Next episode button appears at 80%+ progress
6. MENU button still opens the full settings panel
