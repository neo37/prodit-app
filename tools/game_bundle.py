#!/usr/bin/env python3
"""Игра «Хвосты» (~/infra/alisa) → assets/game приложения: только то, что она грузит.
Импорты JS разбираются рекурсивно ('three' и 'three/addons/…' — по importmap игры).
Запуск: python3 tools/game_bundle.py [путь к игре]"""
import re
import shutil
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else Path.home() / "infra/alisa").resolve()
DST = Path(__file__).resolve().parents[1] / "assets" / "game"
STATIC = ["index.html", "play.html", "style.css", "play.css", "assets/favicon.svg", "assets/map_new.jpg",
          "assets/height_map_new.jpg", "models/maskot.web.glb", "models/tiny-dancer.opt.glb",
          "viewer/own.html", "viewer/viewer.css",
          # уровень «Замок» и экран выбора на старте
          "start.html", "assets/preview-tails.jpg", "assets/castle/preview.jpg",
          "assets/castle/stage1.glb", "assets/castle/stage2.glb", "assets/castle/stage3.glb",
          "assets/castle/stage4.glb"]
ENTRY = ["src/kid-td.js", "src/levels.js", "src/lore.js", "viewer/own.js", "viewer/scene.js"]
IMP = re.compile(r"""(?:import|export)\s[^'"]*?from\s*['"]([^'"]+)['"]|import\s*['"]([^'"]+)['"]""")


def resolve(spec, base):
    if spec == "three":
        return SRC / "vendor/three/build/three.module.js"
    if spec.startswith("three/addons/"):
        return SRC / "vendor/three/examples/jsm" / spec[len("three/addons/"):]
    return (base.parent / spec).resolve()


def main():
    if DST.exists():
        shutil.rmtree(DST)
    seen, todo = set(), [SRC / e for e in ENTRY]
    while todo:
        f = todo.pop()
        if f in seen or not f.is_file():
            continue
        seen.add(f)
        for m in IMP.finditer(f.read_text(encoding="utf-8", errors="ignore")):
            spec = m.group(1) or m.group(2)
            todo.append(resolve(spec, f))
    files = {SRC / s for s in STATIC} | seen
    total = 0
    for f in sorted(files):
        rel = f.relative_to(SRC)
        out = DST / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, out)
        total += f.stat().st_size
    # с главного экрана приложения (GameActivity открывает continue.html) — экран выбора:
    # «Набережная» (последнее открытое место, tails.unlocked) или «Замок»; прошлый выбор подсвечен
    (DST / "continue.html").write_text('<!doctype html><meta charset="utf-8"><script>'
        'location.replace("start.html");</script>', encoding="utf-8")
    # в приложении не нужны ссылка на версию td/ и подсказка про локальный сервер
    idx = DST / "index.html"
    idx.write_text(idx.read_text(encoding="utf-8").replace("</head>",
                   "<style>.footer-note{display:none}</style></head>", 1), encoding="utf-8")
    print(f"{len(files)} файлов, {total / 1e6:.1f} МБ → {DST}")


if __name__ == "__main__":
    main()
