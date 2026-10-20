#!/usr/bin/env python3
"""Check translated navigation search and real system navigation on an explicit AVD."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("An explicit emulator serial is required")
    prefix = [args.adb, "-s", args.serial]
    out = Path(__file__).resolve().parents[1] / "build/reports/search-ui" / args.serial
    out.mkdir(parents=True, exist_ok=True)
    cache = None

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), timeout=45).decode("utf-8").strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1"

    def preferences(name):
        return {n.get("name"): n.get("value", n.text) for n in ET.fromstring(adb("shell", "run-as", "com.napreth.set4u", "cat", f"shared_prefs/{name}.xml"))}

    original = preferences("appearance")

    def tree(force=False):
        nonlocal cache
        if cache is None or force:
            adb("shell", "uiautomator", "dump", "/sdcard/set4u-search-ui.xml")
            cache = ET.fromstring(adb("shell", "cat", "/sdcard/set4u-search-ui.xml"))
        return cache

    def bounds(node):
        return [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]

    def visible(label):
        return any(label in (n.get("text"), n.get("content-desc")) for n in tree().iter("node"))

    def find(*labels, last=False):
        for attempt in range(6):
            matches = [n for n in tree(force=attempt > 0).iter("node")
                       if any(label in (n.get("text"), n.get("content-desc")) for label in labels) and len(bounds(n)) == 4]
            if matches:
                return max(matches, key=lambda n: bounds(n)[1]) if last else matches[0]
        (out / "failure.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")
        (out / "failure.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20))
        raise AssertionError(f"Missing UI element: {labels}")

    def tap(*labels, last=False):
        nonlocal cache
        b = bounds(find(*labels, last=last))
        adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
        cache = None

    def back():
        nonlocal cache
        adb("shell", "input", "keyevent", "BACK")
        cache = None

    def screenshot(name):
        (out / f"{name}.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20))
        (out / f"{name}.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")

    def query(text):
        nonlocal cache
        tap("搜索设置")
        adb("shell", "input", "keyevent", "123")
        adb("shell", "input", "keyevent", *(["67"] * 32))
        if text:
            adb("shell", "input", "text", text)
            # A Chinese IME may retain injected Latin keys as an uncommitted candidate.
            adb("shell", "input", "keyevent", "62")
        ime = adb("shell", "dumpsys", "input_method")
        if re.search(r"\bm(?:InputShown|IsInputViewShown)=true", ime):
            back()
        cache = None

    styles = {"cupertino": "Apple Cupertino", "material": "Google Material", "miuix": "HyperOS Miuix", "coui": "ColorOS COUI"}
    languages = {"system": "跟随系统", "zh-CN": "简体中文", "en": "English"}
    changed_language = False
    results = []
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", "com.napreth.set4u/.MainActivity")
    try:
        if not visible("关于选项卡"):
            tap("About tab"); tap("Language"); tap("简体中文")
            changed_language = True
        for style, title in styles.items():
            tap("关于选项卡"); tap("关于选项卡"); tap("外观"); tap("界面风格"); tap(title); find(title)
            tap("设置选项卡"); find("搜索设置")
            query("hotspot"); find("热点和网络共享")
            screenshot(f"{style}-translated-navigation-search")
            query("")
            find("网络与通信")
            assert not visible("刷新")
            results.append({"theme": style, "translatedNavigationSearch": True, "clearingRestoresHome": True})
            print(f"{title}: translated navigation search OK", flush=True)
        query("certificate"); find("安装证书")
        query("VPN"); find("VPN", last=True); tap("VPN", last=True)
        for attempt in range(8):
            focus = adb("shell", "dumpsys", "activity", "activities")
            if re.search(r"(?:topResumedActivity|mResumedActivity).*com\.android\.settings", focus):
                break
            time.sleep(.25)
        else:
            raise AssertionError("VPN result did not open system settings")
        screenshot("vpn-system-page")
        back(); find("搜索设置"); find("网络与通信")
    finally:
        # Reopen after an assertion failure to dismiss menus or the keyboard.
        adb("shell", "am", "force-stop", "com.napreth.set4u")
        adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", "com.napreth.set4u/.MainActivity")
        cache = None
        tap("关于选项卡"); tap("关于选项卡"); tap("外观"); tap("界面风格"); tap(styles[original.get("theme", "cupertino")]); find(styles[original.get("theme", "cupertino")])
        if changed_language:
            back(); tap("语言"); tap(languages[original.get("language", "system")])
        assert preferences("appearance") == original
    summary = {"serial": args.serial, "api": adb("shell", "getprop", "ro.build.version.sdk"), "themes": results,
               "certificateNavigationIndexed": True, "vpnSystemNavigation": True, "appearanceRestored": True}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
