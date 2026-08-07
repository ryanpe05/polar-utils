# android_rr

A bare-bones **Android** app that records **RR (beat-to-beat) intervals** from a
Polar H9 (or any BLE Heart Rate strap) and lets you export them to CSV.

This is the on-the-bike companion to [`ble_rr/`](../ble_rr): same BLE protocol,
but it runs on your phone and **keeps recording in the background** so you can
pocket it for a test ride. No account, no cloud.

## What it does

- **Scan** lists every nearby BLE **Heart Rate Service** (`0x180D`) strap so you
  can **pick which one to pair with** — handy when several straps are in the
  room. It then connects only to the strap you chose (and reconnects to that
  same one if it drops out, ignoring other straps in range).
- Subscribes to **Heart Rate Measurement** (`0x2A37`) and parses each
  notification (HR + RR intervals in 1/1024 s units) exactly like
  `ble_rr/record.py`.
- Writes rows to a CSV in the app's external files dir via a **foreground
  service**, so capture survives the screen turning off / the app being
  backgrounded.
- **Export** lets you tick **which recordings** to share, then opens the system
  share sheet with just those CSV files (AirDrop to a Mac, email to yourself,
  save to Drive, etc.). With nothing ticked it offers to export all.

It deliberately has almost no UI: Scan, Stop, Export, and a list of recordings.

## Output format

One CSV per session, `rr_data/<timestamp>_rr.csv`:

```
iso_time,hr_bpm,rr_ms,cum_rr_ms
2026-06-23T20:15:03.412Z,72,832.03,832.03
2026-06-23T20:15:04.214Z,73,818.36,1650.39
```

- One row per RR interval. Notifications with HR but no RR get a row with empty
  `rr_ms`/`cum_rr_ms` so the timeline is never blank.
- `cum_rr_ms` is the cumulative RR time — a beat-clock independent of system
  time, matching `ble_rr`'s convention.

## Build & install

Requires Android Studio (it provisions the Gradle wrapper + SDK automatically).

1. `File → Open` this `android_rr/` directory.
2. Plug in a phone with USB debugging on (or use an emulator — though BLE needs
   real hardware).
3. Hit **Run**.

Or from the command line, once an Android SDK is configured (set `sdk.dir` in
`local.properties`) and the Gradle wrapper has been generated
(`gradle wrapper`):

```
./gradlew installDebug
```

- **minSdk 26** (Android 8.0), **targetSdk 34**.

## Use

1. Wet the H9 electrodes and put it on (the strap wakes on chest contact).
2. Make sure no other app holds the strap (Polar Flow, a watch, etc. — BLE is
   one connection at a time).
3. Open the app, tap **Scan**, grant the Bluetooth / notification permissions.
4. Pick your strap from the list (each entry shows its name, MAC address, and
   signal strength — the nearest strap is usually the strongest). Capture
   starts as soon as you tap it. Tap **Rescan** if it isn't listed yet.
5. The persistent notification shows live HR and the RR count. Pocket the phone
   and ride.
6. Tap **Stop** when done. In the recordings list, **tick the files** you want
   and tap **Export** to share them.

## Permissions

- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+), or
  `ACCESS_FINE_LOCATION` + legacy Bluetooth perms on older devices.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` for background
  capture.
- `POST_NOTIFICATIONS` (Android 13+) for the ongoing-capture notification.

## Notes / limitations

- **Scan** shows every HR strap in range so you can pick the right one when more
  than one is nearby. The scan gives up after ~10 s; hit **Rescan** to retry.
- If the strap drops out of range it auto-rescans and reconnects to the **same**
  strap you selected, ignoring any others in range.
- Battery optimization on some phones can still kill long-running services;
  for a long ride, exempt the app from battery optimization in system settings.
