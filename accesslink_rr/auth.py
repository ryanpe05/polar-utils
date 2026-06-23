"""One-time OAuth2 dance: get an access token and register the user."""
import json
import secrets
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse

import requests

ROOT = Path(__file__).parent
CONFIG_PATH = ROOT / "config.json"
TOKEN_PATH = ROOT / "token.json"

AUTHORIZE_URL = "https://flow.polar.com/oauth2/authorization"
TOKEN_URL = "https://polarremote.com/v2/oauth2/token"
REGISTER_URL = "https://www.polaraccesslink.com/v3/users"


def load_config():
    if not CONFIG_PATH.exists():
        sys.exit(f"Missing {CONFIG_PATH}. Copy config.example.json and fill it in.")
    return json.loads(CONFIG_PATH.read_text())


class _CallbackHandler(BaseHTTPRequestHandler):
    code_holder: dict = {}

    def do_GET(self):
        qs = parse_qs(urlparse(self.path).query)
        self.code_holder["code"] = qs.get("code", [None])[0]
        self.code_holder["state"] = qs.get("state", [None])[0]
        self.code_holder["error"] = qs.get("error", [None])[0]
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        msg = "Authorization received. You can close this tab."
        if self.code_holder["error"]:
            msg = f"Error: {self.code_holder['error']}"
        self.wfile.write(msg.encode())

    def log_message(self, *_):
        pass


def get_auth_code(client_id: str, redirect_uri: str) -> str:
    state = secrets.token_urlsafe(16)
    params = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "state": state,
        "scope": "accesslink.read_all",
    }
    url = f"{AUTHORIZE_URL}?{urlencode(params)}"

    host = urlparse(redirect_uri).hostname or "localhost"
    port = urlparse(redirect_uri).port or 5005
    server = HTTPServer((host, port), _CallbackHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    print(f"Opening browser:\n  {url}\n")
    webbrowser.open(url)
    print(f"Waiting for callback on {redirect_uri} ...")

    while "code" not in _CallbackHandler.code_holder:
        pass
    server.shutdown()

    if _CallbackHandler.code_holder.get("state") != state:
        sys.exit("State mismatch — possible CSRF, aborting.")
    if _CallbackHandler.code_holder.get("error"):
        sys.exit(f"Auth error: {_CallbackHandler.code_holder['error']}")
    return _CallbackHandler.code_holder["code"]


def exchange_code(client_id: str, client_secret: str, redirect_uri: str, code: str) -> dict:
    r = requests.post(
        TOKEN_URL,
        data={"grant_type": "authorization_code", "code": code, "redirect_uri": redirect_uri},
        headers={"Accept": "application/json"},
        auth=(client_id, client_secret),
    )
    r.raise_for_status()
    return r.json()


def register_user(access_token: str) -> dict:
    member_id = f"rr-fetcher-{secrets.token_hex(4)}"
    r = requests.post(
        REGISTER_URL,
        json={"member-id": member_id},
        headers={
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
    )
    # 409 means already registered with this token — that's fine.
    if r.status_code == 409:
        return {"already_registered": True}
    r.raise_for_status()
    return r.json()


def main():
    cfg = load_config()
    code = get_auth_code(cfg["client_id"], cfg["redirect_uri"])
    tok = exchange_code(cfg["client_id"], cfg["client_secret"], cfg["redirect_uri"], code)
    reg = register_user(tok["access_token"])

    out = {
        "access_token": tok["access_token"],
        "x_user_id": tok.get("x_user_id"),
        "expires_in": tok.get("expires_in"),
        "registration": reg,
    }
    TOKEN_PATH.write_text(json.dumps(out, indent=2))
    print(f"\nSaved token to {TOKEN_PATH}")
    print(f"  x_user_id: {out['x_user_id']}")
    print(f"  expires_in: {out['expires_in']} seconds")


if __name__ == "__main__":
    main()
