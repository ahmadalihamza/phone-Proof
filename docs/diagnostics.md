# Diagnostics and evidence

The engine dispatches registered handlers, applies API/capability/permission gates, bounds observation windows and records timestamps. Platform exceptions become an explained inaccessible/inconclusive state; there is no random result path. A disabled implementation is shown only as disabled/not tested.

Automatic observations include device/build/RAM/storage information, declared display modes, battery broadcast/properties, sensor metadata, Camera2 lens metadata, network state, connected audio/USB metadata, thermal APIs, Android Keystore round trips and bounded filesystem/database/compute operations. Information is deliberately excluded from functional scoring.

Interactive checks include display patterns and app-window brightness, a 6×8 touch grid, edge/corner/gesture/pointer observations, per-camera CameraX preview/capture, torch and tone playback with human confirmation, real AudioRecord samples, sensor events, charger transitions, BLE advertisements, fresh GPS fixes, volume/back input, vibration and BiometricPrompt authentication.

Only fresh events or successful operations justify automatic PASS. A quiet microphone, stationary sensor, missing BLE advertisement or indoor GPS timeout is inconclusive. Permission denial is NOT_ACCESSIBLE; optional hardware absence is NOT_SUPPORTED. Android battery current/counter sentinels are inaccessible. Battery health is the platform enum, not a percent or authenticity claim. Thermal headroom NaN is not interpreted as healthy. Storage tests use a 256 KiB private file and delete it in finally. CPU/memory operations are deliberately small. Raw audio and location coordinates are not retained.

Touch coverage describes the app interaction surface; system gesture/navigation insets are excluded. Position RMS includes finger movement and is not a laboratory jitter or sampling-rate measurement. CameraX may not expose every Camera2 physical lens for independent capture; an inaccessible initialization remains inconclusive and must be validated on actual phones. Metadata enumerates physical camera relationships separately.

See diagnostic-registry.md for every definition and limitation. ENABLED means executable code exists; it does not mean the check was exercised on a physical device in this workspace.
