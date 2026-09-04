#!/usr/bin/env python3
"""Upload a trimmed build log to a dedicated branch via GitHub Contents API
so it can be inspected without needing Actions log/blob-storage access."""
import subprocess
import json
import base64
import os
import sys

REPO = "miltadentu/wifistatic-mod"
BRANCH = "ci-logs"
TOKEN = os.environ["GH_TOKEN"]
API = "https://api.github.com"


def gh(method, url, data=None):
    cmd = [
        "curl", "-s", "-X", method,
        "-H", f"Authorization: token {TOKEN}",
        "-H", "Content-Type: application/json",
    ]
    if data is not None:
        cmd += ["-d", json.dumps(data)]
    cmd += [url]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    return json.loads(out) if out else {}


def main():
    log_path = sys.argv[1]
    target_name = sys.argv[2] if len(sys.argv) > 2 else os.path.basename(log_path)

    with open(log_path, "rb") as f:
        raw = f.read()[-60000:]
    content_b64 = base64.b64encode(raw).decode()

    main_ref = gh("GET", f"{API}/repos/{REPO}/git/ref/heads/main")
    main_sha = main_ref["object"]["sha"]
    gh("POST", f"{API}/repos/{REPO}/git/refs",
       {"ref": f"refs/heads/{BRANCH}", "sha": main_sha})

    existing = gh("GET", f"{API}/repos/{REPO}/contents/{target_name}?ref={BRANCH}")
    existing_sha = existing.get("sha")

    payload = {
        "message": f"CI log run {os.environ.get('GITHUB_RUN_ID', '')}",
        "content": content_b64,
        "branch": BRANCH,
    }
    if existing_sha:
        payload["sha"] = existing_sha

    result = gh("PUT", f"{API}/repos/{REPO}/contents/{target_name}", payload)
    print(json.dumps(result.get("content", result), indent=2)[:500])


if __name__ == "__main__":
    main()
