"""Download each exercise's FIT and report whether RR is present.

FIT stores beat-to-beat RR in `hrv` messages: each message has a `time` field
which is itself a list of RR intervals (seconds). Also dumps every message type
seen, so we can spot RR hiding under a non-standard name.
"""
import io
import json
import sys
from collections import Counter
from pathlib import Path

import requests
from fitparse import FitFile

ROOT = Path(__file__).parent
TOKEN_PATH = ROOT / "token.json"
DATA_DIR = ROOT / "data"
BASE = "https://www.polaraccesslink.com/v3"


def main():
    if not TOKEN_PATH.exists():
        sys.exit("Run auth.py first.")
    tok = json.loads(TOKEN_PATH.read_text())
    headers = {"Authorization": f"Bearer {tok['access_token']}", "Accept": "application/json"}

    exercises = requests.get(f"{BASE}/exercises", headers=headers).json()
    DATA_DIR.mkdir(exist_ok=True)

    for ex in exercises:
        ex_id = ex["id"]
        print(f"\n=== {ex_id}  {ex.get('start_time')}")

        fit_bytes = requests.get(f"{BASE}/exercises/{ex_id}/fit", headers=headers).content
        fit_path = DATA_DIR / f"{ex_id}.fit"
        fit_path.write_bytes(fit_bytes)
        print(f"  saved {fit_path} ({len(fit_bytes)} bytes)")

        try:
            fit = FitFile(io.BytesIO(fit_bytes))
            msg_counts = Counter()
            rr_values = []
            for msg in fit.get_messages():
                msg_counts[msg.name] += 1
                if msg.name == "hrv":
                    for f in msg.fields:
                        if f.name == "time" and f.value is not None:
                            vals = f.value if isinstance(f.value, list) else [f.value]
                            rr_values.extend(v for v in vals if v is not None)
            print(f"  FIT message types: {dict(msg_counts)}")
            print(f"  RR samples found: {len(rr_values)}")
            if rr_values:
                ms = [round(v * 1000, 1) for v in rr_values[:20]]
                print(f"  first 20 RR (ms): {ms}")
        except Exception as e:
            print(f"  FIT parse failed: {e}")


if __name__ == "__main__":
    main()
