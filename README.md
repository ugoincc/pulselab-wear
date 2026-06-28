# PulseLab Wear

A standalone Wear OS app that reads heart-rate (and simulated HRV / SpO₂ / stress)
data on the watch and streams it to the PulseLab phone app over the Wear Data Layer.

## Features

- **Three live data sources**, cycled with one button (`DataSource` enum):
  - **Live** — Health Services `MeasureClient`: real heart rate, foreground only,
    per-second updates.
  - **Background** — Health Services `PassiveMonitoringClient`: real heart rate
    that keeps streaming to the phone even when the app is backgrounded, the
    screen is off, or the process was killed (`PassiveDataService` is bound by
    Health Services independently of the UI).
  - **Fake** — in-app simulated feed that fills *every* metric, for UI testing.
  - Switching is *live*: `collectLatest` on the source flag in `HomeViewModel`
    cancels the active branch and starts the other without restarting the session.
- **Debug metrics panel** — the home screen lists all five metrics every time
  (see *Metric availability* below), showing either the value or a note about
  where that value will eventually come from, plus raw debug fields
  (active source, readings sent, phone reachable, last-update timestamp).
- **Phone streaming** of each reading as a `SensorBundle` over the Data Layer,
  with a connectivity indicator.

## Architecture (quick map)

| Layer | File |
|-------|------|
| Live foreground HR (MeasureClient) | `data/health/LiveMeasureMonitor.kt` |
| Background real HR (PassiveMonitoringClient) | `data/health/PassiveDataService.kt`, `data/health/PassiveMonitoringManager.kt` |
| Service → UI bridge | `data/health/PassiveHeartRateStore.kt` |
| Simulated source (Fake mode) | `data/health/HeartRateRepository.kt` |
| Metric/source model + availability map | `model/HealthMetric.kt`, `model/DataSource.kt` |
| Phone transport | `data/wearable/WearDataSender.kt` |
| State + flow wiring | `presentation/home/HomeViewModel.kt` |
| UI (stateful + previewable stateless) | `presentation/home/HomeScreen.kt` |

## Metric availability (the "plan ahead" reality)

Only **heart rate** is available live through standard AndroidX Health Services.
The other metrics are scaffolded in `HealthMetric` (each carries its `provider`
string and a `wired` flag) and shown on screen, but are **not yet sourced** —
they need APIs outside Health Services:

| Metric | Live source today | Eventual source |
|--------|-------------------|-----------------|
| **HR** | ✅ MeasureClient / PassiveMonitoringClient | — |
| **HRV** | ❌ | Compute from RR intervals, or Samsung Health Sensor SDK |
| **SpO2** | ❌ | Samsung Health Sensor SDK (privileged); Health Connect for history |
| **Skin Temp** | ❌ | Samsung Health Sensor SDK (live); Health Connect `SkinTemperatureRecord` (API 35+) |
| **Stress** | ❌ | Samsung-proprietary (derived from HRV) — Samsung Health SDK, **no AOSP API** |

> The **Fake** source populates all five so the UI/pipeline can be exercised end
> to end before the real sources exist. To actually wire HRV/SpO2/skin-temp/stress
> you'll need the **Samsung Health Sensor SDK** (partner approval required) and/or
> **Health Connect**; stress has no non-Samsung path.

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
reading). Use the **Source** button to switch between real and fake data.
