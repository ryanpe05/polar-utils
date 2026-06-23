# ble_rr

Stream **heart rate and RR (beat-to-beat) intervals** live from a Polar H9
(or any BLE heart-rate sensor) over Bluetooth and write them to CSV.

Unlike `accesslink_rr/`, this requires no Polar account, no cloud round-trip,
and gives you every beat — not 1-Hz downsampled HR. Works with H6/H7/H9/H10
and any other strap that implements the standard BLE Heart Rate Service,
including Wahoo TICKR, Garmin HRM-Pro, etc.

## How it works

Subscribes to the standard BLE **Heart Rate Service** (`0x180D`),
characteristic **Heart Rate Measurement** (`0x2A37`), and parses each
notification per the BLE spec:

- byte 0: flags (HR width, RR-present bit, etc.)
- HR value (1 or 2 bytes)
- optional energy expended (2 bytes)
- 0+ RR intervals, each a `uint16` LE in units of 1/1024 s — converted to ms

Notifications fire roughly once per second; each one carries the latest HR
plus 0–2 RR intervals captured since the last notification.

## Files

| File | Purpose |
| --- | --- |
| `scan.py` | 10-second BLE scan that lists nearby HR devices. Run first to find your H9's address. |
| `record.py` | Connect to a device, subscribe to HR notifications, write `data/<timestamp>_hr.csv` and `data/<timestamp>_rr.csv`. |
| `data/` | Output (gitignored). |

## Setup

```
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

## Use

```
python scan.py                              # list nearby HR devices
python record.py                            # auto-pick first HR device
python record.py --address <UUID|MAC>       # specific device
python record.py --duration 300             # stop after 5 minutes
```

Press Ctrl-C any time to stop cleanly.

## Output format

`data/<timestamp>_hr.csv`:

```
iso_time, hr_bpm
2026-06-22T20:15:03.412+00:00, 72
```

`data/<timestamp>_rr.csv`:

```
iso_time, rr_ms, cum_rr_ms
2026-06-22T20:15:03.412+00:00, 832.03, 832.03
2026-06-22T20:15:04.214+00:00, 818.36, 1650.39
```

`cum_rr_ms` is the cumulative time across all RR intervals — useful as a
beat-clock independent of system time.

## macOS gotchas

- **Bluetooth permission**: the first run will prompt macOS to grant
  Bluetooth access to your terminal app (Terminal, iTerm, VS Code, etc.).
  If `scan.py` finds nothing, check
  System Settings → Privacy & Security → Bluetooth.
- **One connection at a time**: the H9 can only be paired to one app.
  Quit Polar Beat / Polar Flow / any connected watch before recording.
- **Wake the strap**: the H9 wakes on chest contact. If scanning finds
  nothing, moisten the electrodes — dry straps don't transmit.
- On macOS, BLE addresses appear as opaque CoreBluetooth UUIDs (not MACs).
  They're stable per-host, so you can hardcode the one `scan.py` prints.
