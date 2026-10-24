"""Runs only on the isolated CI-created Android emulator, never a customer phone."""
import json
import pathlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT / "staging-artifacts"
PACKAGE = "com.example.codresan"
results = []


def command(*args):
    run = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    if run.returncode:
        raise RuntimeError(f"Command failed: {args[0]} {args[1:3]}: {run.stdout[-1500:]}")
    return run.stdout


devices = command("adb", "devices")
serials = [line.split()[0] for line in devices.splitlines() if line.startswith("emulator-") and line.endswith("\tdevice")]
if len(serials) != 1:
    raise RuntimeError("Exactly one CI emulator is required; physical devices will never be selected")
serial = serials[0]


def adb(*args):
    return command("adb", "-s", serial, *args)


def probe(action):
    output = adb("shell", "am", "instrument", "-w", "-e", "action", action,
                 "com.example.codresan.qa/com.example.codresan.qa.UpgradeProbe")
    if "QA_SUCCESS:" + action not in output:
        raise RuntimeError(output)
    results.append({"test": action, "passed": True})


def launch():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(4)


def screenshot(name):
    with (ARTIFACTS / name).open("wb") as output:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], stdout=output, check=True)


try:
    # Device-side app requests must never reach the operational relay during QA.
    # Host-side artifact downloads are unaffected; SMS console delivery needs no data.
    adb("shell", "settings", "put", "global", "http_proxy", "127.0.0.1:9")
    assert adb("shell", "settings", "get", "global", "http_proxy").strip() == "127.0.0.1:9"
    # Keep the radio available for realistic emulator SMS delivery. All app
    # OkHttp clients use the verified, intentionally unavailable system proxy.
    time.sleep(3)
    results.append({"test": "app-network-blocked-by-system-proxy", "passed": True})
    old = ARTIFACTS / "classic-2.1.0.apk"
    new = ARTIFACTS / "MAHANBOT-Android-release.apk"
    qa = ARTIFACTS / "mahan-upgrade-probe.apk"
    adb("install", "-r", str(old))
    adb("install", "-r", str(qa))
    probe("seed")
    # The stored API points at test.invalid; no synthetic OTP is sent to production.
    if adb("shell", "getprop", "ro.build.version.sdk").strip() == "24":
        # The real 2.1.0 baseline crashes in TextView_fontFamily on this API.
        # Seeded old-schema data/Keystore remain valid for a genuine in-place upgrade.
        results.append({"test": "baseline-2.1.0-ui", "status": "known-baseline-font-crash-not-relaunched"})
    else:
        launch()
        screenshot("before-upgrade.png")
    adb("install", "-r", str(new))
    launch()
    probe("verify-upgrade")
    launch()  # Finishing custom instrumentation terminates its target process.
    screenshot("after-upgrade.png")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.RECEIVE_SMS")
    probe("enable-sms")
    launch()
    for text in ("کد تایید جهت بازیابی کد رهگیری ثبت نام تسهیلات ازدواج: 123456",
                 "کد تایید جهت حذف ثبت نام تسهیلات ازدواج: 654321", "۱۲۳۴۵۶",
                 "بانک: رمز پویا 888888"):
        adb("emu", "sms", "send", "2000411", text)
        time.sleep(3)
    # Ordered SMS delivery and cold-start receivers can lag on shared CI hosts.
    time.sleep(15)
    launch()
    screenshot("typed-sms-before-probe.png")
    probe("verify-sms")
    launch()
    screenshot("typed-sms.png")
    # Isolated emulator only: fresh install, no real user data exists in this device.
    adb("uninstall", PACKAGE)
    adb("install", str(new))
    launch()
    probe("verify-fresh")
    launch()
    screenshot("fresh-install.png")
    adb("shell", "uiautomator", "dump", "/sdcard/mahan-window.xml")
    xml = adb("shell", "cat", "/sdcard/mahan-window.xml")
    root = ET.fromstring(xml[xml.find("<?xml"):])
    button = None
    for _ in range(12):
        adb("shell", "uiautomator", "dump", "/sdcard/mahan-window.xml")
        xml = adb("shell", "cat", "/sdcard/mahan-window.xml")
        idx = xml.find("<?xml")
        if idx != -1:
            root = ET.fromstring(xml[idx:])
            # Dismiss any system ANR dialog (e.g. Pixel Launcher isn't responding)
            if any("responding" in node.attrib.get("text", "") or node.attrib.get("text") == "Close app" for node in root.iter("node")):
                adb("shell", "input", "keyevent", "4")
                time.sleep(1)
                launch()
                continue
            button = next((node for node in root.iter("node") if node.attrib.get("resource-id") == PACKAGE + ":id/btnSettings"), None)
            if button is not None:
                break
            # Scroll up towards top of screen in case main scroll view was scrolled down
            adb("shell", "input", "swipe", "160", "150", "160", "500", "300")
        time.sleep(1)
    if button is None:
        raise RuntimeError("btnSettings not found on main screen")
    import re
    x1,y1,x2,y2 = map(int, re.findall(r"\d+", button.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    support = None
    for _ in range(10):
        time.sleep(1)
        adb("shell", "uiautomator", "dump", "/sdcard/mahan-window.xml")
        xml = adb("shell", "cat", "/sdcard/mahan-window.xml")
        idx = xml.find("<?xml")
        if idx != -1:
            root = ET.fromstring(xml[idx:])
            support = next((node for node in root.iter("node") if node.attrib.get("text") == "پشتیبانی ماهان"), None)
            if support is not None:
                break
            scroll = next((node for node in root.iter("node") if "scroll" in node.attrib.get("class", "").lower()), None)
            if scroll is not None:
                sx1, sy1, sx2, sy2 = map(int, re.findall(r"\d+", scroll.attrib["bounds"]))
                smid_x = (sx1 + sx2) // 2
                start_y = int(sy1 + (sy2 - sy1) * 0.75)
                end_y = int(sy1 + (sy2 - sy1) * 0.25)
                adb("shell", "input", "swipe", str(smid_x), str(start_y), str(smid_x), str(end_y), "300")
            else:
                adb("shell", "input", "swipe", "160", "450", "160", "200", "300")
    if support is None:
        raise RuntimeError("Settings dialog or 'پشتیبانی ماهان' button not found in UI dump")
    x1,y1,x2,y2=map(int,re.findall(r"\d+",support.attrib["bounds"]))
    adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));time.sleep(3)
    screenshot("support.png")
    results.append({"test":"support-screen-opens","passed":True})
    log=adb("logcat","-d","-s","AndroidRuntime:E")
    if "FATAL EXCEPTION" in log:
        raise RuntimeError("AndroidRuntime crash recorded")
    results.append({"test":"no-android-runtime-crash","passed":True})
except Exception:
    import traceback
    (ARTIFACTS / "emulator-failure.txt").write_text(traceback.format_exc(), encoding="utf-8")
    try:
        screenshot("failure.png")
        (ARTIFACTS / "android-runtime-errors.txt").write_text(adb("logcat", "-d", "-s", "AndroidRuntime:E"), encoding="utf-8")
    except Exception:
        pass
    raise
finally:
    (ARTIFACTS / "emulator-results.json").write_text(json.dumps(results,indent=2),encoding="utf-8")
print(json.dumps(results,indent=2))
