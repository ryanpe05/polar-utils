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
  python record.py --debug                  # verbose diagnostics (see below)

Debug mode (--debug) logs, to stderr:
  - every discovered device (name, RSSI, advertised services)
  - the GATT services/characteristics exposed by the connected sensor
  - per-notification raw hex, the decoded flags byte, and field offsets
  - a warning when a notification carries extra bytes but does NOT set the
    RR-present flag (bit 4) -- the tell-tale sign a sensor keeps its
    beat-to-beat data in a vendor field rather than the standard 0x2A37.
"""
import argparse
import asyncio
import csv
import logging
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient, BleakScanner

ROOT = Path(__file__).parent
DATA_DIR = ROOT / "data"

HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

log = logging.getLogger("ble_rr")


def _decode_flags(flags: int) -> str:
    """Human-readable breakdown of the HR Measurement flags byte."""
    bits = {
        "hr_16bit": flags & 0x01,
        "sensor_contact_detected": (flags >> 1) & 0x01,
        "sensor_contact_supported": (flags >> 2) & 0x01,
        "energy_expended_present": (flags >> 3) & 0x01,
        "rr_present": (flags >> 4) & 0x01,
    }
    set_bits = ", ".join(name for name, on in bits.items() if on) or "(none)"
    return f"0x{flags:02x} (0b{flags:08b}) -> {set_bits}"


def parse_hr_measurement(data: bytes) -> tuple[int, list[int]]:
    flags = data[0]
    hr_16bit = flags & 0x01
    energy_present = (flags >> 3) & 0x01
    rr_present = (flags >> 4) & 0x01

    log.debug("raw notification: %d bytes  hex=%s", len(data), data.hex())
    log.debug("flags byte: %s", _decode_flags(flags))

    offset = 1
    if hr_16bit:
        hr = int.from_bytes(data[offset:offset + 2], "little")
        offset += 2
    else:
        hr = data[offset]
        offset += 1
    log.debug("hr=%d bpm (format=%s, consumed up to offset %d)",
              hr, "uint16" if hr_16bit else "uint8", offset)

    if energy_present:
        energy = int.from_bytes(data[offset:offset + 2], "little")
        log.debug("energy expended present: %d kJ (offset %d->%d)",
                  energy, offset, offset + 2)
        offset += 2

    rr_ms = []
    if rr_present:
        rr_bytes = len(data) - offset
        log.debug("rr flag set: %d trailing bytes -> expecting %d RR value(s)",
                  rr_bytes, rr_bytes // 2)
        if rr_bytes % 2 != 0:
            log.warning("rr region has odd byte count (%d); last byte ignored",
                        rr_bytes)
        while offset + 1 < len(data):
            rr_1024 = int.from_bytes(data[offset:offset + 2], "little")
            offset += 2
            rr_ms.append(round(rr_1024 * 1000 / 1024, 2))
        log.debug("parsed RR (ms): %s", rr_ms)
    else:
        trailing = len(data) - offset
        log.debug("rr flag NOT set; %d unparsed trailing byte(s) after HR field",
                  trailing)
        if trailing > 0:
            log.warning(
                "device sent %d extra byte(s) but did not set the RR flag (bit 4); "
                "raw=%s -- RR data may be in a vendor-specific field, not 0x2A37",
                trailing, data.hex())

    return hr, rr_ms


async def pick_device(address: str | None):
    if address:
        print(f"Connecting to {address} ...")
        return address
    print("Scanning 10s for an HR device ...")
    devices = await BleakScanner.discover(
        timeout=10.0, service_uuids=[HR_SERVICE_UUID], return_adv=True
    )
    if not devices:
        sys.exit("No HR device found. Run scan.py first, or wear the strap.")
    for addr, (dev, adv) in devices.items():
        log.debug(
            "discovered: %s  name=%r  rssi=%s  services=%s",
            addr, dev.name, getattr(adv, "rssi", "?"), adv.service_uuids,
        )
    d = next(iter(devices.values()))[0]
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

    notify_count = 0

    def on_notify(_handle, data: bytearray):
        nonlocal cum, notify_count
        notify_count += 1
        now = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
        log.debug("notification #%d @ %s", notify_count, now)
        try:
            hr, rrs = parse_hr_measurement(bytes(data))
        except Exception as e:
            print(f"  parse error: {e}  raw={data.hex()}")
            log.exception("failed to parse notification raw=%s", data.hex())
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
        log.debug("connected=%s to %s", client.is_connected, address)
        for service in client.services:
            log.debug("service %s  %s", service.uuid, service.description)
            for ch in service.characteristics:
                log.debug("  char %s  props=%s  %s",
                          ch.uuid, ",".join(ch.properties), ch.description)
        await client.start_notify(HR_MEASUREMENT_UUID, on_notify)
        log.debug("subscribed to HR Measurement (%s)", HR_MEASUREMENT_UUID)
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
    ap.add_argument("--debug", action="store_true",
                    help="Verbose debug logging: raw bytes, decoded flags, "
                         "discovered services/characteristics. Useful for "
                         "diagnosing why a sensor reports HR but no RR.")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.debug else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    if args.debug:
        # bleak's own logs are noisy but useful when chasing GATT issues.
        logging.getLogger("bleak").setLevel(logging.DEBUG)
        log.debug("debug logging enabled")

    address = await pick_device(args.address)
    try:
        await stream(address, args.duration)
    except KeyboardInterrupt:
        print("\nInterrupted.")


if __name__ == "__main__":
    asyncio.run(main())
