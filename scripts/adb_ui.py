#!/usr/bin/env python3
"""Tiny adb UI driver for the emulator demo (stdlib only). Finds on-screen text via `uiautomator dump`.

  adb_ui.py texts                 list visible text + bounds
  adb_ui.py tap "동의하고"         tap the first node whose text contains the substring
  adb_ui.py tapx "1"              tap the node whose text EXACTLY equals the argument (keypad keys)
  adb_ui.py xy 1200 800           tap raw screen coordinates (px)
  adb_ui.py type "Minjun"         `input text` (ASCII only: adb cannot type Hangul)
  adb_ui.py shot out.png          screenshot
  adb_ui.py key BACK|HOME|ENTER   key event
Env: ADB (path), SERIAL (default emulator-5554).
"""
import os
import re
import subprocess
import sys
import tempfile

ADB = os.environ.get("ADB", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
SER = os.environ.get("SERIAL", "emulator-5554")


def adb(*a, out=False):
    r = subprocess.run([ADB, "-s", SER, *a], capture_output=True)
    return r.stdout if out else r.stdout.decode(errors="replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?clickable="(\w+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, d, c, x1, y1, x2, y2 = m.groups()
        label = (t or d).replace("&#10;", " ").replace("&amp;", "&")
        if label.strip():
            out.append((label, c == "true", (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2))
    return out


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "texts":
        for label, click, x, y in nodes():
            print(f"{label[:70]:70} ({x},{y}){' *' if click else ''}")
    elif cmd == "tap":
        for label, _, x, y in nodes():
            if args[0] in label:
                adb("shell", "input", "tap", str(x), str(y))
                print(f"tapped '{label[:40]}' at ({x},{y})")
                return
        sys.exit(f"no node containing {args[0]!r}")
    elif cmd == "tapx":
        for label, _, x, y in nodes():
            if label.strip() == args[0]:
                adb("shell", "input", "tap", str(x), str(y))
                print(f"tapped exact '{label}' at ({x},{y})")
                return
        sys.exit(f"no node exactly {args[0]!r}")
    elif cmd == "xy":
        adb("shell", "input", "tap", args[0], args[1])
    elif cmd == "type":
        adb("shell", "input", "text", args[0].replace(" ", "%s"))
    elif cmd == "key":
        adb("shell", "input", "keyevent", args[0])
    elif cmd == "shot":
        open(args[0], "wb").write(adb("exec-out", "screencap", "-p", out=True))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
