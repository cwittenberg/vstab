#!/usr/bin/env python3
"""Download and inspect a pinned publisher Codex package; never install or authenticate.

This developer helper leaves the real app's install action to the Code OSS
Extensions view. It does not read any Codex configuration or credential files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "extensions/codex/linux-x64.lock.json"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="download and verify publisher VSIX")
    parser.add_argument("--inspect", action="store_true", help="inspect an already downloaded VSIX")
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    if lock["id"] != "openai.chatgpt" or lock["publisherVerified"] is not True:
        raise RuntimeError("Unexpected publisher identity in lock")
    package = ROOT / ".local/downloads" / (
        f"openai.chatgpt-{lock['version']}@{lock['targetPlatform']}.vsix"
    )
    if args.download:
        package.parent.mkdir(parents=True, exist_ok=True)
        if not package.exists() or digest(package) != lock["sha256"]:
            temporary = package.with_suffix(".vsix.part")
            request = urllib.request.Request(lock["downloadUrl"], headers={"User-Agent": "vstab-package-inspector/1"})
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if digest(temporary) != lock["sha256"]:
                temporary.unlink(missing_ok=True)
                raise RuntimeError("Publisher VSIX SHA-256 mismatch")
            temporary.replace(package)
        print(f"Verified package: {package}")
    if args.inspect:
        if not package.exists() or digest(package) != lock["sha256"]:
            raise RuntimeError("Run --download first; package must match pinned hash")
        with zipfile.ZipFile(package) as archive:
            manifest = json.loads(archive.read("extension/package.json"))
            if manifest.get("publisher") != "openai" or manifest.get("name") != "chatgpt" or manifest.get("version") != lock["version"]:
                raise RuntimeError("VSIX manifest identity does not match lock")
            output = ROOT / ".local/codex-inspection"
            output.mkdir(parents=True, exist_ok=True)
            for item in ["extension/package.json", "extension/LICENSE.md", "extension/out/extension.js"]:
                (output / Path(item).name).write_bytes(archive.read(item))
            files = archive.namelist()
            report = {
                "id": lock["id"], "version": manifest["version"],
                "sha256": lock["sha256"], "main": manifest.get("main"),
                "browser": manifest.get("browser"),
                "proposedApis": manifest.get("enabledApiProposals", []),
                "binaryPaths": [name for name in files if "/bin/" in name and not name.endswith("/")],
                "commandIds": [item["command"] for item in manifest.get("contributes", {}).get("commands", [])],
                "status": "package inspection only; no extension execution or authentication",
            }
            (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(report, indent=2))
    if not args.download and not args.inspect:
        print(json.dumps(lock, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"codex-package: {error}", file=sys.stderr)
        raise SystemExit(1)
