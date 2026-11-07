#!/usr/bin/env python3
"""Publish a built extension APK into this repo and rewrite repo/index.min.json.

Usage: scripts/update_index.py path/to/tachiyomi-ru.remanga-v1.4.7-release.apk

Reads package, version and label from the APK with aapt2, copies it to
repo/apk/tachiyomi-<suffix>-v<version>.apk (removing older APKs of the same
extension), copies the launcher icon to repo/icon/<pkg>.png, and writes
repo/index.min.json. Tachimanga resolves "apk" as <repo>/apk/<apk> and icons as
<repo>/icon/<pkg>.png, where <repo> is the folder holding index.min.json.
"""

import glob
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Per-extension data that is not stored in the APK. The source id is fixed so
# existing libraries keep working.
EXTENSIONS = {
    "eu.kanade.tachiyomi.extension.ru.remanga": {
        "module": "src/ru/remanga",
        "sources": [
            {
                "name": "ReManga",
                "lang": "ru",
                "id": "7584815203666942529",
                "baseUrl": "https://remanga.org",
            }
        ],
    },
}


def find_aapt2():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
        or os.path.expanduser("~/Library/Android/sdk")
    found = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    if not found:
        sys.exit(f"aapt2 not found under {sdk}/build-tools")
    return found[-1]


def badging(apk):
    out = subprocess.run([find_aapt2(), "dump", "badging", apk],
                         check=True, capture_output=True, text=True).stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    label = re.search(r"application-label:'([^']*)'", out)
    nsfw = re.search(r"meta-data: name='tachiyomi.extension.nsfw' value='(\d)'", out)
    if not pkg or not label:
        sys.exit("Could not read package info from APK")
    return {
        "pkg": pkg.group(1),
        "code": int(pkg.group(2)),
        "version": pkg.group(3),
        "name": label.group(1).removeprefix("Tachiyomi: "),
        "nsfw": int(nsfw.group(1)) if nsfw else 0,
    }


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    info = badging(apk)
    meta = EXTENSIONS.get(info["pkg"])
    if meta is None:
        sys.exit(f"Unknown package {info['pkg']}; add it to EXTENSIONS")

    suffix = info["pkg"].removeprefix("eu.kanade.tachiyomi.extension.")
    lang = suffix.split(".")[0]
    apk_name = f"tachiyomi-{suffix}-v{info['version']}.apk"

    index_path = os.path.join(ROOT, "repo", "index.min.json")
    index = []
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            index = json.load(f)
    old = next((e for e in index if e["pkg"] == info["pkg"]), None)
    if old and old["code"] >= info["code"]:
        sys.exit(f"versionCode {info['code']} is not above the published {old['code']}; bump extVersionCode")

    apk_dir = os.path.join(ROOT, "repo", "apk")
    os.makedirs(apk_dir, exist_ok=True)
    for stale in glob.glob(os.path.join(apk_dir, f"*{suffix}-v*.apk")):
        os.remove(stale)
    shutil.copyfile(apk, os.path.join(apk_dir, apk_name))

    icon_src = os.path.join(ROOT, meta["module"], "res", "mipmap-xxxhdpi", "ic_launcher.png")
    if os.path.exists(icon_src):
        icon_dir = os.path.join(ROOT, "repo", "icon")
        os.makedirs(icon_dir, exist_ok=True)
        shutil.copyfile(icon_src, os.path.join(icon_dir, f"{info['pkg']}.png"))

    entry = {
        "name": info["name"],
        "pkg": info["pkg"],
        "apk": apk_name,
        "lang": lang,
        "code": info["code"],
        "version": info["version"],
        "nsfw": info["nsfw"],
        "sources": meta["sources"],
    }
    index = [e for e in index if e["pkg"] != info["pkg"]] + [entry]
    index.sort(key=lambda e: e["pkg"])
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"Published {info['name']} {info['version']} (code {info['code']}) as repo/apk/{apk_name}")


if __name__ == "__main__":
    main()
