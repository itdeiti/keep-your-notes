#!/usr/bin/env python3
"""
Import an existing Obsidian vault (e.g. a Zettelkasten with Google Keep exports)
into notes-vault, step by step. Privacy rule: this script never prints note
content, titles or file names, only counts. Details go to files under _import/
that only the user opens.

  python3 vault_import.py copy  SRC_VAULT DEST_VAULT   # steps 1+2: copy and clean into DEST/_import/
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone

SKIP_DIRS = {".git", ".obsidian", ".trash", "index", "templates"}
SKIP_ROOT_FILES = {"README.md", "CAPTURE-SETUP.md", "MCP-SETUP.md"}
IMG_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".heic", ".pdf", ".m4a", ".mp3", ".mp4"}
# Frontmatter keys we report by name (schema words, not content). Everything else is only counted.
KNOWN_KEYS = ["created", "updated", "modified", "date", "tags", "labels", "aliases", "archived", "pinned",
              "color", "trashed", "title", "source", "url", "author", "type", "status", "keep_id", "id"]
DATE_KEYS = ["created", "date", "updated", "modified"]

stats = Counter()
key_counts = Counter()
unknown_keys = set()


def split_front(text):
    lines = text.replace("\r\n", "\n").split("\n")
    if lines and lines[0] == "---":
        for i in range(1, len(lines)):
            if lines[i] == "---":
                return lines[1:i], "\n".join(lines[i + 1:])
    return [], "\n".join(lines)


def front_get(front, key):
    for l in front:
        if l.startswith(key + ":"):
            return l[len(key) + 1:].strip().strip('"\'')
    return None


def parse_date(s):
    if not s:
        return None
    s = s.strip()
    for fmt in ("%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M",
                "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d", "%d.%m.%Y"):
        try:
            d = datetime.strptime(s.replace("Z", "+0000"), fmt)
            if d.tzinfo:
                d = d.astimezone().replace(tzinfo=None)
            return d
        except ValueError:
            pass
    return None


def git_added_date(src, rel):
    try:
        out = subprocess.run(["git", "-C", src, "log", "--diff-filter=A", "--follow", "--format=%aI", "--", rel],
                             capture_output=True, text=True, timeout=20).stdout.strip().splitlines()
        return parse_date(out[-1][:19]) if out else None
    except Exception:
        return None


FILENAME_DATE = re.compile(r"(\d{4}-\d{2}-\d{2})[_ T]?(\d{2})?[-:]?(\d{2})?")
EMBED_WIKI = re.compile(r"!\[\[([^\]|#]+)(?:[|#][^\]]*)?\]\]")
EMBED_MD = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)\)")


def created_for(src, rel, front, path):
    for k in DATE_KEYS[:2]:
        d = parse_date(front_get(front, k))
        if d:
            stats["datum_aus_frontmatter"] += 1
            return d
    m = FILENAME_DATE.search(os.path.basename(rel))
    if m:
        d = parse_date(m.group(1) + (" %s:%s" % (m.group(2), m.group(3)) if m.group(2) and m.group(3) else ""))
        if d:
            stats["datum_aus_dateiname"] += 1
            return d
    d = git_added_date(src, rel)
    if d:
        stats["datum_aus_git"] += 1
        return d
    stats["datum_aus_dateisystem"] += 1
    return datetime.fromtimestamp(os.path.getmtime(path))


def normalize_body(body):
    out = []
    for l in body.split("\n"):
        s = l.lstrip()
        ind = l[: len(l) - len(s)]
        if s.startswith(("☐ ", "□ ")):
            l = ind + "- [ ] " + s[2:]; stats["checkbox_umgewandelt"] += 1
        elif s.startswith(("☑ ", "☒ ", "✅ ", "✓ ", "✔ ")):
            l = ind + "- [x] " + s[2:]; stats["checkbox_umgewandelt"] += 1
        elif re.match(r"^[-*] \[[ xX]\]\S", s):  # "- [ ]Text" without space
            l = ind + s[:5] + " " + s[5:]; stats["checkbox_umgewandelt"] += 1
        out.append(l)
    return "\n".join(out)


def cmd_copy(src, dest):
    src = os.path.abspath(os.path.expanduser(src))
    dest = os.path.abspath(os.path.expanduser(dest))
    stage = os.path.join(dest, "_import")
    if os.path.exists(stage):
        print("Abbruch: _import/ existiert schon")
        return 1
    notes_dir = os.path.join(stage, "notizen")
    att_dir = os.path.join(stage, "attachments")
    os.makedirs(notes_dir)
    os.makedirs(att_dir)

    # all files by basename for resolving embeds
    files_by_name = {}
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d not in {".git", ".obsidian", ".trash"}]
        for f in files:
            files_by_name.setdefault(f, os.path.join(root, f))

    seen_hash = {}
    mapping = []
    used_names = set()
    for root, dirs, files in os.walk(src):
        rel_root = os.path.relpath(root, src)
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        for f in sorted(files):
            if not f.endswith(".md"):
                continue
            stats["md_gefunden"] += 1
            if rel_root == "." and f in SKIP_ROOT_FILES:
                stats["uebersprungen_setup"] += 1
                continue
            rel = os.path.normpath(os.path.join(rel_root, f))
            path = os.path.join(root, f)
            try:
                text = open(path, encoding="utf-8").read()
            except Exception as e:
                stats["fehler_" + type(e).__name__] += 1
                continue
            front, body = split_front(text)
            for l in front:
                m = re.match(r"^([A-Za-z_][\w-]*):", l)
                if m and m.group(1) in KNOWN_KEYS:
                    key_counts[m.group(1)] += 1
                elif m:
                    unknown_keys.add(m.group(1))
            if not body.strip():
                stats["leer_uebersprungen"] += 1
                continue
            body = normalize_body(body)
            h = hashlib.sha256(re.sub(r"\s+", " ", body).strip().lower().encode()).hexdigest()
            if h in seen_hash:
                stats["duplikat_uebersprungen"] += 1
                continue
            seen_hash[h] = rel

            # embeds: copy referenced local files, rewrite to Obsidian-style ![[name]]
            def wiki(m):
                name = os.path.basename(m.group(1).strip())
                p = files_by_name.get(name)
                if p and os.path.splitext(name)[1].lower() in IMG_EXT:
                    shutil.copy2(p, os.path.join(att_dir, name)); stats["anhang_kopiert"] += 1
                elif os.path.splitext(name)[1].lower() in IMG_EXT:
                    stats["anhang_fehlt"] += 1
                return m.group(0)

            def md(m):
                target = m.group(2)
                if target.startswith(("http://", "https://", "data:")):
                    return m.group(0)
                name = os.path.basename(target.split("?")[0]).replace("%20", " ")
                p = files_by_name.get(name)
                if p:
                    shutil.copy2(p, os.path.join(att_dir, name)); stats["anhang_kopiert"] += 1
                    stats["bildlink_umgeschrieben"] += 1
                    return "![[%s]]" % name
                stats["anhang_fehlt"] += 1
                return m.group(0)

            body = EMBED_WIKI.sub(wiki, body)
            body = EMBED_MD.sub(md, body)

            created = created_for(src, rel, front, path)
            front = [l for l in front if not l.startswith("created:")]
            front.insert(0, "created: " + created.strftime("%Y-%m-%dT%H:%M"))
            front.append("import_quelle: zettelkasten")

            name = f
            n = 2
            while name.lower() in used_names:
                name = "%s %d.md" % (f[:-3], n); n += 1
            if name != f:
                stats["name_kollision_umbenannt"] += 1
            used_names.add(name.lower())
            with open(os.path.join(notes_dir, name), "w", encoding="utf-8") as out:
                out.write("---\n" + "\n".join(front) + "\n---\n" + body)
            os.utime(os.path.join(notes_dir, name), (created.timestamp(), created.timestamp()))
            mapping.append({"quelle": rel, "ziel": name, "created": created.strftime("%Y-%m-%d")})
            stats["notiz_kopiert"] += 1
            if created < datetime.now().replace(year=datetime.now().year - 2):
                stats["aelter_als_2_jahre"] += 1

    with open(os.path.join(stage, "mapping.json"), "w", encoding="utf-8") as out:
        json.dump(mapping, out, ensure_ascii=False, indent=1)
    stats["anhang_dateien_im_ziel"] = len(os.listdir(att_dir))

    print("== Zahlen ==")
    for k in sorted(stats):
        print("%-28s %d" % (k, stats[k]))
    print("== Frontmatter-Felder (bekannte Namen) ==")
    for k in KNOWN_KEYS:
        if key_counts[k]:
            print("%-28s %d" % (k, key_counts[k]))
    print("%-28s %d" % ("andere_feldnamen_verschieden", len(unknown_keys)))
    return 0


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "copy":
        try:
            sys.exit(cmd_copy(sys.argv[2], sys.argv[3]))
        except Exception as e:  # never leak paths or content
            print("Fehler:", type(e).__name__)
            sys.exit(2)
    print(__doc__)
