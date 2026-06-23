"""Inspect what AccessLink actually returns for your exercises.

Prints, per exercise:
  - basic metadata (sport, device, duration)
  - the list of sample-type keys present in the `samples` array
  - whether TCX and FIT exports are downloadable
  - a peek at TCX content for RR-bearing extensions
"""
import json
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).parent
TOKEN_PATH = ROOT / "token.json"
BASE = "https://www.polaraccesslink.com/v3"

SAMPLE_TYPE_NAMES = {
    "0": "Heart rate", "1": "Speed", "2": "Cadence", "3": "Altitude",
    "4": "Power", "5": "Power pedaling index", "6": "Power L/R balance",
    "7": "Air pressure", "8": "Running cadence", "9": "Temperature",
    "10": "Distance", "11": "RR Interval",
}


def main():
    if not TOKEN_PATH.exists():
        sys.exit("Run auth.py first.")
    tok = json.loads(TOKEN_PATH.read_text())
    headers = {"Authorization": f"Bearer {tok['access_token']}", "Accept": "application/json"}

    r = requests.get(f"{BASE}/exercises", headers=headers, params={"samples": "true"})
    if r.status_code == 204:
        print("No exercises returned.")
        return
    r.raise_for_status()
    exercises = r.json()
    print(f"Got {len(exercises)} exercise(s).\n")

    for ex in exercises:
        ex_id = ex.get("id")
        print(f"=== {ex_id}  {ex.get('start_time')}  {ex.get('sport')}  device={ex.get('device')}")
        samples = ex.get("samples") or []
        if not samples:
            print("  samples: <empty>")
        else:
            for s in samples:
                # Endpoint uses snake_case; old transactions path used kebab-case. Try both.
                t = str(s.get("sample_type", s.get("sample-type")))
                name = SAMPLE_TYPE_NAMES.get(t, "?")
                data = s.get("data") or ""
                count = len([x for x in data.split(",") if x.strip()])
                rate = s.get("recording_rate", s.get("recording-rate"))
                print(f"  sample-type {t:>2} ({name}): {count} points, rate={rate}")
                print(f"      keys: {list(s.keys())}")
                print(f"      data preview: {data[:120]}")

        # Try TCX
        tcx = requests.get(f"{BASE}/exercises/{ex_id}/tcx",
                           headers={**headers, "Accept": "*/*"})
        print(f"  TCX: HTTP {tcx.status_code}, {len(tcx.content)} bytes", end="")
        if tcx.ok:
            body = tcx.text
            hits = []
            for needle in ("RRInterval", "rr-interval", "RR>", "HeartRateBpm", "<ns"):
                if needle in body:
                    hits.append(needle)
            print(f", contains: {hits}")
        else:
            print()

        # Try FIT (binary — just confirm it exists and grab size)
        fit = requests.get(f"{BASE}/exercises/{ex_id}/fit")
        # FIT needs auth too
        fit = requests.get(f"{BASE}/exercises/{ex_id}/fit", headers=headers)
        print(f"  FIT: HTTP {fit.status_code}, {len(fit.content)} bytes\n")


if __name__ == "__main__":
    main()
