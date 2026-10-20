#!/usr/bin/env python3
"""Exercise the Android network page on an explicitly selected, prepared AVD."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--radios", action="store_true", help="Also test Bluetooth, NFC visibility and mobile data when a default SIM is available")
    parser.add_argument("--modes", action="store_true", help="Verify mode isolation and denied local root on the stock userdebug AVD")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("An explicit emulator serial is required")
    prefix = [args.adb, "-s", args.serial]
    output = Path(__file__).resolve().parents[1] / "build/reports/network-ui"
    output.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), timeout=40).decode("utf-8").strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Tests require an AVD"

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/set4u-network-ui.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/set4u-network-ui.xml"))

    def find(text, attempts=8):
        for _ in range(attempts):
            root = tree()
            for node in root.iter("node"):
                if node.get("text", "").casefold() == text.casefold() or node.get("content-desc", "").casefold() == text.casefold():
                    bounds = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
                    if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                        return node, bounds
            time.sleep(0.3)
        raise AssertionError(f"UI element not visible: {text}")

    def tap(text):
        _, bounds = find(text)
        adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))

    def screenshot(name):
        (output / f"{name}.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=15))
        (output / f"{name}.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")

    def scroll_to(text):
        for _ in range(6):
            try:
                return find(text, attempts=1)
            except AssertionError:
                root = tree()
                bounds = [int(value) for value in re.findall(r"\d+", root.find("node").get("bounds"))]
                width, height = bounds[2], bounds[3]
                adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), "350")
        raise AssertionError(f"Unable to scroll to {text}")

    def radio_switch(name, writable=True):
        for _ in range(8):
            for node in tree().iter("node"):
                if node.get("checkable") == "true" and node.get("content-desc") == name:
                    if not writable or node.get("enabled") == "true":
                        return node
            time.sleep(0.3)
        raise AssertionError(f"Radio switch is not writable: {name}")

    def change_radio(name, desired):
        node = radio_switch(name)
        assert node.get("checked") != str(desired).lower()
        bounds = [int(value) for value in re.findall(r"\d+", node.get("bounds"))]
        adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            root = tree()
            for node in root.iter("node"):
                if node.get("checkable") == "true" and node.get("content-desc") == name:
                    if node.get("enabled") == "true" and node.get("checked") == str(desired).lower():
                        # The switch stays disabled until the engine has completed verification.
                        return
            time.sleep(0.3)
        raise AssertionError(f"No completed readback for {name}")

    def wifi():
        status = adb("shell", "cmd", "wifi", "status")
        if "Wifi is enabled" in status.splitlines():
            return True
        assert "Wifi is disabled" in status.splitlines(), "Unknown WLAN status"
        return False

    def wait_wifi(expected):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if wifi() == expected:
                return
            time.sleep(0.25)
        raise AssertionError("WLAN state did not match the UI request")

    try:
        original_mode_xml = ET.fromstring(adb("shell", "run-as", "com.napreth.set4u", "cat", "shared_prefs/MainActivity.xml"))
    except subprocess.CalledProcessError:
        original_mode_xml = ET.fromstring("<map/>")
    own_before = next((n.get("value") == "true" for n in original_mode_xml if n.get("name") == "ownPages"), False)
    def own_pages(enabled):
        tap("关于选项卡"); tap("关于选项卡"); tap("调试")
        node = next(n for n in tree().iter("node") if n.get("checkable") == "true" and n.get("content-desc") == "始终使用内置设置页")
        if (node.get("checked") == "true") != enabled:
            b = [int(n) for n in re.findall(r"\d+", node.get("bounds"))]
            adb("shell", "input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))
        adb("shell", "input", "keyevent", "BACK")
    before = wifi()
    bluetooth_before = None
    mobile_before = None
    radio_results = {}
    try:
        adb("shell", "am", "force-stop", "com.napreth.set4u")
        adb("shell", "am", "start", "-W", "-n", "com.napreth.set4u/.MainActivity")
        # Fresh installs follow the host language; choose Chinese through the app UI for these fixtures.
        if any(n.get("content-desc") == "About tab" for n in tree().iter("node")):
            tap("About tab")
            scroll_to("Language")
            tap("Language")
            tap("简体中文")
        own_pages(True)
        tap("工作模式选项卡")
        try:
            find("已启用", attempts=2)
        except AssertionError:
            tap("重试" if any(n.get("text") == "重试" for n in tree().iter("node")) else "获取权限")
        find("已启用")
        screenshot("shizuku")
        if args.modes:
            # AOSP su is shell-only on this AVD; an app must not borrow shell's root grant.
            tap("ADB（Shizuku）")
            tap("root")
            find("未获得 root 权限")
            find("重试")
            assert not any(n.get("text") == "好" for n in tree().iter("node"))
            tap("设置选项卡")
            tap("网络与通信")
            find("WLAN")
            assert not any(n.get("checkable") == "true" and n.get("enabled") == "true" for n in tree().iter("node"))
            screenshot("root-denied")
            tap("工作模式选项卡")
            tap("root")
            tap("ADB（Shizuku）")
            find("已启用")
            tap("设置选项卡")
            adb("shell", "input", "keyevent", "BACK")
            tap("工作模式选项卡")
        tap("关于选项卡")
        scroll_to("0.1.0-debug")
        scroll_to("软件更新")
        scroll_to("源代码")
        scroll_to("MIT License · 2026 · Napreth")
        screenshot("about")
        tap("许可证")
        find("MIT License")
        find("关闭")
        tap("关闭")
        tap("设置选项卡")
        screenshot("home")
        tap("网络与通信")
        find("WLAN")
        for node in tree().iter("node"):
            text = node.get("text", "")
            assert "刷新" not in text and "回读验证" not in text and "Command exited" not in text
            assert text != "设备信息" and "默认数据 SIM 卡与 eSIM" not in text
        screenshot("network")
        # Tab changes preserve the settings navigation stack.
        tap("关于选项卡")
        tap("设置选项卡")
        find("网络与通信")
        tap("WLAN")
        node, _ = find("启用 WLAN")
        # The switch's content description equals its setting title.
        switches = [node for node in tree().iter("node") if node.get("checkable") == "true" and node.get("content-desc") == "启用 WLAN"]
        assert len(switches) == 1 and switches[0].get("enabled") == "true"
        assert switches[0].get("checked") == str(before).lower()
        bounds = [int(value) for value in re.findall(r"\d+", switches[0].get("bounds"))]
        adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        wait_wifi(not before)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            current = radio_switch("启用 WLAN", writable=False)
            if current.get("enabled") == "true" and current.get("checked") == str(not before).lower():
                break
            time.sleep(.3)
        else:
            raise AssertionError("WLAN verification did not complete")
        screenshot("wlan")
        adb("shell", "input", "keyevent", "BACK")
        if args.radios:
            scroll_to("蓝牙")
            tap("蓝牙")
            bluetooth_before = radio_switch("蓝牙").get("checked") == "true"
            adb("shell", "input", "keyevent", "BACK")
            features = adb("shell", "pm", "list", "features").splitlines()
            assert "feature:android.hardware.nfc" not in features, "This UI test expects an AVD without NFC"
            assert not any(node.get("text") == "NFC" or node.get("content-desc") == "NFC" for node in tree().iter("node"))
            tap("蓝牙")
            change_radio("蓝牙", not bluetooth_before)
            change_radio("蓝牙", bluetooth_before)
            screenshot("bluetooth")
            adb("shell", "input", "keyevent", "BACK")
            tap("网络与通信")
            tap("移动数据")
            mobile_writable = False
            for _ in range(10):
                nodes = list(tree().iter("node"))
                toggle = next((n for n in nodes if n.get("checkable") == "true" and n.get("content-desc") == "移动数据" and n.get("enabled") == "true"), None)
                if toggle is not None:
                    mobile_writable = True
                    break
                if any(n.get("text") == "不可用" for n in nodes):
                    break
                time.sleep(.3)
            if mobile_writable:
                mobile_before = toggle.get("checked") == "true"
                change_radio("移动数据", not mobile_before)
                change_radio("移动数据", mobile_before)
            else:
                # Unknown must not be rendered as an unchecked switch. Details remain available on demand.
                assert not any(n.get("checkable") == "true" and n.get("content-desc") == "移动数据" for n in tree().iter("node"))
                tap("移动数据")
                find("没有可用的数据 SIM 卡。")
                tap("好")
            screenshot("mobile-data")
            radio_results = {"bluetoothBefore": bluetooth_before, "mobileDataBefore": mobile_before,
                             "bluetoothRestored": True, "mobileDataWriteRoundTrip": mobile_writable,
                             "mobileDataRestored": True if mobile_writable else None, "noSimGuard": not mobile_writable, "nfcHidden": True}
            adb("shell", "input", "keyevent", "BACK")
        # Scroll the settings list until the third group is visible.
        scroll_to("私人 DNS")
        tap("私人 DNS")
        find("提供商主机名")
        find("指定提供商")
        screenshot("private-dns")
    finally:
        own_pages(own_before)
        adb("shell", "svc", "wifi", "enable" if before else "disable")
        wait_wifi(before)
        if bluetooth_before is not None:
            adb("shell", "cmd", "bluetooth_manager", "enable" if bluetooth_before else "disable")
        if mobile_before is not None:
            adb("shell", "svc", "data", "enable" if mobile_before else "disable")
        adb("shell", "rm", "-f", "/sdcard/set4u-network-ui.xml")
    report = {"serial": args.serial, "wifiBefore": before, "wifiAfter": wifi(), "tabs": ["设置", "工作模式", "关于"], "noRefreshButton": True, "settingsStackPreserved": True, "modeSwitchChecked": args.modes, "ungrantedRootDenied": True if args.modes else None,
              "screens": ["home", "network", "wlan", "private-dns", "shizuku", "about"] + (["bluetooth", "mobile-data"] if args.radios else []), **radio_results}
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
