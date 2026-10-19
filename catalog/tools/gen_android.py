#!/usr/bin/env python3
"""Generate the Android sources for the core exercise catalogue.

catalog/core.json is the source of truth. This script writes:

  app/src/main/kotlin/io/github/kanylbullen/tung/data/SeedExercises.kt
  app/src/main/res/values/strings.xml          (en, only between the catalog markers)
  app/src/main/res/values-<lang>/strings.xml   (<lang>, only between the catalog markers)

English is the default locale and the fallback: every entry needs name.en, and
SeedExercises.kt stores the English name in `name`, which the app shows when no
`ex_<key>` resource matches. Today the only translation is values-sv.

A translation takes part when its values-<lang>/strings.xml contains the catalog
markers; <lang> is the directory qualifier and the key in `name`. Entries without
name.<lang> are left out of that file, so Android falls back to English for them.

Run it from anywhere:  python3 -I catalog/tools/gen_android.py
With --check it writes nothing and exits 1 if any file is out of date.
Output is deterministic: running it twice in a row changes nothing.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "catalog" / "core.json"
KOTLIN = ROOT / "app/src/main/kotlin/io/github/kanylbullen/tung/data/SeedExercises.kt"
RES = ROOT / "app/src/main/res"

# Same order as core/src/main/kotlin/io/github/kanylbullen/tung/core/Catalog.kt;
# the muscle-group order is the order in the Kotlin list.
MUSCLE_GROUPS = ["chest", "back", "shoulders", "biceps", "triceps", "legs", "glutes", "core", "fullbody"]
EQUIPMENT = ["barbell", "dumbbell", "machine", "cable", "bodyweight", "other"]
# The language of values/ and the fallback name; every entry must have it.
DEFAULT_LANGUAGE = "en"

BEGIN = "<!-- catalog:begin -->"
END = "<!-- catalog:end -->"
HEADER = "Generated from catalog/core.json, do not edit. Run catalog/tools/gen_android.py."


def string_files():
    """values/strings.xml is the default language; each values-<lang>/strings.xml with the markers is <lang>."""
    files = {DEFAULT_LANGUAGE: RES / "values" / "strings.xml"}
    for directory in sorted(RES.glob("values-*")):
        path = directory / "strings.xml"
        if not path.is_file() or BEGIN not in path.read_text(encoding="utf-8"):
            continue
        lang = directory.name[len("values-"):]
        if lang in files:
            sys.exit(f"{path}: {lang} is already {files[lang].relative_to(ROOT)}")
        files[lang] = path
    return files


def load_core():
    entries = json.loads(CORE.read_text(encoding="utf-8"))
    seen = set()
    for e in entries:
        key = e["key"]
        if not key or not all(c.islower() or c.isdigit() or c == "_" for c in key):
            sys.exit(f"core.json: bad key {key!r}")
        if key in seen:
            sys.exit(f"core.json: duplicate key {key!r}")
        seen.add(key)
        if not e["name"].get(DEFAULT_LANGUAGE, "").strip():
            sys.exit(f"core.json: {key} has no {DEFAULT_LANGUAGE} name")
        for lang, name in e["name"].items():
            if not name.strip():
                sys.exit(f"core.json: {key} has an empty {lang} name")
        if e["muscleGroup"] not in MUSCLE_GROUPS:
            sys.exit(f"core.json: {key} has unknown muscleGroup {e['muscleGroup']!r}")
        if e["equipment"] not in EQUIPMENT:
            sys.exit(f"core.json: {key} has unknown equipment {e['equipment']!r}")
    # Grouped by muscle group, then by key: the order of the generated list.
    return sorted(entries, key=lambda e: (MUSCLE_GROUPS.index(e["muscleGroup"]), e["key"]))


def kotlin_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def android_string(s):
    out = (
        s.replace("\\", "\\\\")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace('"', '\\"')
    )
    if out[:1] in ("@", "?"):
        out = "\\" + out
    return out


def render_kotlin(entries):
    muscles = sorted({e["muscleGroup"] for e in entries})
    equipment = sorted({e["equipment"] for e in entries})
    lines = [
        f"// {HEADER}",
        "",
        "package io.github.kanylbullen.tung.data",
        "",
        "import io.github.kanylbullen.tung.core.Equipment",
    ]
    lines += [f"import io.github.kanylbullen.tung.core.Equipment.{q.upper()}" for q in equipment]
    lines.append("import io.github.kanylbullen.tung.core.MuscleGroup")
    lines += [f"import io.github.kanylbullen.tung.core.MuscleGroup.{m.upper()}" for m in muscles]
    lines += [
        "",
        "/** A catalogue exercise: a stable [key] for `ex_<key>` and the English [name] as fallback. */",
        "internal data class SeedExercise(",
        "    val key: String,",
        "    val name: String,",
        "    val muscle: MuscleGroup,",
        "    val equipment: Equipment,",
        ")",
        "",
        "/**",
        " * The starting catalogue, generated from `catalog/core.json`. What the user sees",
        " * comes from the string resource `ex_<key>` in `values/strings.xml` (English)",
        " * and `values-sv/strings.xml` (Swedish), generated from the same file;",
        " * [SeedExercise.name] is stored as the English fallback. To add an entry, edit",
        " * `catalog/core.json` and run `catalog/tools/gen_android.py`.",
        " */",
        "internal object SeedExercises {",
        "    val all: List<SeedExercise> = listOf(",
    ]
    previous = None
    for e in entries:
        if previous is not None and e["muscleGroup"] != previous:
            lines.append("")
        previous = e["muscleGroup"]
        lines.append(
            f"        SeedExercise({kotlin_string(e['key'])}, {kotlin_string(e['name'][DEFAULT_LANGUAGE])}, "
            f"{e['muscleGroup'].upper()}, {e['equipment'].upper()}),"
        )
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def render_strings(text, entries, lang, path):
    begin = text.find(BEGIN)
    end = text.find(END)
    if begin < 0 or end < begin or text.count(BEGIN) != 1 or text.count(END) != 1:
        sys.exit(f"{path}: needs exactly one {BEGIN} followed by one {END}")
    line_start = text.rfind("\n", 0, begin) + 1
    indent = text[line_start:begin]
    block = [BEGIN, f"<!-- {HEADER} -->"]
    block += [
        f'<string name="ex_{e["key"]}">{android_string(e["name"][lang])}</string>' for e in entries if lang in e["name"]
    ]
    block.append(END)
    return text[:line_start] + "\n".join(indent + b for b in block) + text[end + len(END):]


def main():
    check = "--check" in sys.argv[1:]
    entries = load_core()
    outputs = {KOTLIN: render_kotlin(entries)}
    for lang, path in string_files().items():
        outputs[path] = render_strings(path.read_text(encoding="utf-8"), entries, lang, path)
    stale = []
    for path, content in outputs.items():
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current == content:
            continue
        stale.append(path)
        if not check:
            path.write_bytes(content.encode("utf-8"))
    for path in stale:
        print(("out of date: " if check else "wrote ") + str(path.relative_to(ROOT)))
    if not stale:
        print(f"up to date ({len(entries)} exercises)")
    if check and stale:
        sys.exit(1)


if __name__ == "__main__":
    main()
