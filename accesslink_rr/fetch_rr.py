"""Pull RR-interval samples for all available exercises and write per-exercise CSVs.

Uses the non-deprecated endpoint: GET /v3/exercises?samples=true returns the
full list with sample data embedded (sample type 11 = RR interval, ms).

Caveats from Polar docs:
  - Only exercises uploaded to Flow in the last 30 days are returned.
  - Only exercises uploaded AFTER the user is registered with your client are returned.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).parent
TOKEN_PATH = ROOT / "token.json"
DATA_DIR = ROOT / "data"
BASE = "https://www.polaraccesslink.com/v3"
RR_SAMPLE_TYPE = "11"


def load_token() -> dict:
    if not TOKEN_PATH.exists():
        sys.exit("No token.json — run `python auth.py` first.")
    return json.loads(TOKEN_PATH.read_text())


def list_exercises(headers, include_samples=True) -> list:
    params = {"samples": "true"} if include_samples else {}
    r = requests.get(f"{BASE}/exercises", headers=headers, params=params)
    if r.status_code == 204:
        return []
    r.raise_for_status()
    return r.json()


def parse_rr_data(raw: str) -> list:
    out = []
    for tok in raw.split(","):
        tok = tok.strip()
        if not tok or tok.lower() == "null":
            out.append(None)
        else:
            out.append(int(tok))
    return out


def extract_rr(exercise: dict) -> list | None:
    for s in exercise.get("samples", []) or []:
        if str(s.get("sample-type")) == RR_SAMPLE_TYPE and s.get("data"):
            return parse_rr_data(s["data"])
    return None


def write_csv(path: Path, summary: dict, rr_ms: list):
    with path.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["# exercise_id", summary.get("id")])
        w.writerow(["# start_time", summary.get("start_time") or summary.get("start-time")])
        w.writerow(["# sport", summary.get("sport")])
        w.writerow(["# duration", summary.get("duration")])
        w.writerow(["# device", summary.get("device")])
        w.writerow(["beat_index", "rr_ms", "cum_time_ms"])
        cum = 0
        for i, v in enumerate(rr_ms):
            if v is not None:
                cum += v
            w.writerow([i, v if v is not None else "", cum])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list-only", action="store_true",
                    help="Print exercise summaries (with RR availability) without writing files.")
    args = ap.parse_args()

    DATA_DIR.mkdir(exist_ok=True)
    tok = load_token()
    headers = {"Authorization": f"Bearer {tok['access_token']}", "Accept": "application/json"}

    exercises = list_exercises(headers, include_samples=not args.list_only)
    if not exercises:
        print("No exercises returned. Possible reasons:")
        print("  - No exercises uploaded in the last 30 days.")
        print("  - All your existing exercises were uploaded BEFORE OAuth registration.")
        return

    print(f"Found {len(exercises)} exercise(s).\n")

    if args.list_only:
        # Need samples to know RR availability, but listing-only stays lightweight: just metadata.
        for ex in exercises:
            print(f"  {ex.get('id')}  {ex.get('start_time')}  "
                  f"{ex.get('sport')}  {ex.get('duration')}  device={ex.get('device')}")
        return

    written, no_rr = 0, 0
    for ex in exercises:
        ex_id = ex.get("id")
        start = (ex.get("start_time") or "unknown").replace(":", "-")

        rr_ms = extract_rr(ex)
        if not rr_ms:
            print(f"  - {ex_id} ({start}): no RR data")
            no_rr += 1
            continue

        out_path = DATA_DIR / f"{start}_{ex_id}.csv"
        write_csv(out_path, ex, rr_ms)
        summary_only = {k: v for k, v in ex.items() if k != "samples"}
        (DATA_DIR / f"{start}_{ex_id}.summary.json").write_text(json.dumps(summary_only, indent=2))
        print(f"  + {ex_id} ({start}): {len(rr_ms)} RR samples -> {out_path.name}")
        written += 1

    print(f"\nDone. {written} written, {no_rr} without RR.")


if __name__ == "__main__":
    main()
