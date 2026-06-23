"""Scan for nearby BLE heart-rate devices and print their names + addresses.

Run this first to find your H9's address (looks like 'Polar H9 XXXXXXXX').
On macOS, addresses are opaque UUIDs assigned by CoreBluetooth — that's fine,
they're stable per-host so you can hardcode it into record.py.
"""
import asyncio

from bleak import BleakScanner

HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"


async def main():
    print("Scanning 10s for BLE devices advertising Heart Rate Service...")
    results = await BleakScanner.discover(
        timeout=10.0, service_uuids=[HR_SERVICE_UUID], return_adv=True
    )
    if not results:
        print("\nNo HR devices found. Make sure your H9 is:")
        print("  - worn (chest contact wakes it up), and")
        print("  - not paired/connected to another app (Polar Flow, watch, etc.).")
        return
    print(f"\nFound {len(results)}:")
    for address, (device, adv) in results.items():
        print(f"  {address}   {device.name or adv.local_name or '(no name)'}   RSSI={adv.rssi}")


if __name__ == "__main__":
    asyncio.run(main())
