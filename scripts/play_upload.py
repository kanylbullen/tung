#!/usr/bin/env python3
"""Uploads the phone and the watch app bundles to a Play track in one edit.

The Google Play Developer API (androidpublisher v3) with a service account's JSON key,
standard library only: the JWT is signed by `openssl`, the requests are urllib. One
edit holds both bundles, so the phone and the watch go out together or not at all.
The phone goes to TRACK and the watch to the Wear OS form factor's track of the same
name (`wear:TRACK`). Committing the edit sends it for review where the track has one,
since managed publishing is off; a draft release is saved and waits in the console.

    scripts/play_upload.py --track production --phone app.aab --watch wear.aab \
        --notes deploy/release-notes

`scripts/release.sh --upload TRACK` runs it after the build. `--dry-run` signs in,
opens an edit, reads the tracks and throws the edit away: no bundle is uploaded.

The key never reaches stdout, a log or a command line: it goes to openssl on stdin.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PACKAGE = "io.github.kanylbullen.tung"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
DEFAULT_KEY = Path.home() / ".config/tung/play-publisher.json"
# Play's limit on one language's "What's new".
MAX_NOTES = 500
WEAR_PREFIX = "wear:"


class PlayError(Exception):
    pass


def fail(message: str) -> None:
    print(f"play-upload: {message}", file=sys.stderr)
    sys.exit(1)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(key: dict) -> str:
    """A service account's OAuth token: a JWT signed RS256 by openssl, traded at token_uri."""
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claim = b64url(json.dumps({
        "iss": key["client_email"],
        "scope": SCOPE,
        "aud": key["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claim}".encode()
    # The data is not secret and goes in a file; the key goes to openssl on stdin only.
    with tempfile.NamedTemporaryFile() as data:
        data.write(signing_input)
        data.flush()
        signed = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", "/dev/stdin", data.name],
            input=key["private_key"].encode(), capture_output=True,
        )
    if signed.returncode != 0 or not signed.stdout:
        raise PlayError("openssl could not sign the token request with the key")
    jwt = f"{header}.{claim}.{b64url(signed.stdout)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt,
    }).encode()
    answer = request("POST", key["token_uri"], None, body, "application/x-www-form-urlencoded")
    token = answer.get("access_token")
    if not token:
        raise PlayError("the token endpoint answered without an access token")
    return token


def request(method: str, url: str, token: str | None, body: bytes | None = None,
            content_type: str = "application/json", timeout: int = 120) -> dict:
    headers = {"Content-Type": content_type} if body is not None else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        try:
            detail = json.loads(detail).get("error", {}).get("message", detail)
        except ValueError:
            pass
        # The path only, never the query or a header: nothing secret is in either, but keep it short.
        raise PlayError(f"{method} {urllib.parse.urlsplit(url).path} answered {e.code}: {detail}") from None
    return json.loads(text) if text.strip() else {}


def read_notes(directory: Path) -> list[dict]:
    """One `<language>.txt` per language (en-GB.txt, sv-SE.txt), as Play's releaseNotes."""
    notes = []
    for path in sorted(directory.glob("*.txt")):
        language = path.stem
        if not re.fullmatch(r"[a-z]{2,3}(-[A-Z]{2})?", language):
            fail(f"{path.name}: the file name is not a language tag such as en-GB or sv-SE")
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            fail(f"{path.name} is empty")
        if len(text) > MAX_NOTES:
            fail(f"{path.name} has {len(text)} characters; Play takes at most {MAX_NOTES}")
        notes.append({"language": language, "text": text})
    if not notes:
        fail(f"no <language>.txt in {directory}")
    return notes


def upload_bundle(token: str, edit: str, path: Path) -> int:
    url = f"{UPLOAD}/edits/{edit}/bundles?uploadType=media"
    answer = request("POST", url, token, path.read_bytes(), "application/octet-stream", timeout=600)
    code = answer.get("versionCode")
    if not isinstance(code, int):
        raise PlayError(f"Play took {path.name} but answered no versionCode")
    return code


def release(code: int, status: str, notes: list[dict] | None) -> dict:
    entry: dict = {"versionCodes": [str(code)], "status": status}
    if notes:
        entry["releaseNotes"] = notes
    return entry


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--track", required=True,
                        help="the phone's track: internal, alpha, beta, production or a custom closed track")
    parser.add_argument("--phone", type=Path, help="the phone's AAB")
    parser.add_argument("--watch", type=Path, help="the watch's AAB, to the wear: track of the same name")
    parser.add_argument("--phone-version-code", type=int, help="refuse unless Play reads this versionCode in the phone's bundle")
    parser.add_argument("--watch-version-code", type=int, help="the same for the watch")
    parser.add_argument("--status", choices=["completed", "draft"], default="completed",
                        help="completed goes out (after review where the track has one); draft waits in the console")
    parser.add_argument("--notes", type=Path, help="a directory of <language>.txt release notes")
    parser.add_argument("--key", type=Path, default=Path(os.environ.get("TUNG_PLAY_KEY", DEFAULT_KEY)),
                        help=f"the service account's JSON key (default $TUNG_PLAY_KEY or {DEFAULT_KEY})")
    parser.add_argument("--replace-in-review", action="store_true",
                        help="cancel changes already in review and send them with this edit; without it the commit refuses")
    parser.add_argument("--phone-only", action="store_true",
                        help="a dry run checks only the phone's track (alpha has no wear:alpha)")
    parser.add_argument("--dry-run", action="store_true",
                        help="sign in, open an edit, read the tracks, throw the edit away; nothing is uploaded")
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    if not re.fullmatch(r"[a-z0-9][a-z0-9_ -]*", args.track):
        fail(f"--track {args.track!r} is not a track name")
    if args.phone_only and args.watch:
        fail("--phone-only with --watch")
    if not args.dry_run and not (args.phone or args.watch):
        fail("nothing to upload: give --phone, --watch or both")
    for path in (args.phone, args.watch):
        if path and not path.is_file():
            fail(f"{path} is not a file")
    notes = read_notes(args.notes) if args.notes else None
    if not args.key.is_file():
        fail(f"no service account key at {args.key}")
    if args.key.stat().st_mode & 0o077:
        fail(f"{args.key} is readable by others; chmod 600 it")
    try:
        key = json.loads(args.key.read_text())
        key["client_email"], key["private_key"], key["token_uri"]
    except (ValueError, KeyError):
        fail(f"{args.key} is not a service account key")

    phone_track = args.track
    watch_track = WEAR_PREFIX + args.track
    edit = None
    token = None
    try:
        token = access_token(key)
        print(f"signed in as {key['client_email']}")
        edit = request("POST", f"{API}/edits", token, b"{}")["id"]
        tracks = {t["track"]: t for t in request("GET", f"{API}/edits/{edit}/tracks", token).get("tracks", [])}
        for name, wanted in ((phone_track, args.phone or args.dry_run), (watch_track, args.watch or (args.dry_run and not args.phone_only))):
            if wanted and name not in tracks:
                raise PlayError(f"Play has no track {name!r}; it has {', '.join(sorted(tracks))}")
        for name in (phone_track, watch_track):
            for r in tracks.get(name, {}).get("releases", []):
                print(f"  {name}: {r.get('name', '?')} {r.get('status')} versionCodes {','.join(r.get('versionCodes', []))}")

        if args.dry_run:
            print("dry run: the edit is thrown away, nothing was uploaded")
            return

        plans = []
        for label, path, expected, track in (
            ("phone", args.phone, args.phone_version_code, phone_track),
            ("watch", args.watch, args.watch_version_code, watch_track),
        ):
            if not path:
                continue
            size = path.stat().st_size / 1_000_000
            print(f"uploading the {label}'s bundle ({size:.1f} MB)…")
            code = upload_bundle(token, edit, path)
            if expected is not None and code != expected:
                raise PlayError(f"Play read versionCode {code} in the {label}'s bundle, not {expected}")
            print(f"  versionCode {code}")
            plans.append((track, code))

        for track, code in plans:
            # A PUT replaces the track's whole list. A completed release supersedes what was
            # out, so it goes alone; a draft must keep what is out (or in review) beside it,
            # replacing only an earlier draft.
            kept = [] if args.status == "completed" else [
                r for r in tracks[track].get("releases", []) if r.get("status") != "draft"]
            body = json.dumps({"track": track, "releases": kept + [release(code, args.status, notes)]}).encode()
            request("PUT", f"{API}/edits/{edit}/tracks/{urllib.parse.quote(track, safe='')}", token, body)
            print(f"{track}: versionCode {code}, {args.status}")

        request("POST", f"{API}/edits/{edit}:validate", token, b"{}")
        behaviour = "CANCEL_IN_REVIEW_AND_SUBMIT" if args.replace_in_review else "ERROR_IF_IN_REVIEW"
        request("POST", f"{API}/edits/{edit}:commit?changesInReviewBehavior={behaviour}", token, b"{}")
        edit = None
        print("committed." + (" Sent for review where the track has one." if args.status == "completed"
                              else " The releases are drafts: send them from the Play Console."))
    except PlayError as e:
        fail(str(e))
    finally:
        if edit and token:
            try:
                request("DELETE", f"{API}/edits/{edit}", token)
            except PlayError:
                pass


if __name__ == "__main__":
    main()
