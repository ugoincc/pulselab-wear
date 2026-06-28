# PulseLab Wear

A standalone Wear OS app that reads heart-rate (and simulated HRV / SpO₂ / stress)
data on the watch and streams it to the PulseLab phone app over the Wear Data Layer.

## Features

- **Live heart-rate monitoring** straight from the watch's optical sensor
  (`SensorManager` / `Sensor.TYPE_HEART_RATE`).
- **Real / Fake data source toggle** — a button on the home screen switches
  between the real sensor and a simulated feed. Switching is *live*: thanks to
  `flatMapLatest` on the source flag in `HomeViewModel`, flipping the source
  mid-session cancels the active flow and starts the other one without
  restarting the session. Fake readings are tagged `(SIM)` on screen.
- **Phone streaming** of each reading as a `SensorBundle` over the Data Layer,
  with a connectivity indicator.

## Architecture (quick map)

| Layer | File |
|-------|------|
| Sensor / simulation source | `data/health/HeartRateRepository.kt` |
| Phone transport | `data/wearable/WearDataSender.kt` |
| State + flow wiring | `presentation/home/HomeViewModel.kt` |
| UI (stateful + previewable stateless) | `presentation/home/HomeScreen.kt` |

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

### Future migration path (when targeting API 36+ for Play Store)

This is real work, not a one-liner:
1. Add `androidx.health:health-services-client`.
2. Rewrite `realHeartRateFlow()` to use Health Services `MeasureClient`.
3. Declare `android.permission.health.READ_HEART_RATE` (and add
   `android:maxSdkVersion="35"` to the existing `BODY_SENSORS` lines).
4. Add a privacy-policy activity (required by the health-permission framework,
   same as Health Connect).

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
