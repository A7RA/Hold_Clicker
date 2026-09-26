# Ra Clicker

**Touch Turbo / Rapid Tap** for Android — not an auto clicker.

The core mechanic:

```
Hold Trigger  →  Rapid taps fire at target
Release       →  All taps stop immediately
```

No timers running in the background. No clicks without your finger on the button.

---

## How it works

```
User finger DOWN on Trigger
         ↓
TurboEngine loop starts (coroutine)
         ↓
  DOWN → UP  (dispatchGesture @ target)
  DOWN → UP
  DOWN → UP
  ...
         ↓
User finger UP from Trigger
         ↓
Loop cancelled immediately — no delay
```

Each tap is a real `GestureDescription.StrokeDescription` with a 1 ms duration,
meaning the target app sees genuine `ACTION_DOWN → ACTION_UP` touch events,
not synthetic `performClick()` calls. This is what games respond to.

---

## Requirements

| Item | Minimum |
|------|---------|
| Android SDK | Android 12 (API 31) |
| Target SDK | 35 |
| Kotlin | 2.0 |
| Gradle | 8.9 |
| Android Studio | Ladybug (2024.2.x) or newer |
| Device | Physical device recommended (emulator works but touch injection may differ) |

> **Root is not required.** Everything uses public Android APIs.

---

## Build

### 1. Open in Android Studio

```
File → Open → select the `Clicker/` folder
```

Let Gradle sync complete.

### 2. Build from command line

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First-time Setup (must be done in order)

### Step 1 — Grant Overlay Permission

1. Open **Ra Clicker**.
2. Tap **"Grant Overlay Permission"**.
3. Find **Ra Clicker** in the list and toggle it **ON**.
4. Press Back — the chip turns green.

This allows the floating Trigger and Target indicators to appear on top of
other apps and games.

### Step 2 — Enable Accessibility Service

1. Tap **"Enable Accessibility"** — a dialog explains what the service does.
2. Tap **"Open Settings"**.
3. In Android Settings → Accessibility → Installed Services, find
   **"Ra Clicker Turbo"** and toggle it **ON**.
4. Confirm the permission dialog Android shows.
5. Press Back — the chip turns green.

> **Why is this needed?**
> `AccessibilityService.dispatchGesture()` is the only non-root API that can
> inject real touch events into arbitrary apps. Ra Clicker uses it *only* for
> gesture injection — it does not read, record, or transmit any screen content.
> This is declared in `res/xml/accessibility_service_config.xml`:
> `accessibilityEventTypes=""` means zero events are consumed.

### Step 3 — Set Target

1. The **Trigger** toggle at the top of the main screen turns the floating
   overlay on/off. Enable it now.
2. Tap **"Set Target"**.
3. The screen goes dark with the overlay active — **tap exactly where you
   want rapid taps to land** (e.g. a button inside a game).
4. A red crosshair appears at that position and the coordinates display in
   the app.

### Step 4 — Choose Tap Interval

Use the **Tap Interval** spinner to select how fast taps fire:

| Interval | Taps / second |
|----------|---------------|
| 10 ms    | 100 tps       |
| 20 ms    | 50 tps        |
| 30 ms    | ~33 tps       |
| 50 ms    | 20 tps        |
| 75 ms    | ~13 tps       |
| 100 ms   | 10 tps        |
| 150 ms   | ~7 tps        |
| 200 ms   | 5 tps         |
| 300 ms   | ~3 tps        |
| 500 ms   | 2 tps         |
| 1 000 ms | 1 tps         |

Start with **50 ms** (20 tps) — a good balance for most games.

---

## Using Hold → Rapid Tap

1. Switch to your game / target app.
2. The **orange lightning-bolt button** floats on top. Drag it anywhere
   convenient (bottom corner, side of screen, etc.).
3. **Press and hold** the orange button.
4. Taps fire repeatedly at the red crosshair target.
5. **Release** — taps stop instantly.

That's the complete flow. No start/stop buttons. No timers.

### Drag vs Hold

The trigger button distinguishes drag from hold using a **10 px displacement
threshold**:

- Move finger < 10 px → **Hold** → turbo fires
- Move finger ≥ 10 px → **Drag** → window repositions, no taps

After dragging, the new position is saved automatically (DataStore).

---

## Test Single Tap

The **"Test Single Tap"** button sends exactly one tap to the current target.
Use it to verify the crosshair is placed correctly before using turbo in a game.

---

## Persistent Settings

All settings survive app restarts via **DataStore**:

| Setting | Default |
|---------|---------|
| Target X / Y | not set |
| Trigger X / Y | (100, 600) px |
| Tap interval | 50 ms |

---

## Logcat Tags

All log output uses the tag `RaClicker`:

```bash
adb logcat -s RaClicker
```

Key messages:

```
RaClicker: Accessibility connected
RaClicker: Turbo started | target=(540.0, 960.0) | interval=50ms
RaClicker: Tap dispatched → (540.0, 960.0)
RaClicker: Turbo stopped
RaClicker: Trigger ACTION_CANCEL — stopping turbo
RaClicker: Target selected at (540.0, 960.0)
RaClicker: Accessibility disconnected
```

---

## Architecture

```
app/src/main/java/com/raclicker/app/
├── MainActivity.kt                      # Material 3 main screen
├── accessibility/
│   └── RaAccessibilityService.kt        # dispatchGesture touch injection
├── overlay/
│   ├── OverlayService.kt                # Foreground service, owns overlays
│   ├── TriggerOverlay.kt                # Floating hold-to-fire button
│   └── TargetOverlay.kt                 # Placement + indicator overlays
├── turbo/
│   └── TurboEngine.kt                   # Cancellable coroutine tap loop
└── settings/
    └── SettingsRepository.kt            # DataStore persistence + RaSettings
```

### Component interaction

```
MainActivity
    │  startForegroundService(OverlayService)
    ▼
OverlayService ──creates──► TriggerOverlay
                   └────────► TargetOverlay

TriggerOverlay
    │  ACTION_DOWN: reads currentSettings from OverlayService
    │  calls RaAccessibilityService.startTurbo(x, y, interval)
    ▼
RaAccessibilityService
    │  TurboEngine.start(x, y, interval) { injectTap(x, y) }
    ▼
TurboEngine (coroutine loop)
    │  every intervalMs: calls injectTap callback
    ▼
dispatchGesture(GestureDescription)
    │  StrokeDescription(path=moveTo(x,y), start=0, duration=1ms)
    ▼
Target app receives ACTION_DOWN → ACTION_UP
```

---

## Known Limitations & Android Notes

### dispatchGesture rate limit
Android internally queues gesture dispatches. At intervals below ~20 ms the
system may merge or drop gestures. In practice **30–50 ms** is the reliable
sweet spot for most devices. Test on your specific device.

### Miui / One UI / ColorOS
Some OEM ROMs add extra restrictions on `TYPE_APPLICATION_OVERLAY` windows or
Accessibility Services. If the overlay doesn't appear:
- Check Settings → Privacy → Special permissions → Display over other apps
- On MIUI: Settings → Apps → Ra Clicker → Other permissions → Display over apps

### Android 14+ foreground service
The manifest declares `android:foregroundServiceType="specialUse"` which
is required on Android 14+ for services that don't fit a standard type (camera,
location, media, etc.). The system shows a one-time disclosure dialog on first
launch — this is expected behavior.

### No root, no shell injection
Ra Clicker does **not** use `su`, `/dev/input`, `sendevent`, or any
undocumented APIs. Everything goes through `AccessibilityService.dispatchGesture()`.

### Orientation changes
Target coordinates are stored as raw pixel values from top-left. If you rotate
the screen after setting a target, re-set the target in the new orientation
since pixel coordinates change with rotation.

---

## Permissions Summary

| Permission | Why |
|-----------|-----|
| `SYSTEM_ALERT_WINDOW` | Draw floating overlay windows |
| `FOREGROUND_SERVICE` | Run OverlayService in foreground |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ foreground service type |
| `VIBRATE` | Short haptic feedback when trigger is pressed |
| `POST_NOTIFICATIONS` | Show foreground service notification (Android 13+) |
| Accessibility Service | `dispatchGesture()` touch injection |

---

## License

MIT — do what you want, no warranty.
