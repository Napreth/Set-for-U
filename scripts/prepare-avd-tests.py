#!/usr/bin/env python3
"""Fetch the pinned official Shizuku test dependency into the build cache."""
from build import download

download({
    "file": "shizuku-v13.6.0.r1086.2650830c-release.apk",
    "url": "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk",
    "sha256": "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f",
}, offline=False)
