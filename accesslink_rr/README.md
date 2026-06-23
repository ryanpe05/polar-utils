# accesslink_rr

Pull exercise data (and, in theory, RR intervals) from Polar Flow via the
**Polar AccessLink REST API**.

## Status / caveat

Polar's docs say RR-interval samples (sample type `11`, ms) are exposed for
training recorded with H6/H7/H9/H10 straps. In practice, sessions recorded
with the **Polar Flow phone app + H9** come back with HR/speed/distance but
**no RR data** — neither in the JSON `samples` array, the TCX export, nor
the FIT export (`hrv` messages absent).

RR via AccessLink seems to require recording with a **Polar watch** (Vantage,
Grit X, M-series, etc.) paired to an H-series strap, since the watch is what
actually writes RR to the uploaded file. If you only have a phone + strap,
use the sibling `ble_rr/` project instead — it reads RR directly from the H9
over Bluetooth.

This subdirectory is still useful for: pulling HR/speed/distance/route, FIT
exports, and confirming RR availability for new recordings.

## Files

| File | Purpose |
| --- | --- |
| `auth.py` | One-time OAuth2 flow. Opens browser, captures auth code on a local callback server, exchanges for an access token, registers the user. Writes `token.json`. |
| `fetch_rr.py` | Pulls `GET /v3/exercises?samples=true` and writes one CSV per exercise (with RR if present) plus a summary JSON. |
| `diagnose.py` | Prints what sample types each exercise actually contains. Use this when RR data is missing to confirm whether it's the API or the recording. |
| `check_fit_rr.py` | Downloads each exercise's FIT file and parses `hrv` messages to look for beat-to-beat RR there. |
| `config.example.json` | Template for OAuth client credentials. |
| `data/` | Output directory (gitignored). |

## Setup

1. Register an OAuth client at https://admin.polaraccesslink.com.
   - Set redirect URI to `http://localhost:5005/callback`.
   - Save the `client_id` and `client_secret`.
2. Copy and fill in credentials:
   ```
   cp config.example.json config.json
   ```
3. Install deps:
   ```
   python -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt
   ```
4. Authorize (one time):
   ```
   python auth.py
   ```
   Browser opens → log into Polar Flow → token saved to `token.json`.

## Daily use

```
python fetch_rr.py              # write all available exercises to data/
python fetch_rr.py --list-only  # just print what's available
python diagnose.py              # inspect raw sample types per exercise
python check_fit_rr.py          # download FITs and look for hrv (RR) messages
```

## AccessLink constraints to remember

- 30-day rolling window — older exercises are not returned.
- Exercises uploaded to Flow **before** OAuth registration may not appear.
- Rate limit: 500 + 20×users per 15 min; 5000 + 100×users per 24 h.
