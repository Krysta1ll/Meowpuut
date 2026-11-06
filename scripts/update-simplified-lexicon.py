#!/usr/bin/env python3
"""Check or stage a reproducible upstream data update. Never runs from a key event."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEST = ROOT / "DataSources/PinyinPro"

def fetch(url):
    return subprocess.check_output(["curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "90", url])

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="Replace verified source snapshots; rebuild and test before release")
    parser.add_argument("--revision", help="Optional upstream 40-character commit")
    args = parser.parse_args()
    current = json.loads((DEST / "SOURCE.json").read_text())
    commit = json.loads(fetch("https://api.github.com/repos/zh-lx/pinyin-pro/commits/" + (args.revision or "main")))
    revision = commit["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision): raise ValueError("Invalid revision")
    print(json.dumps({"installed_source": current["revision"], "upstream": revision,
                      "repository_commit_date": commit["commit"]["committer"]["date"],
                      "changed": revision != current["revision"]}, ensure_ascii=False))
    if not args.apply: return
    paths = {"complete.json": "packages/data/json/complete.json", "LICENSE": "packages/data/LICENSE", "README.md": "packages/data/README.md"}
    payloads = {name: fetch(f"https://raw.githubusercontent.com/zh-lx/pinyin-pro/{revision}/{path}") for name, path in paths.items()}
    if payloads["LICENSE"] != (DEST / "LICENSE").read_bytes():
        raise ValueError("Upstream license changed; source remains untouched for review")
    data = json.loads(payloads["complete.json"])
    if not isinstance(data, dict) or len(data) < 100000: raise ValueError("Unexpected dictionary shape or truncation")
    for word, item in data.items():
        if not isinstance(word, str) or not isinstance(item, list) or len(item) != 2 or not isinstance(item[0], str) or not isinstance(item[1], (int, float)):
            raise ValueError("Unexpected dictionary row")
    history = json.loads(fetch(f"https://api.github.com/repos/zh-lx/pinyin-pro/commits?sha={revision}&path=packages/data/json/complete.json&per_page=1"))[0]
    current.update(revision=revision, data_commit=history["sha"], data_commit_date=history["commit"]["committer"]["date"])
    current["files"].update({name: hashlib.sha256(value).hexdigest() for name, value in payloads.items()})
    with tempfile.TemporaryDirectory(dir=DEST.parent) as staging:
        folder = pathlib.Path(staging)
        for name, value in payloads.items(): (folder / name).write_bytes(value)
        (folder / "SOURCE.json").write_text(json.dumps(current, indent=2) + "\n")
        for name in [*payloads, "SOURCE.json"]: (folder / name).replace(DEST / name)
    print("Source updated. Run scripts/build.sh, --smoke, and scripts/build-ios.sh before installing.")

if __name__ == "__main__": main()
