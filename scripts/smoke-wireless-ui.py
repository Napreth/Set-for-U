#!/usr/bin/env python3
"""Check app preferences, privilege-based routing and optional root wireless pages on an AVD."""
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
    parser.add_argument("--theme", action="append", choices=["material", "cupertino", "miuix", "coui"])
    parser.add_argument("--root", action="store_true", help="Require app-UID root authorization already prepared on this AVD")
    args = parser.parse_args()
    assert re.fullmatch(r"emulator-\d+", args.serial)
    prefix = [args.adb, "-s", args.serial]
    out = Path(__file__).resolve().parents[1] / "build/reports/wireless-ui" / args.serial
    out.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), timeout=40).decode("utf-8").strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1"

    def prefs(name):
        try:
            raw = adb("shell", "run-as", "com.napreth.set4u", "cat", f"shared_prefs/{name}.xml")
        except subprocess.CalledProcessError:
            raw = "<map/>"
        values = {n.get("name"): n.get("value", n.text) for n in ET.fromstring(raw)}
        return ({"theme": "cupertino", "themeMode": "system", "language": "system"} | values) if name == "appearance" else values

    original_appearance = prefs("appearance")
    original_mode = prefs("MainActivity")

    cache = None
    def tree(force=False):
        nonlocal cache
        if cache is None or force:
            for attempt in range(5):
                try:
                    response = adb("shell", "uiautomator", "dump", "/sdcard/set4u-wireless-ui.xml")
                    if "dumped to" not in response:
                        raise RuntimeError("Accessibility root not ready")
                    break
                except (subprocess.CalledProcessError, RuntimeError):
                    if attempt == 4:
                        raise
                    time.sleep(.5)
            cache = ET.fromstring(adb("shell", "cat", "/sdcard/set4u-wireless-ui.xml"))
        return cache

    def bounds(node):
        return [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]

    def visible(label):
        return any(label in (n.get("text"), n.get("content-desc")) for n in tree().iter("node"))

    def find(label, attempts=8):
        for attempt in range(attempts):
            nodes = [n for n in tree(force=attempt > 0).iter("node") if label in (n.get("text"), n.get("content-desc")) and len(bounds(n)) == 4]
            if nodes:
                return nodes[0]
        screenshot("failure")
        raise AssertionError(f"Missing {label}")

    def tap(label):
        nonlocal cache
        b = bounds(find(label))
        adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
        cache = None

    def back():
        nonlocal cache
        cache = None
        adb("shell", "input", "keyevent", "BACK")

    def screenshot(name):
        (out / f"{name}.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20))
        (out / f"{name}.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")

    def about():
        for _ in range(2):
            tap("关于选项卡" if visible("关于选项卡") else "About tab")

    def checkbox(label, enabled=None):
        nonlocal cache
        for attempt in range(8):
            nodes = [n for n in tree(force=attempt > 0).iter("node") if n.get("checkable") == "true" and n.get("content-desc") == label]
            if nodes:
                node = nodes[0]
                if enabled is not None and (node.get("checked") == "true") != enabled:
                    assert node.get("enabled") == "true", label
                    b = bounds(node); adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
                    cache = None
                return node
        raise AssertionError(f"Missing checkbox {label}")

    def debug():
        about(); tap("调试")

    def mode(root):
        tap("工作模式选项卡"); tap("工作模式选项卡")
        current = prefs("MainActivity").get("workMode", "shell") == "root"
        if current != root:
            tap("root" if current else "ADB（Shizuku）"); tap("root" if root else "ADB（Shizuku）")

    def network():
        tap("设置选项卡")
        # Clear any nested device page before opening the category.
        for _ in range(4):
            if visible("搜索设置"):
                break
            back()
        tap("网络与通信")

    def expect_system(label):
        tap(label)
        activity = adb("shell", "dumpsys", "activity", "activities")
        assert re.search(r"(?:topResumedActivity|mResumedActivity).*com\.android\.settings", activity)
        screenshot(f"system-{label}")
        back()

    styles = {"material": "Google Material", "cupertino": "Apple Cupertino", "miuix": "HyperOS Miuix", "coui": "ColorOS COUI"}
    before_bluetooth = None
    report = {"serial": args.serial, "rootPages": False}
    adb("shell", "input", "keyevent", "WAKEUP")
    adb("shell", "am", "force-stop", "com.napreth.set4u")
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", "com.napreth.set4u/.MainActivity")
    try:
        about(); tap("语言" if visible("关于选项卡") else "Language"); tap("简体中文")
        if args.root:
            mode(True); find("已启用")
            debug(); checkbox("以 root 模拟 shell 身份", False); checkbox("始终使用内置设置页", False)
            mode(True); find("已启用")
            network(); begin = time.monotonic(); tap("WLAN")
            # The system's connection SSID must reach the actual app page.
            status = adb("shell", "cmd", "wifi", "status")
            match = re.search(r"SSID: (.*?), BSSID:", status)
            if match:
                find(match.group(1).strip('"'))
            find("网络"); find("扫描")
            if match:
                ssid = match.group(1).strip('"')
                names = [n for n in tree().iter("node") if n.get("text") == ssid]
                assert len(names) == 1, "The current network must appear once on the WLAN overview"
                assert not visible("断开连接"), "Connection actions belong on the detail page"
                tap(f"网络详情：{ssid}"); find("自动连接"); find("断开连接"); find("忘记网络")
                screenshot("root-wifi-details"); back()
                tap("已保存网络"); find(ssid)
                tap(f"网络详情：{ssid}"); find("断开连接"); back(); find("已保存网络")
                back(); find("网络")
                report["wifiOverviewDeduplicated"] = True
                report["wifiDetailsAndSavedNavigation"] = True
            report["wifiPageSeconds"] = round(time.monotonic() - begin, 2)
            screenshot("root-wifi")
            back(); tap("蓝牙"); find("设备名称"); find("扫描")
            for attempt in range(8):
                node = checkbox("蓝牙")
                if node.get("enabled") == "true":
                    break
                cache = None
            assert node.get("enabled") == "true", "Bluetooth state must be readable and writable"
            before_bluetooth = node.get("checked") == "true"
            checkbox("蓝牙", not before_bluetooth)
            for _ in range(10):
                tree(force=True)
                changed = checkbox("蓝牙")
                if changed.get("enabled") == "true" and (changed.get("checked") == "true") != before_bluetooth:
                    break
            else:
                raise AssertionError("Bluetooth change did not pass readback")
            checkbox("蓝牙", before_bluetooth)
            for _ in range(10):
                tree(force=True)
                restored_bt = checkbox("蓝牙")
                if restored_bt.get("enabled") == "true" and (restored_bt.get("checked") == "true") == before_bluetooth:
                    break
            else:
                raise AssertionError("Bluetooth state was not restored")
            if not before_bluetooth:
                checkbox("蓝牙", True)
                for _ in range(10):
                    tree(force=True)
                    ready = checkbox("蓝牙")
                    if ready.get("enabled") == "true" and ready.get("checked") == "true":
                        break
                else:
                    raise AssertionError("Bluetooth could not be enabled for discovery")
            for _ in range(10):
                node = find("扫描")
                parents = {child: parent for parent in tree().iter("node") for child in parent}
                current = node
                disabled = False
                while current is not None:
                    disabled |= current.get("enabled") == "false"
                    current = parents.get(current)
                if not disabled:
                    break
                cache = None
            tap("扫描")
            find("正在扫描…")
            for _ in range(16):
                tree(force=True)
                if not visible("正在扫描…"):
                    break
            assert not visible("无线操作未完成")
            screenshot("root-bluetooth")
            back(); tap("热点和网络共享"); find("WLAN 热点"); find("配置热点")
            assert not visible("无线操作未完成")
            screenshot("root-hotspot")
            report["rootPages"] = True
        mode(False)
        debug()
        node = checkbox("以 root 模拟 shell 身份")
        assert node.get("enabled") == "false"
        checkbox("始终使用内置设置页", False)
        screenshot("debug-disabled-shell-identity")
        if args.root:
            mode(True); debug(); checkbox("以 root 模拟 shell 身份", True)
            mode(True)
        network()
        expect_system("WLAN"); expect_system("蓝牙"); expect_system("热点和网络共享")
        debug(); checkbox("始终使用内置设置页", True)
        network(); tap("WLAN"); find("启用 WLAN"); find("此页面的无线管理功能需要 root 权限")
        screenshot("forced-own-page")
        report["systemFallback"] = True; report["debugOverride"] = True
        for style, title in styles.items():
            if args.theme and style not in args.theme:
                continue
            about(); tap("外观"); tap("界面风格"); tap(title); find(title)
            screenshot(f"{style}-appearance")
            back(); tap("调试"); find("非 root 模式下，部分功能可能不可用")
            screenshot(f"{style}-debug")
        report["styles"] = args.theme or list(styles)
    finally:
        if before_bluetooth is not None:
            adb("shell", "cmd", "bluetooth_manager", "enable" if before_bluetooth else "disable")
            for _ in range(30):
                status = adb("shell", "dumpsys", "bluetooth_manager")
                if re.search(r"(?im)^\s*state:\s*" + ("ON" if before_bluetooth else "(?:OFF|BLE_ON)") + r"\s*$", status):
                    break
                time.sleep(.2)
            else:
                raise AssertionError("Bluetooth state could not be restored")
        # Restore through the same UI paths used to change the preferences.
        adb("shell", "am", "force-stop", "com.napreth.set4u")
        adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", "com.napreth.set4u/.MainActivity")
        cache = None
        debug(); checkbox("始终使用内置设置页", original_mode.get("ownPages", "false") == "true")
        if prefs("MainActivity").get("rootShell", "false") != original_mode.get("rootShell", "false"):
            mode(True); debug(); checkbox("以 root 模拟 shell 身份", original_mode.get("rootShell", "false") == "true")
        mode(original_mode.get("workMode", "shell") == "root")
        about(); tap("外观"); tap("界面风格"); tap(styles[original_appearance.get("theme", "cupertino")])
        tap("主题"); tap({"system": "跟随系统", "light": "浅色", "dark": "深色"}[original_appearance.get("themeMode", "system")])
        back(); tap("语言"); tap({"system": "跟随系统", "zh-CN": "简体中文", "en": "English"}[original_appearance.get("language", "system")])
        for _ in range(12):
            if prefs("appearance") == original_appearance:
                break
            time.sleep(.15)
        assert prefs("appearance") == original_appearance
        restored = prefs("MainActivity")
        for key in ["workMode", "rootShell", "ownPages"]:
            assert restored.get(key, "shell" if key == "workMode" else "false") == original_mode.get(key, "shell" if key == "workMode" else "false")
    report["preferencesRestored"] = True
    (out / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
