# Synthetic Engine

An Android Auto app that reads how your car is actually moving, simulates a combustion
engine and gearbox from that, plays the engine sound through the car speakers in real
time, and draws a live tachometer on the car display.

```
 car speed (Android Auto) ─┐
 car accelerometer ────────┤                 ┌─ EngineSimulator ─┐   ┌─ EngineSynth ─→ AudioTrack → car speakers
 phone GPS speed ──────────┼─→ MotionFusion ─┤  (gearbox, RPM,   ├───┤
 phone accelerometer ──────┘   (speed+accel) │   load estimate)  │   └─ TachometerRenderer → car display + phone
                                             └───────────────────┘
```

## How it works

**1. Capturing the car's motion (`MotionFusion`)**

| Source | Used for | Notes |
|---|---|---|
| Android Auto `CarInfo` speed | road speed | Best source. Needs `CAR_SPEED` and a host at Car API level 3 or higher. |
| Android Auto `CarSensors` accelerometer | longitudinal acceleration | Already in the car's frame. Many head units don't provide it. |
| Phone GPS | road speed fallback | 1–4 Hz. Ignored while car speed is fresh. |
| Phone linear accelerometer | acceleration fallback | The phone can sit in any orientation: the forward axis is **learned automatically** by matching the accelerometer vector against changes in GPS speed. |

Between the slow speed samples, speed is dead-reckoned from the accelerometer, so the
RPM reacts as soon as you press the pedal instead of waiting for the next GPS fix.

**2. Engine model (`EngineSimulator`)** — the real engine is never read; it is modelled from motion:
- **Throttle/load** comes from measured acceleration plus rolling/aero resistance,
  compared with what the current gear could deliver. Going uphill shows as more load.
- **Automatic gearbox**: shift points depend on throttle (short-shifts when cruising,
  holds to the redline at full throttle), kickdown, and a short torque cut on each shift.
- **Torque converter**: on launch the engine flares towards stall RPM.
- **Overrun** (lifting off or braking) and the **rev limiter**.

**3. Sound synthesis (`EngineSynth`)** — fully procedural, no samples:
- A crank-angle accumulator runs over the 720° four-stroke cycle; each cylinder fires
  at its real firing angle and sends a combustion pulse into its exhaust bank.
- Each bank is shaped by exhaust resonances that rise in pitch with load, plus a
  low-frequency "thump", engine-order tones and intake noise, then soft-clipped.
- Uneven per-bank pulse spacing gives the cross-plane V8 its burble. Lifting off
  produces overrun pops, and the limiter cuts sparks.
- Engines: **V8 5.7 cross-plane**, **Inline-4 2.0**, **Flat-6 3.8**, each with its own
  gearbox, idle speed and redline.

**4. Display (`TachometerRenderer`)** — one Canvas renderer used on both screens: an RPM
dial with a red zone, a shift-light flash, gear, digital RPM, speed and a load arc. On wide
car screens a side panel also shows speed, load, longitudinal g and which sensors are in use.

## Project layout

```
engine/   Pure Kotlin (JVM) core with no Android dependency: fusion, simulator, synth, tests
app/      Android app
  car/      CarAppService, Session, NavigationTemplate screen + surface renderer, car sensors
  audio/    AudioTrack streaming thread
  sensors/  phone GPS + accelerometer
  ui/       tachometer renderer/view, phone activity (incl. demo mode)
```

## Build & run

Requirements: Android Studio (or the Android SDK with platform 35) and JDK 17+.

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew -p engine test              # engine unit tests (no Android SDK needed)
./gradlew -p engine run --args=out    # render demo drives to out/drive-{v8,i4,f6}.wav
```

CI (`.github/workflows/android.yml`) runs the tests and builds the debug APK and the demo
WAVs as downloadable artifacts.

### Testing on Android Auto

1. Install the debug APK on your phone.
2. Turn on Android Auto developer mode: Android Auto settings → tap *Version* 10 times →
   ⋮ → *Developer settings* → enable **Unknown sources**.
3. Connect to the car, or run the
   [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu).
   The app shows up in the launcher as **Synthetic Engine**.
4. Opening it on the car screen starts the engine. Use ▶/■ to start or stop, and tap the
   engine name to switch engines. Grant location when asked: it is needed for GPS speed if
   the car doesn't share its own.

On the phone, **Demo mode** drives a virtual car with an on-screen accelerator and brake,
so you can hear and see the full pipeline while parked.

## Caveats

- **Car screen graphics need the navigation category.** The Car App Library only lets
  *navigation* apps draw custom graphics, so the app is declared as one. This works for
  sideloading and with developer mode, but Google Play review would likely reject a
  non-navigation app in that category. To publish, swap the gauge for a template UI
  (e.g. a `PaneTemplate` with RPM/gear rows), or target Android Automotive OS.
- **Car sensor support varies.** Many head units don't expose the accelerometer, and some
  don't expose speed. The app falls back to the phone, so mount the phone firmly: a phone
  sliding around in a cup holder shows up as "acceleration".
- The car accelerometer's forward axis is assumed to be index 1 (`MotionFusion.carForwardAxis`).
- The sound plays on the media stream without taking audio focus, so it mixes with
  your music instead of pausing it.
- This is an entertainment app. Keep the volume at a level where you can still hear
  sirens and traffic.
