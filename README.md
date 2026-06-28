# PulseLab Wear

A standalone Wear OS app that reads heart rate, **HRV (RMSSD), SpO₂ and skin
temperature** on the watch and streams them to the PulseLab phone app over the
Wear Data Layer. HR comes from AndroidX Health Services; the other three are real
readings from the **Samsung Health Sensor SDK**.

## Features

- **Four selectable data sources**, cycled with one button (`DataSource` enum):
  - **Live** — Health Services `MeasureClient`: real heart rate, foreground only,
    per-second updates.
  - **Background** — Health Services `PassiveMonitoringClient`: real heart rate
    that keeps streaming to the phone even when the app is backgrounded, the
    screen is off, or the process was killed (`PassiveDataService` is bound by
    Health Services independently of the UI).
  - **Sensor** — Samsung Health Sensor SDK: real **HR + IBI → HRV (RMSSD)**,
    **SpO₂** and **skin temperature**, foreground. Reads the watch sensors
    directly (see *Sensor source* below).
  - **Fake** — in-app simulated feed that fills *every* metric, for UI testing.
  - Switching is *live*: `collectLatest` on the source flag in `HomeViewModel`
    cancels the active branch and starts the next without restarting the session.
- **Debug metrics panel** — the home screen lists all five metrics every time
  (see *Metric availability* below), showing either the value or a note about its
  source/availability for the active source, plus raw debug fields
  (active source, readings sent, phone reachable, last-update timestamp).
- **Phone streaming** of each reading as a `SensorBundle` over the Data Layer,
  with a connectivity indicator.

## Architecture (quick map)

| Layer | File |
|-------|------|
| Live foreground HR (MeasureClient) | `data/health/LiveMeasureMonitor.kt` |
| Background real HR (PassiveMonitoringClient) | `data/health/PassiveDataService.kt`, `data/health/PassiveMonitoringManager.kt` |
| Service → UI bridge | `data/health/PassiveHeartRateStore.kt` |
| Samsung SDK source (HRV/SpO₂/skin temp) | `data/health/SamsungSensorMonitor.kt`, `data/health/SensorConnectionManager.kt` |
| HRV / RMSSD computation | `data/health/Hrv.kt` (`rmssd()` + `IbiWindow`) |
| Simulated source (Fake mode) | `data/health/HeartRateRepository.kt` |
| Metric/source model + availability map | `model/HealthMetric.kt`, `model/DataSource.kt` |
| Phone transport | `data/wearable/WearDataSender.kt` |
| State + flow wiring | `presentation/home/HomeViewModel.kt` |
| UI (stateful + previewable stateless) | `presentation/home/HomeScreen.kt` |

## Metric availability

Standard AndroidX Health Services only exposes **heart rate** live. Everything
else is now sourced from the **Samsung Health Sensor SDK** in the **Sensor**
source — it reads the watch sensors directly and gives us IBI (→ RMSSD), SpO₂ and
skin temperature that Health Services cannot. **Stress** is the only metric still
unsourced: it is Samsung-proprietary with no AOSP API, so we derive it offline
from RMSSD rather than reading it live.

| Metric | Live source today | Notes |
|--------|-------------------|-------|
| **HR** | ✅ Health Services (`MeasureClient` / `PassiveMonitoringClient`) **or** Samsung SDK | Samsung SDK's HR tracker also carries the IBI we need for HRV |
| **HRV** | ✅ Samsung SDK — RMSSD computed from IBI (Sensor source) | `Hrv.kt`: sliding `IbiWindow`, IBIs filtered by status before RMSSD |
| **SpO2** | ✅ Samsung SDK — `SPO2_ON_DEMAND`, re-armed every 60 s (Sensor source) | on-demand: one ~30 s measurement per arm, so we re-arm to keep it fresh |
| **Skin Temp** | ✅ Samsung SDK — `SKIN_TEMPERATURE_CONTINUOUS` (Sensor source) | continuous, slower cadence than HR |
| **Stress** | ❌ no live source | Samsung-proprietary; **derive offline from RMSSD** (no AOSP/SDK live API) |

> Which metrics fill in depends on the **active source**: only the **Sensor**
> source produces HRV/SpO₂/skin temp; **Live**/**Background** fill HR only; **Fake**
> populates all five so the UI/pipeline can be exercised without a watch. A
> per-device **capability check** is the definitive gate — the Sensor source only
> starts the trackers this particular watch reports as supported.

### How background monitoring works

1. Starting a **Real** session calls `PassiveMonitoringManager.start()`, which
   registers `PassiveDataService` with Health Services and persists that intent
   in `SharedPreferences`.
2. Health Services delivers **batched** heart-rate samples to
   `PassiveDataService.onNewDataPointsReceived()` — running in the app's process
   but **not tied to the Activity/ViewModel lifecycle**. The service forwards
   each reading to the phone and publishes it to `PassiveHeartRateStore`.
3. While the UI is alive, `HomeViewModel` observes `PassiveHeartRateStore` to
   show the latest reading. When the UI goes away, `onCleared()` cancels only the
   in-process collector — it deliberately **does not** unregister monitoring, so
   streaming continues. Only an explicit **Stop** (or `clear...Async`) ends it.
4. On next launch, if the persisted flag says a background session is active, the
   ViewModel resumes showing it so the user can stop it.

> **Trade-off:** passive (Background) data is batched and lower-frequency than a
> foreground stream, so the number updates less often than in **Live** mode. The
> two are deliberately separate selectable sources here for debug clarity; a
> production build would run **Live (MeasureClient) in the foreground and
> Background (Passive) when the app leaves the foreground** simultaneously.

> **Permission note:** passive HR needs `BODY_SENSORS`, and uninterrupted
> background delivery needs `BODY_SENSORS_BACKGROUND` — set the app's body-sensors
> permission to **"Allow all the time"** in watch Settings for best reliability.

### Sensor source (Samsung Health Sensor SDK)

The **Sensor** source (`SamsungSensorMonitor`) connects to the Samsung Health
Platform on the watch and exposes a single `Flow<HeartRateReading>` that slots
into the same foreground collector + `WearDataSender` path as the other sources —
no new transport.

1. `SensorConnectionManager` connects to the Health Platform, then runs a
   **capability check** — the definitive gate. Only trackers this watch reports
   as supported are started.
2. **HR + IBI** (`HEART_RATE_CONTINUOUS`, ~1 Hz) is the driver. Valid IBIs feed a
   sliding `IbiWindow`; each HR emission carries the window's current **RMSSD** as
   HRV. IBIs are filtered by status first — bad IBIs inflate RMSSD.
3. **SpO₂** (`SPO2_ON_DEMAND`) is on-demand: one ~30 s measurement per arm, so it
   is **re-armed every 60 s**. Only one on-demand tracker may run at a time.
4. **Skin temperature** (`SKIN_TEMPERATURE_CONTINUOUS`) runs continuously at a
   slower cadence. SpO₂ and skin-temp update "latest" values that ride along on
   the next HR emission.
5. On `awaitClose` (source switched / session stopped) every tracker is
   `unsetEventListener()`'d and the service disconnected — no leak.

> **Status codes are per-metric and runtime-validated** (not in the `.aar`):
> confirmed on-device (SM-L705F) — HR valid `1`, off-body `-3`, SpO₂ completed
> `2`, skin-temp valid `0`. Off-body HR (`-3`) is dropped.

> **Requires Health Platform dev mode** on the watch (Settings → Apps → Health
> Platform → tap the title ~10× until `[Dev mode]` shows). Without it the SDK
> returns `SDK_POLICY_ERROR`. The SDK does **not** run on an emulator. See
> `HANDOFF-samsung-sensor-sdk.md` for the full setup checklist.

## Decisions & gotchas (project memory)

### Why `targetSdk = 35`, not 36

> **TL;DR:** Targeting API 36 silently breaks heart-rate permissions on modern
> Samsung / Wear OS 6 watches. Stay at 35 until we migrate to Health Services.

On **Wear OS 6 / Android 16 (API 36)**, the `BODY_SENSORS` and
`BODY_SENSORS_BACKGROUND` permissions are **deprecated**. For apps that *target*
API 36, the system no longer treats `BODY_SENSORS` as a grantable runtime
permission — heart rate must instead go through the granular health permissions
(`android.permission.health.READ_HEART_RATE`) via **Health Services
`MeasureClient`**.

**The symptom we hit** (Samsung Galaxy Watch Ultra): the runtime permission
request silently did nothing, and **Settings → Apps → PulseLab → Permissions
showed no permission toggle at all**. Uninstalling, clearing cache, and
rebuilding made no difference — because the app was opted into the new
permission model where `BODY_SENSORS` simply doesn't exist.

The rule from Google's docs: *"If an application has targetSdk=35, BODY_SENSORS
permissions should always be used."* So targeting **35** keeps the classic
`BODY_SENSORS` runtime flow working with plain `SensorManager` — no Health
Services rewrite required. `compileSdk` can stay at 36.

**Verify the permission is grantable:**
```bash
adb shell dumpsys package br.unioeste.pulselab.wear | grep -i body_sensors
```
You should see `BODY_SENSORS` with a `granted=` state. If nothing is listed,
the app is targeting an SDK where the permission no longer exists.

> **Status update:** real heart rate now uses Health Services'
> `PassiveMonitoringClient` (see *Background monitoring* above), but it still
> reads through the `BODY_SENSORS` permission — **not** the granular
> `android.permission.health.*` permissions. So the `targetSdk = 35` decision
> still stands; passive monitoring and the SDK target are independent concerns.

### Future migration path (when targeting API 36+ for Play Store)

This is real work, not a one-liner:
1. ✅ `androidx.health:health-services-client` already added; real HR runs
   through `PassiveMonitoringClient`.
2. Migrate from the `BODY_SENSORS` permission to the granular
   `android.permission.health.READ_HEART_RATE` (add `android:maxSdkVersion="35"`
   to the existing `BODY_SENSORS` lines) and request it via the health flow.
3. Add a privacy-policy activity (required by the health-permission framework,
   same as Health Connect).
4. Optionally add `MeasureClient` for snappy real-time display while the app is
   in the foreground.

References:
- [Test how your app handles behavior changes — Wear OS 6](https://developer.android.com/training/wearables/versions/6/changes)
- [Declare appropriate permissions — Android Health Services](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Behavior changes: Apps targeting Android 16+](https://developer.android.com/about/versions/16/behavior-changes-16)

## Building

```bash
./gradlew :wear:installDebug
```

On the watch: open the app → **Start** → grant the heart-rate permission →
wait ~10s for the optical sensor to lock on (loose fit or off-wrist yields no
reading). Use the **Source** button to cycle Live → Background → Fake → Sensor.

The **Sensor** source additionally needs Health Platform **dev mode** enabled on
the watch (see *Sensor source* above) and a physical device — it does not work on
an emulator.
