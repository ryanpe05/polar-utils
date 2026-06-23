"""Stream HR + RR intervals from a Polar H9 (or any BLE HR sensor) to CSV.

Subscribes to the standard BLE Heart Rate Measurement characteristic (0x2A37)
and parses each notification per the BLE HR Service spec:

  byte 0       : flags
                   bit 0  -> HR value format (0 = uint8, 1 = uint16)
                   bit 4  -> RR intervals present
  bytes 1..N   : HR value (uint8 or uint16 LE)
  (optional)   : energy expended (uint16 LE) if flag bit 3 set
  (optional)   : RR intervals, each uint16 LE in units of 1/1024 s

Usage:
  python record.py                          # auto-pick first HR device
  python record.py --address <UUID|MAC>     # connect to a specific device
  python record.py --duration 300           # stop after 300 seconds
"""
import argparse
import asyncio
import csv
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient, BleakScanner

ROOT = Path(__file__).parent
DATA_DIR = ROOT / "data"

HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"


def parse_hr_measurement(data: bytes) -> tuple[int, list[int]]:
    flags = data[0]
    hr_16bit = flags & 0x01
    energy_present = (flags >> 3) & 0x01
    rr_present = (flags >> 4) & 0x01

    offset = 1
    if hr_16bit:
        hr = int.from_bytes(data[offset:offset + 2], "little")
        offset += 2
    else:
        hr = data[offset]
        offset += 1

    if energy_present:
        offset += 2

    rr_ms = []
    if rr_present:
        while offset + 1 < len(data):
            rr_1024 = int.from_bytes(data[offset:offset + 2], "little")
            offset += 2
            rr_ms.append(round(rr_1024 * 1000 / 1024, 2))
    return hr, rr_ms


async def pick_device(address: str | None):
    if address:
        print(f"Connecting to {address} ...")
        return address
    print("Scanning 10s for an HR device ...")
    devices = await BleakScanner.discover(timeout=10.0, service_uuids=[HR_SERVICE_UUID])
    if not devices:
        sys.exit("No HR device found. Run scan.py first, or wear the strap.")
    d = devices[0]
    print(f"Picked {d.address}  {d.name or '(no name)'}")
    return d.address


async def stream(address: str, duration: float | None):
    DATA_DIR.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%dT%H%M%S")
    hr_path = DATA_DIR / f"{stamp}_hr.csv"
    rr_path = DATA_DIR / f"{stamp}_rr.csv"

    hr_f = hr_path.open("w", newline="")
    rr_f = rr_path.open("w", newline="")
    hr_w = csv.writer(hr_f)
    rr_w = csv.writer(rr_f)
    hr_w.writerow(["iso_time", "hr_bpm"])
    rr_w.writerow(["iso_time", "rr_ms", "cum_rr_ms"])

    cum = 0.0
    start = time.monotonic()
    stop_flag = asyncio.Event()

    def on_notify(_handle, data: bytearray):
        nonlocal cum
        now = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
        try:
            hr, rrs = parse_hr_measurement(bytes(data))
        except Exception as e:
            print(f"  parse error: {e}  raw={data.hex()}")
            return
        hr_w.writerow([now, hr])
        for v in rrs:
            cum += v
            rr_w.writerow([now, v, round(cum, 2)])
        print(f"  hr={hr:>3} bpm   rr={rrs if rrs else '-'}", flush=True)

    print(f"Writing HR -> {hr_path.name}")
    print(f"Writing RR -> {rr_path.name}")
    print("Press Ctrl-C to stop.\n")

    async with BleakClient(address) as client:
        await client.start_notify(HR_MEASUREMENT_UUID, on_notify)
        try:
            if duration:
                await asyncio.wait_for(stop_flag.wait(), timeout=duration)
            else:
                await stop_flag.wait()
        except (asyncio.TimeoutError, KeyboardInterrupt):
            pass
        finally:
            await client.stop_notify(HR_MEASUREMENT_UUID)

    hr_f.close()
    rr_f.close()
    elapsed = time.monotonic() - start
    print(f"\nDone. {elapsed:.1f}s elapsed.")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--address", help="BLE address (or macOS UUID). Otherwise auto-pick.")
    ap.add_argument("--duration", type=float, default=None,
                    help="Stop after N seconds. Otherwise run until Ctrl-C.")
    args = ap.parse_args()

    address = await pick_device(args.address)
    try:
        await stream(address, args.duration)
    except KeyboardInterrupt:
        print("\nInterrupted.")


if __name__ == "__main__":
    asyncio.run(main())
