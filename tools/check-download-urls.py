#!/usr/bin/env python3
"""Check that the component download URLs hard-coded in the app still resolve.

Scans the setup sources for release-asset URLs (the Prepare Vapor package list and the
guided-setup fallbacks, which are used when the remote default.json is unavailable) and
the remote default.json itself, then requests each URL. Exits 1 if any returns an HTTP
error, so renamed or deleted release assets are caught before a user hits them.
"""
import json, pathlib, re, sys, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = ["app/src/main/feature/setup/VaporPreparation.kt", "app/src/main/feature/setup/SetupWizardActivity.kt"]
DEFAULT_JSON = "https://raw.githubusercontent.com/nicholasx417/WinNative-Components/main/default.json"
PATTERN = re.compile(r'https://github\.com/[\w.-]+/[\w.-]+/releases/download/[^"\s]+\.(?:wcp|tzst|txz|zip)')

def status(url: str) -> str:
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "vapor-link-check"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return str(response.status)
    except urllib.error.HTTPError as error:
        return str(error.code)
    except Exception as error:  # network trouble is reported, not treated as a dead link
        return f"unreachable ({type(error).__name__})"

def main() -> int:
    urls = {}
    for source in SOURCES:
        for url in PATTERN.findall((ROOT / source).read_text(encoding="utf-8")):
            urls.setdefault(url, f"{source}")
    try:
        with urllib.request.urlopen(DEFAULT_JSON, timeout=60) as response:
            for entry in json.load(response):
                urls.setdefault(entry["remoteUrl"], "default.json")
    except Exception as error:
        print(f"warning: could not read default.json: {error}")
    dead = []
    for url, origin in sorted(urls.items()):
        code = status(url)
        print(f"{code:>4}  {url}  ({origin})")
        if code.isdigit() and int(code) >= 400:
            dead.append(url)
    print(f"{len(urls)} URLs checked, {len(dead)} dead")
    return 1 if dead else 0

if __name__ == "__main__":
    sys.exit(main())
