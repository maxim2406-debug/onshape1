#!/usr/bin/env python3
"""Нажатие на элемент по тексту через uiautomator (для смоук-теста).

ui.py tap "текст" [--contains] [--index N] — ищет узел с текстом, при необходимости листает вниз, нажимает в центр.
ui.py has "текст" — код 0, если текст на экране.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=60).stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            return ET.fromstring(xml[xml.index("<hierarchy"):])
        time.sleep(1)
    return None


def find(root, text, contains):
    out = []
    for n in root.iter("node"):
        t = n.get("text", "") or n.get("content-desc", "")
        if (text in t) if contains else (t == text):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                if x2 > x1 and y2 > y1:
                    out.append(((x1 + x2) // 2, (y1 + y2) // 2, t))
    return out


def main():
    cmd, text = sys.argv[1], sys.argv[2]
    contains = "--contains" in sys.argv
    index = int(sys.argv[sys.argv.index("--index") + 1]) if "--index" in sys.argv else 0
    for attempt in range(6):
        root = dump()
        hits = find(root, text, contains) if root is not None else []
        if len(hits) > index:
            x, y, t = hits[index]
            if cmd == "has":
                print(f"есть: {t}")
                return 0
            print(f"нажатие: «{t}» ({x},{y})")
            adb("shell", "input", "tap", str(x), str(y))
            return 0
        adb("shell", "input", "swipe", "160", "500", "160", "200", "300")
        time.sleep(1)
    print(f"не найдено: «{text}»")
    if root is not None:
        texts = [n.get("text") for n in root.iter("node") if n.get("text")]
        print("на экране: " + " | ".join(texts[:40]))
    return 1


if __name__ == "__main__":
    sys.exit(main())
