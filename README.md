# polar-rr

Two small Python projects for getting **heart rate and RR-interval data**
out of a Polar H9 chest strap.

| Subdirectory | What it does | When to use it |
| --- | --- | --- |
| [`ble_rr/`](./ble_rr) | Streams HR + RR live from the H9 over Bluetooth LE and writes CSV. Uses the standard BLE Heart Rate Service, so it also works with H10, Wahoo TICKR, Garmin HRM-Pro, etc. | You want beat-to-beat RR data. **Recommended.** |
| [`accesslink_rr/`](./accesslink_rr) | OAuth2 client for the Polar AccessLink REST API. Pulls exercises (HR, speed, distance, FIT/TCX) you've already uploaded to Polar Flow. | You want post-hoc access to your Polar Flow data, or for archiving HR/route info. |

## Why two projects?

The original goal was to pull RR intervals from existing Polar Flow activities
via AccessLink. That turned out to be a dead end for **Polar Flow phone app +
H9** recordings: the API exposes HR/speed/distance but not RR, the TCX export
has no RR extensions, and the FIT export has no `hrv` messages. RR via
AccessLink appears to require recording with a **Polar watch** paired to an
H-series strap; the watch is what writes RR to the upload.

The live-BLE path (`ble_rr/`) sidesteps the cloud entirely and gives every
beat, not 1-Hz downsampled HR. It's the simpler and more reliable answer if
RR is what you actually want.

## Quick start (RR data, today)

```
cd ble_rr
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python scan.py        # confirm H9 shows up
python record.py      # Ctrl-C to stop; CSVs land in ble_rr/data/
```

See [`ble_rr/README.md`](./ble_rr/README.md) for macOS Bluetooth permission
notes and output format.

## On a bike / phone instead of a Mac?

This repo is desktop-only. For mobile RR capture, use an existing app:

- **Android**: *Polar Sensor Logger* (Jukka Happonen, free) — most likely
  works with H9 via standard BLE HR; *HR Logger* as a fallback.
- **iOS**: *HRV Logger* (Marco Altini, paid) — purpose-built for recording
  RR during workouts.

## Layout

```
.
├── ble_rr/             # live BLE capture (recommended)
│   ├── scan.py
│   ├── record.py
│   └── README.md
├── accesslink_rr/      # AccessLink REST API client
│   ├── auth.py
│   ├── fetch_rr.py
│   ├── diagnose.py
│   ├── check_fit_rr.py
│   └── README.md
└── README.md           # this file
```
