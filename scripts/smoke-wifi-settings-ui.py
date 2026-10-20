#!/usr/bin/env python3
"""Check the root WLAN pages on an explicitly selected, already authorized AVD."""
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
    parser.add_argument("--theme", action="append", choices=["cupertino", "material", "miuix", "coui"])
    parser.add_argument("--read-only", action="store_true", help="Check layout and editors without changing network preferences")
    args = parser.parse_args()
    assert re.fullmatch(r"emulator-\d+", args.serial)
    prefix = [args.adb, "-s", args.serial]
    out = Path(__file__).resolve().parents[1] / "build/reports/wifi-settings-ui" / args.serial
    out.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), timeout=40).decode("utf-8").strip()

    def prefs(name):
        return {n.get("name"): n.get("value", n.text) for n in ET.fromstring(adb("shell", "run-as", "com.napreth.set4u", "cat", f"shared_prefs/{name}.xml"))}

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1"
    original = prefs("appearance")
    mode = prefs("MainActivity")
    assert mode.get("workMode") == "root" and mode.get("rootShell", "false") == "false", "Prepare app root authorization first"
    cache = None

    def tree(force=False):
        nonlocal cache
        if cache is None or force:
            for attempt in range(6):
                result = adb("shell", "uiautomator", "dump", "/sdcard/set4u-wifi-settings-ui.xml")
                if "dumped to" in result:
                    cache = ET.fromstring(adb("shell", "cat", "/sdcard/set4u-wifi-settings-ui.xml"))
                    break
                time.sleep(.4)
            else:
                raise AssertionError("Accessibility root not ready")
        return cache

    def nodes(label):
        def visible(n):
            b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
            return len(b) == 4 and b[2] > b[0] and b[3] > b[1]
        return [n for n in tree().iter("node") if label in (n.get("text"), n.get("content-desc")) and visible(n)]

    def find(label):
        for attempt in range(10):
            if attempt:
                tree(True)
            matches = nodes(label)
            if matches:
                # Library dialogs retain the page below them in the accessibility tree.
                return matches[-1]
        raise AssertionError(f"Missing {label}")

    def tap_node(node):
        nonlocal cache
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        cache = None

    def tap(label):
        tap_node(find(label))

    def back():
        nonlocal cache
        adb("shell", "input", "keyevent", "BACK")
        cache = None

    def back_to_overview():
        # A library dialog may consume Back while its closing animation is finishing.
        for _ in range(4):
            tree(True)
            if nodes("启用 WLAN"):
                return
            back()
        raise AssertionError("Did not return to WLAN overview")

    def scroll_to(label, up=False):
        nonlocal cache
        for _ in range(7):
            if nodes(label):
                return
            adb("shell", "input", "swipe", "530", "650" if up else "1850", "530", "1850" if up else "650", "350")
            cache = None
        find(label)

    def capture(name):
        (out / f"{name}.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20))
        (out / f"{name}.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")

    def switch(label):
        for attempt in range(10):
            if attempt:
                tree(True)
            matches = [n for n in nodes(label) if n.get("checkable") == "true"]
            if matches and matches[0].get("enabled") == "true":
                return matches[0]
        raise AssertionError(f"Switch is not ready: {label}")

    def set_switch(label, desired, check_error=True):
        node = switch(label)
        if (node.get("checked") == "true") != desired:
            tap_node(node)
        for _ in range(12):
            tree(True)
            node = switch(label)
            if (node.get("checked") == "true") == desired:
                if check_error:
                    assert not nodes("无线操作未完成")
                return
        raise AssertionError(f"Change not verified: {label}")

    styles = {"cupertino": "Apple Cupertino", "material": "Google Material", "miuix": "HyperOS Miuix", "coui": "ColorOS COUI"}
    report = {"serial": args.serial, "styles": [], "verifiedUiChanges": []}
    changed = {}
    ssid = None
    adb("shell", "input", "keyevent", "WAKEUP")
    adb("shell", "am", "force-stop", "com.napreth.set4u")
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", "com.napreth.set4u/.MainActivity")
    try:
        tap("关于选项卡" if nodes("关于选项卡") else "About tab")
        tap("语言" if nodes("语言") else "Language"); tap("简体中文")
        tap("工作模式选项卡")
        retried = False
        for _ in range(12):
            tree(True)
            if nodes("已启用"):
                break
            if nodes("重试") and not retried:
                tap("重试"); retried = True
        else:
            raise AssertionError("App root authorization did not become ready")
        tap("设置选项卡"); tap("网络与通信"); tap("WLAN")
        for style, title in styles.items():
            if args.theme and style not in args.theme:
                continue
            tap("关于选项卡"); tap("关于选项卡"); tap("外观"); tap("界面风格"); tap(title)
            tap("设置选项卡")
            assert switch("启用 WLAN").get("checked") == "true", "WLAN radio state did not load"
            find("扫描")
            ssid = re.search(r"SSID: (.*?), BSSID:", adb("shell", "cmd", "wifi", "status")).group(1).strip('"')
            find(ssid)
            assert len([n for n in tree().iter("node") if n.get("text") == ssid]) == 1
            assert not nodes("其他网络") and not nodes("允许连接 WEP 网络")
            capture(f"{style}-overview")
            tap("添加网络"); find("连接网络"); tap("保存"); find("网络名称应为 1–32 字节")
            capture(f"{style}-connection-validation")
            tap("WPA2-Personal"); tap("无"); tree(True)
            assert not nodes("密码"), "Open networks must not collect a password"
            find("隐藏网络"); tap("取消"); find("启用 WLAN")
            tap(f"网络详情：{ssid}"); find("自动连接"); find("断开连接")
            capture(f"{style}-details")
            if not args.read_only and not report["verifiedUiChanges"]:
                before = switch("自动连接").get("checked") == "true"
                changed["自动连接"] = before
                set_switch("自动连接", not before); set_switch("自动连接", before)
                del changed["自动连接"]
                report["verifiedUiChanges"].append("autoConnect")
                tap("分享"); find("扫描二维码连接网络"); capture("sharing-qr"); tap("好")
                report["qrSharing"] = True
            scroll_to("按流量计费"); find("代理"); find("IP 设置"); find("隐私")
            capture(f"{style}-network-options")
            tap("代理"); find("取消"); capture(f"{style}-proxy-form")
            tap("无"); capture(f"{style}-proxy-mode-menu"); tap("手动"); find("代理主机"); find("代理端口")
            capture(f"{style}-proxy-editor"); tap("取消")
            tap("IP 设置"); tap("DHCP"); tap("静态"); find("IPv4 地址"); find("网关"); find("DNS")
            capture(f"{style}-ip-editor"); tap("取消")
            back_to_overview(); scroll_to("高级 WLAN 设置")
            capture(f"{style}-preferences")
            if not args.read_only and "publicNetworkNotification" not in report["verifiedUiChanges"]:
                before = switch("附近有公共网络时发出通知").get("checked") == "true"
                changed["附近有公共网络时发出通知"] = before
                set_switch("附近有公共网络时发出通知", not before); set_switch("附近有公共网络时发出通知", before)
                del changed["附近有公共网络时发出通知"]
                report["verifiedUiChanges"].append("publicNetworkNotification")
            tap("高级 WLAN 设置"); find("允许连接 WEP 网络"); capture(f"{style}-advanced"); back()
            if not args.read_only and "autoWakeup" not in report["verifiedUiChanges"] and adb("shell", "cmd", "location", "is-location-enabled") == "true":
                scroll_to("高级 WLAN 设置"); tap("高级 WLAN 设置")
                before_scan = switch("WLAN 扫描").get("checked") == "true"
                changed["WLAN 扫描"] = before_scan
                set_switch("WLAN 扫描", True); back(); scroll_to("自动开启 WLAN", up=True)
                before_wake = switch("自动开启 WLAN").get("checked") == "true"
                changed["自动开启 WLAN"] = before_wake
                set_switch("自动开启 WLAN", not before_wake); set_switch("自动开启 WLAN", before_wake)
                del changed["自动开启 WLAN"]
                scroll_to("高级 WLAN 设置"); tap("高级 WLAN 设置"); set_switch("WLAN 扫描", before_scan)
                del changed["WLAN 扫描"]
                back(); report["verifiedUiChanges"].append("autoWakeup")
            report["styles"].append(style)
            print(f"{style}: list, detail, proxy editor, preferences and advanced page verified", flush=True)
    finally:
        for _ in range(3):
            if nodes("关于选项卡") or nodes("About tab"):
                break
            back()
        for label, value in reversed(list(changed.items())):
            if label == "WLAN 扫描" and not nodes("允许连接 WEP 网络"):
                scroll_to("高级 WLAN 设置"); tap("高级 WLAN 设置")
            elif label != "WLAN 扫描" and nodes("允许连接 WEP 网络"):
                back()
            scroll_to(label, up=label == "自动连接")
            set_switch(label, value, check_error=False)
            del changed[label]
        tap("关于选项卡"); tap("关于选项卡"); tap("外观"); tap("界面风格"); tap(styles[original.get("theme", "cupertino")])
        back(); tap("语言"); tap({"system": "跟随系统", "zh-CN": "简体中文", "en": "English"}[original.get("language", "system")])
        for _ in range(20):
            if prefs("appearance") == original:
                break
            time.sleep(.2)
        assert prefs("appearance") == original
        assert prefs("MainActivity") == mode
        report["preferencesRestored"] = True
        if ssid is not None:
            tap("设置选项卡" if nodes("设置选项卡") else "Settings tab")
            find(ssid); capture("final-overview")
    (out / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
