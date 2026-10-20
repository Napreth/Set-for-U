#!/usr/bin/env python3
"""Verify theme persistence, navigation and device readback on an explicit test AVD."""
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
    output = Path(__file__).resolve().parents[1] / "build/reports/themes-ui"
    output.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), timeout=45).decode("utf-8").strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1"

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/set4u-theme-ui.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/set4u-theme-ui.xml"))

    def find(text, attempts=8):
        for _ in range(attempts):
            for node in tree().iter("node"):
                if node.get("text") == text or node.get("content-desc") == text:
                    bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
                    if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                        return node, bounds
            time.sleep(.3)
        raise AssertionError(f"UI element not visible: {text}")

    def tap(text):
        _, b = find(text)
        adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))

    def back():
        adb("shell", "input", "keyevent", "BACK")

    def screenshot(name):
        (output / f"{name}.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20))
        (output / f"{name}.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")

    def maybe(*labels):
        nodes = list(tree().iter("node"))
        for label in labels:
            if any(n.get("text") == label or n.get("content-desc") == label for n in nodes):
                return label
        return None

    def tap_either(*labels):
        label = maybe(*labels)
        assert label, f"Missing one of {labels}"
        tap(label)

    def scroll_to(text):
        for _ in range(4):
            if maybe(text):
                return
            size = [int(n) for n in re.findall(r"[0-9]+", adb("shell", "wm", "size"))][-2:]
            w, h = size
            adb("shell", "input", "swipe", str(w//2), str(h*3//4), str(w//2), str(h//3), "350")
        find(text)

    def start():
        adb("shell", "am", "force-stop", "com.napreth.set4u")
        adb("shell", "am", "start", "-W", "-n", "com.napreth.set4u/.MainActivity")
        for _ in range(8):
            if maybe("网络与通信", "Network & communication"):
                break
            time.sleep(.3)
        assert not maybe("外观", "界面风格", "Interface style"), "App preferences leaked into device settings"

    def about():
        tap_either("关于选项卡", "About tab")
        tap_either("关于选项卡", "About tab")

    def chinese():
        if maybe("Language"):
            tap("Language")
            tap("简体中文")
        else:
            scroll_to("语言")
            tap("语言")
            tap("简体中文")
        find("外观")

    def connect():
        tap("工作模式选项卡")
        try:
            find("已启用", attempts=2)
        except AssertionError:
            tap_either("获取权限", "重试")
            find("已启用")
        tap("设置选项卡")

    def wifi():
        result = adb("shell", "cmd", "wifi", "status")
        if "Wifi is enabled" in result.splitlines():
            return True
        assert "Wifi is disabled" in result.splitlines()
        return False

    def switch(expected=None):
        for _ in range(12):
            for node in tree().iter("node"):
                if node.get("checkable") == "true" and node.get("content-desc") == "启用 WLAN" and node.get("enabled") == "true":
                    if expected is None or node.get("checked") == str(expected).lower():
                        return node
            time.sleep(.3)
        raise AssertionError(f"No writable, verified WLAN switch: {expected}")

    def toggle(expected):
        node = switch()
        b = [int(n) for n in re.findall(r"\d+", node.get("bounds"))]
        adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
        switch(expected)
        assert wifi() == expected

    try:
        mode_xml = ET.fromstring(adb("shell", "run-as", "com.napreth.set4u", "cat", "shared_prefs/MainActivity.xml"))
    except subprocess.CalledProcessError:
        mode_xml = ET.fromstring("<map/>")
    own_before = next((n.get("value") == "true" for n in mode_xml if n.get("name") == "ownPages"), False)
    def own_pages(enabled):
        about(); tap("调试")
        node = next(n for n in tree().iter("node") if n.get("checkable") == "true" and n.get("content-desc") == "始终使用内置设置页")
        if (node.get("checked") == "true") != enabled:
            b = [int(n) for n in re.findall(r"\d+", node.get("bounds"))]
            adb("shell", "input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))
        back()

    before = wifi()
    results = []
    try:
        start()
        about()
        chinese()
        own_pages(True)
        tap("设置选项卡")
        connect()
        for theme_id, title in [("coui", "ColorOS COUI"), ("material", "Google Material"), ("miuix", "HyperOS Miuix"), ("cupertino", "Apple Cupertino")]:
            about()
            tap("外观")
            process = adb("shell", "pidof", "com.napreth.set4u")
            tap("界面风格")
            screenshot(f"{theme_id}-dropdown-before")
            tap(title)
            find(title)
            assert adb("shell", "pidof", "com.napreth.set4u") == process, "Style change recreated the process"
            tap("主题")
            tap("深色")
            find("深色")
            screenshot(f"{theme_id}-dark")
            tap("主题")
            tap("浅色")
            find("浅色")
            screenshot(f"{theme_id}-light")
            back()
            tap("语言")
            tap("English")
            find("Appearance")
            tap("Settings tab")
            tap("Network & communication")
            find("Bluetooth")
            screenshot(f"{theme_id}-english-network")
            about()
            chinese()
            preferences = adb("shell", "run-as", "com.napreth.set4u", "cat", "shared_prefs/appearance.xml")
            assert f'>{theme_id}<' in preferences and '>light<' in preferences and '>zh-CN<' in preferences
            tap("设置选项卡")
            find("蓝牙")
            time.sleep(2)
            assert not any(n.get("text") == "NFC" for n in tree().iter("node"))
            tap("WLAN")
            switch(before)
            toggle(not before)
            toggle(before)
            screenshot(f"{theme_id}-wifi")
            about()
            scroll_to("许可证")
            tap("许可证")
            find("MIT License")
            screenshot(f"{theme_id}-license")
            tap("关闭")
            start()
            about()
            find("简体中文")
            tap("外观")
            find(title)
            find("浅色")
            screenshot(f"{theme_id}-restart")
            tap("设置选项卡")
            results.append({"theme": theme_id, "saved": True, "restart": True, "darkAndLight": True, "appAndCoreLanguage": True, "wifiReadbackRoundtrip": True, "nfcHidden": True, "licenseDialog": True})
            print(f"{theme_id}: OK", flush=True)
        about()
        tap("外观")
        tap("主题")
        tap("跟随系统")
        original_night = adb("shell", "cmd", "uimode", "night")
        night = re.search(r"Night mode: (\w+)", original_night).group(1)
        try:
            for value in ["yes", "no"]:
                adb("shell", "cmd", "uimode", "night", value)
                find("界面风格")
                find("跟随系统")
                screenshot(f"system-theme-{value}")
        finally:
            adb("shell", "cmd", "uimode", "night", night)
        tap("设置选项卡")
        summary = {"device": args.serial, "wifiBefore": before, "wifiAfter": wifi(), "themes": results}
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False))
    finally:
        about()
        chinese()
        own_pages(own_before)
        if wifi() != before:
            adb("shell", "svc", "wifi", "enable" if before else "disable")


if __name__ == "__main__":
    main()
