#!/usr/bin/env python3
"""Physically tap app menus and device navigation on an explicit AVD; restore appearance."""
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
    parser.add_argument("--theme", choices=("cupertino", "material", "miuix", "coui"), action="append", help="Limit styles; the default exercises all four")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("An explicit emulator serial is required")
    prefix = [args.adb, "-s", args.serial]
    output = Path(__file__).resolve().parents[1] / "build/reports/app-ui" / args.serial
    output.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), timeout=45).decode("utf-8").strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1"

    def preferences(name):
        result = subprocess.run(prefix + ["shell", "run-as", "com.napreth.set4u", "cat", f"shared_prefs/{name}.xml"], capture_output=True, timeout=15)
        return {n.get("name"): n.text for n in ET.fromstring(result.stdout)} if result.returncode == 0 else {}

    original = preferences("appearance")
    original_mode = preferences("MainActivity").get("workMode", "shell")
    themes = {"cupertino": "Apple Cupertino", "material": "Google Material", "miuix": "HyperOS Miuix", "coui": "ColorOS COUI"}
    modes = {"system": "跟随系统", "light": "浅色", "dark": "深色"}
    languages = {"system": "跟随系统", "zh-CN": "简体中文", "en": "English"}

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/set4u-app-ui.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/set4u-app-ui.xml"))

    def find(text, attempts=5):
        for _ in range(attempts):
            for node in tree().iter("node"):
                if text in (node.get("text"), node.get("content-desc")):
                    b = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
                    if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
                        return b
            time.sleep(.2)
        raise AssertionError(f"UI element not visible: {text}")

    def visible(text):
        return any(text in (n.get("text"), n.get("content-desc")) for n in tree().iter("node"))

    def tap(text):
        b = find(text)
        adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))

    def back():
        adb("shell", "input", "keyevent", "BACK")

    size = [int(n) for n in re.findall(r"\d+", adb("shell", "wm", "size"))][-2:]
    w, h = size

    def scroll_to(text):
        for direction in (1, -1):
            for _ in range(4):
                if visible(text):
                    return
                y1, y2 = (h * 3 // 4, h // 3) if direction == 1 else (h // 3, h * 3 // 4)
                adb("shell", "input", "swipe", str(w // 2), str(y1), str(w // 2), str(y2), "250")
        find(text)

    def screenshot(name):
        (output / f"{name}.png").write_bytes(subprocess.check_output(prefix + ["exec-out", "screencap", "-p"], timeout=20))
        (output / f"{name}.xml").write_text(ET.tostring(tree(), encoding="unicode"), encoding="utf-8")

    def focused(name):
        windows = adb("shell", "dumpsys", "activity", "activities")
        focus = next((line for line in windows.splitlines() if "topResumedActivity" in line or "mResumedActivity" in line), "")
        assert "com.napreth.set4u" in focus and "MainActivity" in focus, focus
        (output / f"{name}-focus.txt").write_text(focus, encoding="utf-8")

    def choose(row, option):
        scroll_to(row)
        tap(row)
        tap(option)

    def about():
        tap("关于选项卡" if visible("关于选项卡") else "About tab")
        tap("关于选项卡" if visible("关于选项卡") else "About tab")

    adb("shell", "input", "keyevent", "WAKEUP")
    adb("shell", "am", "force-stop", "com.napreth.set4u")
    adb("shell", "am", "start", "-W", "-n", "com.napreth.set4u/.MainActivity")
    results = []
    try:
        about()
        choose("语言" if visible("关于选项卡") else "Language", "简体中文")
        for theme, title in themes.items():
            if args.theme and theme not in args.theme:
                continue
            print(f"{theme}: testing", flush=True)
            tap("外观")
            choose("界面风格", title)
            scroll_to("界面风格")
            tap("界面风格")
            for option in themes.values():
                find(option)
            # The old native popup appeared in UIAutomator even when invisible and unfocused.
            # Keep it open past the input timeout, record real pixels, and check native focus.
            if theme == "cupertino":
                time.sleep(6)
            focused(f"{theme}-menu")
            screenshot(f"{theme}-style-menu")
            back()
            find("界面风格")
            tap("主题")
            find("深色")
            adb("shell", "input", "tap", "4", str(h // 2))
            find("界面风格")
            choose("主题", "深色")
            find("深色")
            choose("主题", "浅色")
            back()
            choose("语言", "English")
            find("Appearance")
            choose("Language", "简体中文")
            scroll_to("许可证")
            tap("许可证")
            find("MIT License")
            focused(f"{theme}-license")
            screenshot(f"{theme}-license")
            tap("关闭")
            tap("工作模式选项卡")
            current_mode = "root" if original_mode == "root" else "ADB（Shizuku）"
            tap(current_mode)
            find("root")
            find("ADB（Shizuku）")
            focused(f"{theme}-work-mode")
            screenshot(f"{theme}-work-mode")
            tap(current_mode)
            status = find("状态")
            button = find("重新获取" if visible("重新获取") else "重试" if visible("重试") else "获取权限")
            assert status[3] <= button[1], "Enable action must be below status"
            label = find("授权")
            assert label[2] < button[0], "Action button must be to the right of its label"
            assert abs((label[1] + label[3]) - (button[1] + button[3])) < 24, "Action label and button must share the third row"
            assert not visible("以 root 模拟 shell 身份")
            tap("设备信息")
            find("制造商")
            find(adb("shell", "getprop", "ro.product.model"))
            assert not visible("关闭"), "Device information is still a modal dialog"
            screenshot(f"{theme}-device-info")
            # Exercise the visible back button as well as the OS back action.
            tap("返回")
            find(current_mode)
            tap("设备信息")
            find("制造商")
            back()
            find(current_mode)
            about()
            results.append({"theme": theme, "menus": True, "focus": True, "license": True, "workModeDropdown": True, "devicePageAndBack": True})
            print(f"{theme}: OK", flush=True)
    finally:
        # A failed assertion may leave a menu hiding the underlying accessibility tree.
        adb("shell", "am", "force-stop", "com.napreth.set4u")
        adb("shell", "am", "start", "-W", "-n", "com.napreth.set4u/.MainActivity")
        about()
        if not visible("关于选项卡"):
            choose("Language", "简体中文")
        tap("外观")
        choose("界面风格", themes[original.get("theme", "cupertino")])
        choose("主题", modes[original.get("themeMode", "system")])
        back()
        choose("语言", languages[original.get("language", "system")])
        assert preferences("MainActivity").get("workMode", "shell") == original_mode
    summary = {"serial": args.serial, "api": adb("shell", "getprop", "ro.build.version.sdk"), "themes": results, "originalWorkModePreserved": True, "appearanceRestored": True}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
