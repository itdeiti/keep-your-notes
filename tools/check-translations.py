#!/usr/bin/env python3
"""Check that every translation has exactly the strings of the default language.

    python3 tools/check-translations.py        # all values-<lang>/ folders
    python3 tools/check-translations.py fr     # only values-fr/

Compares names of <string> and <plurals> in app/src/main/res/values/strings*.xml (English,
default) with values-<lang>/strings*.xml, and the %1$s / %1$d placeholders of each string.
Exit code 1 if something is missing, extra or has other placeholders.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
ARG = re.compile(r"%(\d+\$)?[sd]")


def load(folder):
    out = {}
    for f in sorted(glob.glob(os.path.join(RES, folder, "strings*.xml"))):
        for e in ET.parse(f).getroot():
            if e.tag == "string":
                out[e.get("name")] = sorted(set(ARG.findall(e.text or "")))
            elif e.tag == "plurals":
                # placeholders may differ between quantities ("one" often has no number)
                out[e.get("name")] = None
    return out


def main():
    base = load("values")
    langs = sys.argv[1:] or sorted(d[len("values-"):] for d in os.listdir(RES)
                                   if re.fullmatch(r"values-[a-z]{2,3}(-r[A-Z]{2})?", d))
    bad = 0
    for lang in langs:
        tr = load("values-" + lang)
        missing = sorted(set(base) - set(tr))
        extra = sorted(set(tr) - set(base))
        args = sorted(k for k in set(base) & set(tr) if base[k] is not None and base[k] != tr[k])
        print("%s: %d strings, %d missing, %d extra, %d placeholder mismatches"
              % (lang, len(tr), len(missing), len(extra), len(args)))
        for k in missing:
            print("  missing     ", k)
        for k in extra:
            print("  extra       ", k)
        for k in args:
            print("  placeholders", k, base[k], "->", tr[k])
        bad += len(missing) + len(extra) + len(args)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
