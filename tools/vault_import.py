#!/usr/bin/env python3
"""
Import an existing Obsidian vault (e.g. a Zettelkasten with Google Keep exports)
into notes-vault, step by step. Privacy rule: this script never prints note
content, titles or file names, only counts. Details go to files under _import/
that only the user opens.

  python3 vault_import.py copy   SRC_VAULT DEST_VAULT  # steps 1+2: copy and clean into DEST/_import/
  python3 vault_import.py topics DEST_VAULT [K]        # step 3: group notes by meaning, name groups -> tag-vorschlag.md, regeln.md
  python3 vault_import.py sort   DEST_VAULT            # step 5: folder + tags per note from regeln.md -> vorschau.csv
  python3 vault_import.py apply  DEST_VAULT            # step 6: move notes per vorschau.csv, write tags, remove _import/

Local models via Ollama (http://localhost:11434): bge-m3 for embeddings, qwen2.5:32b for naming/sorting.
"""
import hashlib
import math
import random
import urllib.request
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


# ---------------- step 3: topics ----------------

OLLAMA = "http://localhost:11434"
EMBED_MODEL = "bge-m3"
LLM = os.environ.get("VAULT_LLM", "qwen2.5:32b")


def ollama(path, payload, timeout=600):
    req = urllib.request.Request(OLLAMA + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def ask_json(prompt):
    r = ollama("/api/generate", {"model": LLM, "prompt": prompt, "format": "json", "stream": False,
                                 "options": {"temperature": 0, "num_ctx": 8192}})
    return json.loads(r["response"])


def load_notes(stage):
    mapping = json.load(open(os.path.join(stage, "mapping.json"), encoding="utf-8"))
    notes = []
    for m in mapping:
        text = open(os.path.join(stage, "notizen", m["ziel"]), encoding="utf-8").read()
        front, body = split_front(text)
        notes.append({"ziel": m["ziel"], "titel": m["ziel"][:-3], "front": front, "body": body, "m": m})
    return mapping, notes


def normalize(v):
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def kmeans(vecs, k, iters=50, seed=7):
    rnd = random.Random(seed)
    cents = [vecs[rnd.randrange(len(vecs))]]
    while len(cents) < k:  # k-means++ with cosine distance
        d = [min(1 - dot(v, c) for c in cents) for v in vecs]
        tot = sum(d) or 1.0
        r, acc = rnd.random() * tot, 0.0
        for i, x in enumerate(d):
            acc += x
            if acc >= r:
                cents.append(vecs[i]); break
    assign = [0] * len(vecs)
    for _ in range(iters):
        new = [max(range(k), key=lambda j: dot(v, cents[j])) for v in vecs]
        if new == assign and _ > 0:
            break
        assign = new
        for j in range(k):
            members = [vecs[i] for i in range(len(vecs)) if assign[i] == j]
            if members:
                cents[j] = normalize([sum(c) / len(members) for c in zip(*members)])
    return assign, cents


def cmd_topics(dest, k):
    stage = os.path.join(os.path.abspath(os.path.expanduser(dest)), "_import")
    mapping, notes = load_notes(stage)

    # dates that are really an import day (many notes on one day) are unreliable
    days = Counter(m["created"] for m in mapping)
    bulk = {d for d, c in days.items() if c >= 20}
    for m in mapping:
        m["datum_unsicher"] = m["created"] in bulk
    json.dump(mapping, open(os.path.join(stage, "mapping.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("datum_unsicher", sum(m["datum_unsicher"] for m in mapping))

    cache_path = os.path.join(stage, "embeddings.json")
    cache = json.load(open(cache_path)) if os.path.exists(cache_path) else {}
    todo = [n for n in notes if n["ziel"] not in cache]
    for i in range(0, len(todo), 16):
        batch = todo[i:i + 16]
        r = ollama("/api/embed", {"model": EMBED_MODEL,
                                  "input": [(n["titel"] + "\n" + n["body"])[:2000] for n in batch]})
        for n, e in zip(batch, r["embeddings"]):
            cache[n["ziel"]] = normalize(e)
    json.dump(cache, open(cache_path, "w"))
    print("embeddings", len(cache))

    vecs = [cache[n["ziel"]] for n in notes]
    assign, cents = kmeans(vecs, k)
    groups = {}
    for n, a, v in zip(notes, assign, vecs):
        groups.setdefault(a, []).append((dot(v, cents[a]), n))
    print("gruppen", len(groups), "groessen", sorted((len(g) for g in groups.values()), reverse=True))

    existing = Counter()
    for n in notes:
        for l in n["front"]:
            if l.startswith("tags:"):
                for t in re.split(r"[,\[\]]", l[5:]):
                    t = t.strip().strip("'\"").lstrip("#")
                    if t: existing[t] += 1
        inside = False
        for l in n["front"]:  # YAML list form
            if l.startswith("tags:") and not l[5:].strip():
                inside = True; continue
            if inside and l.strip().startswith("- "):
                existing[l.strip()[2:].strip().strip("'\"").lstrip("#")] += 1
            elif inside:
                inside = False

    named = []
    for a, members in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        members.sort(key=lambda x: -x[0])
        sample = "\n\n".join("TITEL: %s\nTEXT: %s" % (n["titel"], n["body"][:300].replace("\n", " "))
                             for _, n in members[:8])
        try:
            r = ask_json(
                "Das sind persoenliche Notizen, die thematisch zusammengehoeren.\n\n" + sample +
                "\n\nGib dieser Gruppe einen Tag: 1 bis 2 deutsche Woerter, klein geschrieben, "
                "Bindestrich statt Leerzeichen (z.B. kochen, finanzen, kinder, reise, garten, arbeit). "
                "Dazu eine Beschreibung mit hoechstens 12 Woertern. "
                'Antworte nur als JSON: {"tag": "...", "beschreibung": "..."}')
            tag, desc = str(r.get("tag", "")).strip().lower(), str(r.get("beschreibung", "")).strip()
        except Exception as e:
            tag, desc = "gruppe-%d" % a, "(Benennung fehlgeschlagen: %s)" % type(e).__name__
            stats["benennung_fehler"] += 1
        named.append((tag or "gruppe-%d" % a, desc, members))
    print("benannt", len(named) - stats["benennung_fehler"], "fehler", stats["benennung_fehler"])

    with open(os.path.join(stage, "tag-vorschlag.md"), "w", encoding="utf-8") as f:
        f.write("# Tag-Vorschlag\n\nAutomatisch aus %d Notizen gruppiert (bge-m3 + %s). "
                "Nur zum Ansehen; die Liste, die gilt, steht in regeln.md.\n\n" % (len(notes), LLM))
        for tag, desc, members in named:
            f.write("## %s (%d)\n%s\n\nBeispiele: %s\n\n" % (
                tag, len(members), desc, ", ".join("[[%s]]" % n["titel"] for _, n in members[:5])))
        f.write("## Vorhandene Tags im Zettelkasten (Anzahl)\n\n")
        f.write(", ".join("%s (%d)" % (t, c) for t, c in existing.most_common(60)) + "\n")

    tags = []
    for tag, desc, _ in named:
        if tag not in [t for t, _ in tags]:
            tags.append((tag, desc))
    with open(os.path.join(stage, "regeln.md"), "w", encoding="utf-8") as f:
        f.write("""# Regeln fuer die Sortierung

Diese Datei steuert Schritt 5. Aendern, streichen, umbenennen erlaubt; Format beibehalten.

## Ordner (genau einer pro Notiz)
- projekte: hat ein Ziel und ein Ende (Reise planen, Umzug, Anschaffung, Bewerbung)
- bereiche: Dauerthemen, die laufend gepflegt werden (Gesundheit, Finanzen, Haushalt, Familie, Arbeit)
- wissen: Nachschlagen und Gelerntes (Rezepte, Anleitungen, Clippings, Ideen, Zitate, Buecher)
- archiv: erledigt, vorbei oder nicht mehr relevant
- inbox: nur wenn wirklich unklar

## Archiv-Regel
- Notizen aelter als 2 Jahre ohne offene Aufgabe kommen nach archiv.
- Bei Notizen ohne sicheres Datum entscheidet der Inhalt: erledigte Listen, vergangene Termine, Abgeschlossenes -> archiv.

## Tags (1 bis 3 pro Notiz, nur aus dieser Liste)
""")
        for tag, desc in tags:
            f.write("- %s: %s\n" % (tag, desc))
    print("tags_vorgeschlagen", len(tags))
    return 0


# ---------------- step 5: sort ----------------

def read_rules(stage):
    text = open(os.path.join(stage, "regeln.md"), encoding="utf-8").read()
    folders, tags, section = [], [], None
    for l in text.split("\n"):
        if l.startswith("## "):
            h = l[3:].lower()
            section = "ordner" if h.startswith("ordner") else "tags" if h.startswith("tags") else None
            continue
        m = re.match(r"^- ([^:]+):", l)
        if m and section == "ordner":
            folders.append(m.group(1).strip().lower())
        elif m and section == "tags":
            tags.append(m.group(1).strip().lower().lstrip("#"))
    return text, folders, tags


OPEN_TASK = re.compile(r"^\s*[-*+] \[ \]", re.M)


def cmd_sort(dest):
    stage = os.path.join(os.path.abspath(os.path.expanduser(dest)), "_import")
    rules, folders, tags = read_rules(stage)
    mapping, notes = load_notes(stage)
    unsure = {m["ziel"] for m in mapping if m.get("datum_unsicher")}
    two_years_ago = datetime.now().replace(year=datetime.now().year - 2).strftime("%Y-%m-%d")
    res_path = os.path.join(stage, "klassifikation.json")
    results = json.load(open(res_path, encoding="utf-8")) if os.path.exists(res_path) else {}
    print("ordner", len(folders), "tags", len(tags), "schon_erledigt", len(results))

    for i, n in enumerate(notes):
        if n["ziel"] in results:
            continue
        has_open = bool(OPEN_TASK.search(n["body"]))
        old_tags = [l for l in n["front"] if l.startswith("tags:") or l.strip().startswith("- ")]
        datum = "unbekannt" if n["ziel"] in unsure else n["m"]["created"]
        prompt = (
            rules + "\n\n---\nOrdne diese Notiz nach den Regeln oben ein.\n"
            "TITEL: %s\nERSTELLT: %s\nOFFENE AUFGABEN: %s\nALTE TAGS: %s\nTEXT:\n%s\n\n"
            "Waehle genau einen Ordner aus: %s.\nWaehle 1 bis 3 Tags nur aus: %s.\n"
            'Antworte nur als JSON: {"ordner": "...", "tags": ["..."], "begruendung": "hoechstens 12 Woerter"}'
        ) % (n["titel"], datum, "ja" if has_open else "nein", " ".join(old_tags)[:200], n["body"][:3000],
             ", ".join(folders), ", ".join(tags))
        try:
            r = ask_json(prompt)
            folder = str(r.get("ordner", "")).strip().lower()
            if folder not in folders:
                stats["ordner_ungueltig"] += 1
                folder = "inbox"
            raw = r.get("tags", [])
            raw = raw if isinstance(raw, list) else [raw]
            picked = []
            for t in raw:
                t = str(t).strip().lower().lstrip("#")
                if t in tags and t not in picked:
                    picked.append(t)
                else:
                    stats["tag_verworfen"] += 1
            why = str(r.get("begruendung", "")).strip()
        except Exception as e:
            stats["fehler_" + type(e).__name__] += 1
            folder, picked, why = "inbox", [], "Fehler bei der Einordnung"
        # hard archive rule for reliable dates
        if (n["ziel"] not in unsure and n["m"]["created"] < two_years_ago and not has_open
                and folder != "archiv" and "archiv" in folders):
            folder, why = "archiv", "Regel: aelter als 2 Jahre, keine offene Aufgabe. " + why
            stats["archiv_per_regel"] += 1
        results[n["ziel"]] = {"ordner": folder, "tags": picked[:3], "begruendung": why}
        if (i + 1) % 10 == 0:
            json.dump(results, open(res_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
            print("fortschritt", len(results), "/", len(notes), flush=True)
    json.dump(results, open(res_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    import csv
    with open(os.path.join(stage, "vorschau.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["datei", "erstellt", "datum_unsicher", "ordner", "tags", "begruendung"])
        for n in notes:
            r = results[n["ziel"]]
            w.writerow([n["ziel"], n["m"]["created"], "ja" if n["ziel"] in unsure else "",
                        r["ordner"], " ".join(r["tags"]), r["begruendung"]])

    per_folder = Counter(r["ordner"] for r in results.values())
    per_tagcount = Counter(len(r["tags"]) for r in results.values())
    print("== pro ordner ==")
    for f_ in folders:
        print("%-12s %d" % (f_, per_folder[f_]))
    print("tags_pro_notiz", dict(sorted(per_tagcount.items())))
    print("tags_genutzt", len({t for r in results.values() for t in r["tags"]}), "von", len(tags))
    for k in sorted(stats):
        print(k, stats[k])
    return 0


# ---------------- step 6: apply ----------------

def cmd_apply(dest):
    import csv
    dest = os.path.abspath(os.path.expanduser(dest))
    stage = os.path.join(dest, "_import")
    _, folders, tags = read_rules(stage)
    with open(os.path.join(stage, "vorschau.csv"), encoding="utf-8-sig", newline="") as f:
        sample = f.read(2048); f.seek(0)
        delim = ";" if sample.count(";") >= sample.count(",") else ","
        rows = list(csv.DictReader(f, delimiter=delim))
    print("zeilen", len(rows))
    for row in rows:
        name = (row.get("datei") or "").strip()
        src = os.path.join(stage, "notizen", name)
        if not name or not os.path.exists(src):
            stats["datei_fehlt"] += 1
            continue
        folder = (row.get("ordner") or "").strip().lower() or "inbox"
        if folder not in folders:
            stats["ordner_unbekannt_inbox"] += 1
            folder = "inbox"
        new_tags = []
        for t in (row.get("tags") or "").replace(",", " ").split():
            t = t.strip().lower().lstrip("#")
            if t and t not in new_tags:
                new_tags.append(t)
                if t not in tags:
                    stats["tag_ausserhalb_liste"] += 1
        text = open(src, encoding="utf-8").read()
        front, body = split_front(text)
        # drop old tags (inline or YAML list), write the new ones
        out, skipping = [], False
        for l in front:
            if l.startswith("tags:"):
                skipping = not l[5:].strip()
                continue
            if skipping and l.strip().startswith("- "):
                continue
            skipping = False
            out.append(l)
        if new_tags:
            out.append("tags: [%s]" % ", ".join(new_tags))
        os.makedirs(os.path.join(dest, folder), exist_ok=True)
        target = os.path.join(dest, folder, name)
        n = 2
        while os.path.exists(target):
            target = os.path.join(dest, folder, "%s %d.md" % (name[:-3], n)); n += 1
            stats["name_kollision"] += 1
        mtime = os.path.getmtime(src)
        with open(target, "w", encoding="utf-8") as f:
            f.write("---\n" + "\n".join(out) + "\n---\n" + body)
        os.utime(target, (mtime, mtime))
        os.remove(src)
        stats["verschoben_" + folder] += 1
    att = os.path.join(stage, "attachments")
    if os.path.isdir(att) and os.listdir(att):
        os.makedirs(os.path.join(dest, "attachments"), exist_ok=True)
        for f_ in os.listdir(att):
            shutil.move(os.path.join(att, f_), os.path.join(dest, "attachments", f_))
            stats["anhang_verschoben"] += 1
    left = len(os.listdir(os.path.join(stage, "notizen")))
    print("uebrig_in_import", left)
    if left == 0:
        shutil.rmtree(stage)
        print("import_ordner_entfernt 1")
    for k in sorted(stats):
        print(k, stats[k])
    return 0


if __name__ == "__main__":
    cmds = {"copy": lambda: cmd_copy(sys.argv[2], sys.argv[3]),
            "topics": lambda: cmd_topics(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 18),
            "sort": lambda: cmd_sort(sys.argv[2]),
            "apply": lambda: cmd_apply(sys.argv[2])}
    if len(sys.argv) >= 3 and sys.argv[1] in cmds:
        try:
            sys.exit(cmds[sys.argv[1]]())
        except Exception as e:  # never leak paths or content
            print("Fehler:", type(e).__name__)
            sys.exit(2)
    print(__doc__)
