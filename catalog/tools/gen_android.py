#!/usr/bin/env python3
"""Generate the Android sources for the core exercise catalogue.

catalog/core.json is the source of truth. This script writes:

  app/src/main/kotlin/io/github/kanylbullen/tung/data/SeedExercises.kt
  app/src/main/res/values/strings.xml      (en, only between the catalog markers)
  app/src/main/res/values-sv/strings.xml   (sv, only between the catalog markers)

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
STRINGS = {
    "en": ROOT / "app/src/main/res/values/strings.xml",
    "sv": ROOT / "app/src/main/res/values-sv/strings.xml",
}

# Same order as domain/Catalog.kt; the muscle-group order is the order in the Kotlin list.
MUSCLE_GROUPS = ["chest", "back", "shoulders", "biceps", "triceps", "legs", "glutes", "core", "fullbody"]
EQUIPMENT = ["barbell", "dumbbell", "machine", "cable", "bodyweight", "other"]
LANGUAGES = ["sv", "en"]

BEGIN = "<!-- catalog:begin -->"
END = "<!-- catalog:end -->"
HEADER = "Genererad ur catalog/core.json, redigera inte. Kör catalog/tools/gen_android.py."


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
        for lang in LANGUAGES:
            if not e["name"].get(lang, "").strip():
                sys.exit(f"core.json: {key} has no {lang} name")
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
        "import io.github.kanylbullen.tung.domain.Equipment",
    ]
    lines += [f"import io.github.kanylbullen.tung.domain.Equipment.{q.upper()}" for q in equipment]
    lines.append("import io.github.kanylbullen.tung.domain.MuscleGroup")
    lines += [f"import io.github.kanylbullen.tung.domain.MuscleGroup.{m.upper()}" for m in muscles]
    lines += [
        "",
        "/** A catalogue exercise: a stable [key] for `ex_<key>` and the Swedish [name] as fallback. */",
        "internal data class SeedExercise(",
        "    val key: String,",
        "    val name: String,",
        "    val muscle: MuscleGroup,",
        "    val equipment: Equipment,",
        ")",
        "",
        "/**",
        " * The starting catalogue, generated from `catalog/core.json`. What the user sees",
        " * comes from the string resource `ex_<key>` in `values/strings.xml` and",
        " * `values-en/strings.xml`, generated from the same file; [SeedExercise.name] is",
        " * stored as the Swedish fallback. To add an entry, edit `catalog/core.json` and",
        " * run `catalog/tools/gen_android.py`.",
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
            f"        SeedExercise({kotlin_string(e['key'])}, {kotlin_string(e['name']['sv'])}, "
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
    block += [f'<string name="ex_{e["key"]}">{android_string(e["name"][lang])}</string>' for e in entries]
    block.append(END)
    return text[:line_start] + "\n".join(indent + b for b in block) + text[end + len(END):]


def main():
    check = "--check" in sys.argv[1:]
    entries = load_core()
    outputs = {KOTLIN: render_kotlin(entries)}
    for lang, path in STRINGS.items():
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
