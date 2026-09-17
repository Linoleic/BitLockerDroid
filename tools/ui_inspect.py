#!/usr/bin/env python3
"""
Fast UI Inspector and Click Tool for Android ADB
Dumps the UI hierarchy in milliseconds, prints visible text, or clicks elements by text.
Usage:
  python3 tools/ui_inspect.py           # Dump all visible text and interactive elements
  python3 tools/ui_inspect.py tap "text" # Automatically locate text and tap its center
"""

import sys
import re
import subprocess
import xml.etree.ElementTree as ET

def run_adb(cmd):
    full_cmd = f"adb {cmd}"
    res = subprocess.run(full_cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout.strip()

def parse_bounds(bounds_str):
    # format: [x1,y1][x2,y2]
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
    if m:
        return int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
    return None

def dump_ui():
    run_adb("shell uiautomator dump /sdcard/window_dump.xml")
    xml_content = run_adb("shell cat /sdcard/window_dump.xml")
    if not xml_content.startswith("<?xml") and "<hierarchy" not in xml_content:
        # Fallback: pull
        run_adb("pull /sdcard/window_dump.xml /tmp/window_dump.xml")
        try:
            with open("/tmp/window_dump.xml", "r", encoding="utf-8") as f:
                xml_content = f.read()
        except Exception:
            pass
    return xml_content

def main():
    args = sys.argv[1:]
    target_tap = None
    if len(args) >= 2 and args[0] == "tap":
        target_tap = args[1]
    
    xml_data = dump_ui()
    if not xml_data:
        print("Error: Could not dump UI from device")
        sys.exit(1)

    try:
        root = ET.fromstring(xml_data)
    except Exception as e:
        print(f"XML parse error: {e}")
        sys.exit(1)

    nodes = []
    for node in root.iter("node"):
        text = node.attrib.get("text", "").strip()
        desc = node.attrib.get("content-desc", "").strip()
        bounds = node.attrib.get("bounds", "")
        clickable = node.attrib.get("clickable", "false") == "true"
        cls = node.attrib.get("class", "").split(".")[-1]
        
        if text or desc:
            nodes.append({
                "text": text,
                "desc": desc,
                "bounds": bounds,
                "clickable": clickable,
                "class": cls
            })

    if target_tap:
        # Find matching node
        found = None
        for n in nodes:
            if target_tap in n["text"] or target_tap in n["desc"]:
                found = n
                break
        if found:
            coords = parse_bounds(found["bounds"])
            if coords:
                cx = (coords[0] + coords[2]) // 2
                cy = (coords[1] + coords[3]) // 2
                print(f"Tapping '{target_tap}' at center ({cx}, {cy}) [{found['bounds']}]")
                run_adb(f"shell input tap {cx} {cy}")
                sys.exit(0)
        print(f"Element '{target_tap}' not found on screen!")
        sys.exit(1)

    # Print summary
    print(f"{'BOUNDS':<24} | {'CLASS':<16} | {'TEXT / CONTENT-DESC'}")
    print("-" * 75)
    for n in nodes:
        content = n['text']
        if n['desc']:
            content += f" (desc: {n['desc']})"
        click_mark = "[Clickable] " if n['clickable'] else ""
        print(f"{n['bounds']:<24} | {n['class']:<16} | {click_mark}{content}")

if __name__ == "__main__":
    main()
