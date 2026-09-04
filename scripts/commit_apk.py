#!/usr/bin/env python3
"""Commit built APK files to a dedicated branch via GitHub Git Data API,
bypassing Actions artifact/log blob storage (which may be network-blocked
for some consumers). Files become fetchable via raw.githubusercontent.com.
"""
import subprocess
import json
import base64
import os

REPO = "miltadentu/wifistatic-mod"
BRANCH = "ci-apk"
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
    main_ref = gh("GET", f"{API}/repos/{REPO}/git/ref/heads/main")
    main_sha = main_ref["object"]["sha"]

    # Ensure ci-apk branch exists (ignore error if it already does)
    gh("POST", f"{API}/repos/{REPO}/git/refs",
       {"ref": f"refs/heads/{BRANCH}", "sha": main_sha})

    candidates = [
        ("debug", "app/build/outputs/apk/debug/app-debug.apk"),
        ("release", "app/build/outputs/apk/release/app-release-unsigned.apk"),
    ]
    files_to_commit = [(label, path) for label, path in candidates if os.path.exists(path)]

    if not files_to_commit:
        print("No APK files found to commit")
        return

    tree_items = []
    for label, path in files_to_commit:
        with open(path, "rb") as f:
            content_b64 = base64.b64encode(f.read()).decode()
        blob = gh("POST", f"{API}/repos/{REPO}/git/blobs",
                  {"content": content_b64, "encoding": "base64"})
        sha = blob.get("sha")
        if not sha:
            print(f"Failed to create blob for {label}: {blob}")
            continue
        tree_items.append({
            "path": f"WiFiStaticMod-{label}.apk",
            "mode": "100644",
            "type": "blob",
            "sha": sha,
        })
        print(f"Uploaded blob for {label}: {sha}")

    base_ref = gh("GET", f"{API}/repos/{REPO}/git/ref/heads/{BRANCH}")
    base_sha = base_ref["object"]["sha"]
    base_commit = gh("GET", f"{API}/repos/{REPO}/git/commits/{base_sha}")
    base_tree_sha = base_commit["tree"]["sha"]

    new_tree = gh("POST", f"{API}/repos/{REPO}/git/trees",
                  {"base_tree": base_tree_sha, "tree": tree_items})
    new_commit = gh("POST", f"{API}/repos/{REPO}/git/commits", {
        "message": f"CI APK build run {os.environ.get('GITHUB_RUN_ID', '')}",
        "tree": new_tree["sha"],
        "parents": [base_sha],
    })
    update = gh("PATCH", f"{API}/repos/{REPO}/git/refs/heads/{BRANCH}",
                {"sha": new_commit["sha"], "force": True})
    print("Branch updated:", update.get("ref", update))


if __name__ == "__main__":
    main()
