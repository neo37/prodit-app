#!/usr/bin/env python3
"""Скриншоты для Google Play (3.6, тема «Консоль»): HTML-копии экранов в стиле приложения — графит, янтарь,
шрифт Onest, значки Material Symbols (та же таблица, что в Icons.java), строки — из tools/i18n.py,
постеры — настоящие с magicbtn.online, кадр игры — снимок встроенной игры. 540×960 CSS × 2 = 1080×1920.
Прошлая версия (тема «Лимон») — tools/shots_v1_lemon.py.

Запуск: python3 tools/shots.py  → play/screenshots/<lang>/NN-*.png
"""
import html
import importlib.util
import re
import subprocess
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "play" / "shots-src"
OUT = ROOT / "play" / "screenshots"
spec = importlib.util.spec_from_file_location("i18n", ROOT / "tools" / "i18n.py")
i18n = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i18n)
LANGS = {"en": 0, "ru": 1, "sr": 2}

VIDEOS = [("review-series-film-bp3", "182 MB", 12, 4), ("studio-review-v2-bp3", "92.6 MB", 9, 3),
          ("gitlab-review-final-bp3", "56.5 MB", 7, 2), ("trending-vnedrenie-bp3", "29.0 MB", 5, 1),
          ("studio-review-bp3", "94.3 MB", 4, 0)]

# эмодзи → значок Material Symbols: та же таблица, что в приложении
_java = (ROOT / "src/online/magicbtn/player/Icons.java").read_text(encoding="utf-8")
_pairs = re.findall(r'"((?:\\u[0-9a-fA-F]{4}|[^"\\])+)", "\\u([0-9a-fA-F]{4})"', _java)
ICON = {k.encode("latin-1", "backslashreplace").decode("unicode_escape").encode("utf-16", "surrogatepass").decode("utf-16")
        if "\\u" in k else k: v for k, v in _pairs}
FILLED = set("♥⏺★")


def ic(text):
    """Эмодзи в тексте → значки (как в приложении)."""
    out = []
    for ch in text:
        if ch in ICON:
            cls = "ic f" if ch in FILLED else "ic"
            out.append(f'<span class="{cls}">&#x{ICON[ch]};</span>')
        elif ch != "️":
            out.append(ch)
    return "".join(out)


def T(lang, key, *a):
    s = i18n.S[key][LANGS[lang]]
    for k, v in enumerate(a, 1):
        s = s.replace(f"%{k}$s", str(v)).replace(f"%{k}$d", str(v))
    return ic(html.escape(s))


F = "../../assets/fonts/"
CSS = f"""
@font-face{{font-family:Onest;src:url({F}Onest-Regular.ttf);font-weight:400}}
@font-face{{font-family:Onest;src:url({F}Onest-Medium.ttf);font-weight:500 700}}
@font-face{{font-family:Ic;src:url({F}Icons.ttf)}}@font-face{{font-family:IcF;src:url({F}IconsFilled.ttf)}}
:root{{--bg:#0D1013;--card:#15191C;--ink:#ECEFF2;--gray:#9DA2A8;--line:#2D3135;--acc:#F7B83D;--on:#1C0E00;--red:#FF5A5F}}
*{{box-sizing:border-box;margin:0;padding:0}}
body{{position:relative;width:540px;height:960px;overflow:hidden;font-family:Onest,sans-serif;background:var(--bg);color:var(--ink);font-size:16px;line-height:1.4}}
.ic{{font-family:Ic;font-weight:400;font-size:1.2em;line-height:1;vertical-align:-0.2em;display:inline-block}}.ic.f{{font-family:IcF}}
.sb{{height:28px;display:flex;justify-content:space-between;align-items:center;padding:0 18px;font-size:13px;color:var(--gray)}}
.btn{{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:44px;min-width:44px;padding:0 14px;border:1px solid var(--line);
border-radius:6px;background:var(--card);color:var(--ink);font-weight:500;font-size:15px}}
.y{{background:var(--acc);border-color:var(--acc);color:var(--on)}}
.hdr{{display:flex;align-items:center;gap:8px;padding:12px 16px}}
.hdr h2{{font-size:22px;font-weight:500;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
.chips{{display:flex;gap:6px;padding:4px 16px 12px}}.chip{{padding:8px 14px;border:1px solid var(--line);border-radius:6px;font-size:15px;font-weight:500;color:var(--gray)}}
.chip.on{{color:var(--ink);border-color:var(--gray)}}
.info{{color:var(--gray);font-size:14px;padding:0 16px 8px}}
.grid{{display:grid;grid-template-columns:1fr 1fr;gap:20px 12px;padding:8px 16px}}
.tile .img{{height:110px;border:1px solid var(--line);border-radius:10px;background:#06080B center/cover;position:relative}}
.tile .img i{{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--acc);font-size:30px;font-style:normal;text-shadow:0 0 8px #000}}
.tile b{{display:block;font-size:15px;font-weight:500;margin-top:8px;word-break:break-all;line-height:1.3}}.tile span{{font-size:14px;color:var(--gray)}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:10px}}
.msg{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin:12px 56px 0 16px}}
.msg.me{{background:var(--acc);border-color:var(--acc);color:var(--on);margin:12px 16px 0 56px}}
.msg .w{{font-size:13px;color:var(--gray);font-weight:500}}.msg.me .w{{color:#5a3d00}}.msg .t{{font-size:16px}}
.msg .btn{{margin-top:8px;min-height:38px;font-size:14px}}
.input{{position:absolute;left:0;right:0;bottom:0;background:var(--card);padding:10px 12px;display:flex;gap:8px;align-items:center;border-top:1px solid var(--line)}}
.input .f{{flex:1;border:1px solid var(--line);border-radius:6px;color:var(--gray);padding:11px 12px;font-size:15px}}
"""


def page(body, extra=""):
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}{extra}</style></head>
<body><div class="sb"><span>12:00</span><span>87%</span></div>{body}</body></html>"""


def poster(n):
    return f"img/{n}.jpg"


def s_home(l):
    return page(f'''<div class="hdr"><h2 style="font-size:24px">{T(l, "app_name")}</h2><span class="btn">{ic("☰")}</span></div>
<div class="card" style="margin:16px 16px 0;height:380px;display:flex;flex-direction:column;align-items:center;justify-content:center">
<div style="font-size:60px">{ic("💬")}</div><div style="font-size:30px;font-weight:500">{T(l, "home2_chat")}</div>
<div style="color:var(--red);margin-top:6px">{T(l, "home2_chat_new", 3)}</div>
<span class="btn" style="margin-top:18px">{ic("✉")} {T(l, "home2_dm")}  • 1</span></div>
<div style="display:flex;gap:12px;margin:12px 16px 0;height:230px">
<div class="card" style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center"><div style="font-size:44px;color:var(--acc)">{ic("▶")}</div>
<div style="font-size:20px;font-weight:500;margin-top:8px">{T(l, "home2_player")}</div></div>
<div class="card" style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center"><div style="font-size:44px;color:var(--acc)">{ic("🎲")}</div>
<div style="font-size:20px;font-weight:500;margin-top:8px">{T(l, "home2_game")}</div></div></div>''')


def s_chat(l):
    demo = {
        "en": [("Anna", "Hi all! Who watched the new BP3 review?", None), ("Mark", "Shared it — 3 short clips 👇", "watch"),
               ("Anna", "📞 Call me?", "call"), ("You", "Joining now 🙌", "me")],
        "ru": [("Анна", "Всем привет! Кто смотрел новый обзор BP3?", None), ("Марк", "Поделился — 3 коротких видео 👇", "watch"),
               ("Анна", "📞 Созвонимся?", "call"), ("Вы", "Захожу 🙌", "me")],
        "sr": [("Ана", "Здраво свима! Ко је гледао нови BP3 преглед?", None), ("Марко", "Поделио сам — 3 кратка снимка 👇", "watch"),
               ("Ана", "📞 Да се чујемо?", "call"), ("Ви", "Улазим 🙌", "me")],
    }[l]
    msgs = ""
    for i, (who, text, kind) in enumerate(demo):
        btn = ""
        if kind == "watch":
            btn = f'<span class="btn y">{ic("▶")} {T(l, "chat_watch")}</span>'
        if kind == "call":
            btn = f'<span class="btn y">{ic("📞")} {T(l, "call_join")}</span>'
        msgs += (f'<div class="msg{" me" if kind == "me" else ""}"><div class="w">{who}  12:0{i + 1}</div>'
                 f'<div class="t">{ic(html.escape(text))}</div>{btn}</div>')
    return page(f'''<div class="hdr"><span class="btn">‹</span><h2>{T(l, "chat_title")}</h2><span class="btn">{ic("✉")}</span></div>
<div class="info">{T(l, "chat_rules_short")}</div>{msgs}
<div class="input"><span class="btn">{ic("📎")}</span><span class="f">{T(l, "message_hint")}</span><span class="btn y">{ic("➤")}</span></div>''')


def s_game(l):
    return page("", "body{background:#0b5a7c url(img/game.png) top center/540px auto no-repeat}.sb{display:none}")


def s_phone(l):
    phone = [("VID_20261012_181530", "1:12 · 48 MB", VIDEOS[3][0]), ("VID_20261010_094402", "0:37 · 21 MB", VIDEOS[1][0]),
             ("VID_20261007_200115", "2:05 · 96 MB", VIDEOS[2][0]), ("VID_20261003_131948", "0:58 · 37 MB", VIDEOS[4][0])]
    tiles = "".join(f'<div class="tile"><div class="img" style="background-image:url({poster(p)})"><i>{ic("▶")}</i></div>'
                    f'<b>{n}.mp4</b><span>{m}</span></div>' for n, m, p in phone)
    cam = {"en": "Camera", "ru": "Камера", "sr": "Камера"}[l]
    return page(f'''<div class="hdr"><span class="btn" style="color:var(--red)">{ic("☰")} 3</span><h2>{T(l, "on_phone")}</h2>
<span class="btn y">{T(l, "slideshow")}</span><span class="btn">{ic("⇅")}</span></div>
<div class="chips"><span class="chip on">{T(l, "all")}</span><span class="chip">{cam}</span><span class="chip">WhatsApp</span></div>
<div class="info">{T(l, "count", 24)}</div><div class="grid">{tiles}</div>''')


def s_player(l):
    n = VIDEOS[1][0]
    side = "".join(f'<div style="margin-bottom:20px">{x}</div>' for x in
                   [f'<span style="color:var(--acc)">{ic("♥")}</span><br>10', f'{ic("💬")}<br>3', ic("↗"), ic("👎"), ic("🚩")])
    return page(f'''<div style="position:absolute;inset:0;background:#000">
<div style="position:absolute;left:0;right:0;top:300px;height:304px;background:url({poster(n)}) center/contain no-repeat"></div>
<div style="position:absolute;top:36px;left:12px;right:12px;display:flex;align-items:center;gap:10px;color:#fff">
<span style="font-size:24px">{ic("✕")}</span><span style="flex:1;font-size:15px">2 / 6 · {n}.mp4</span>
<span style="color:var(--red);font-weight:500">{T(l, "rec_on")}</span><span style="font-size:24px">{ic("⋮")}</span></div>
<div style="position:absolute;right:16px;top:360px;color:#fff;text-align:center;font-size:20px;line-height:1.2">{side}</div>
<div style="position:absolute;left:0;right:0;bottom:0;height:84px;background:rgba(21,25,28,.95);display:flex;flex-direction:column;justify-content:center;padding:0 18px">
<div style="display:flex;justify-content:center;gap:52px;font-size:26px;color:#fff">⏮ ⏸ ⏭</div>
<div style="height:4px;background:#2D3135;margin-top:12px;position:relative"><i style="position:absolute;left:0;top:0;bottom:0;width:38%;background:var(--acc)"></i></div></div></div>''')


def s_source(l):
    tiles = "".join(
        f'<div class="tile"><div class="img" style="background-image:url({poster(n)})"><i>{ic("▶")}</i></div>'
        f'<b>{n}.mp4</b><span>{size} · {ic("♥")} {lk} · {ic("💬")} {cm}</span></div>' for n, size, lk, cm in VIDEOS[:4])
    return page(f'''<div class="hdr"><span class="btn">{ic("☰")}</span><h2>{ic("🎬")} magicbtn.online</h2>
<span class="btn y">{T(l, "slideshow")}</span><span class="btn">{ic("📁")}</span></div>
<div class="chips"><span class="chip on">{T(l, "all")}</span><span class="chip">review</span><span class="chip">files</span></div>
<div class="info">{T(l, "count", 6)}</div><div class="grid">{tiles}</div>''')


def s_views(l):
    demo = [("magicbtn.online", "02.11.2026 21:40", 14, "6:12", 2, True), (T(l, "on_phone"), "01.11.2026 19:05", 8, "3:47", 0, False)]
    cards = ""
    for name, when, clips, dur, ex, done in demo:
        open_btn = f'<span class="btn" style="margin-left:8px">{T(l, "rec_open")}</span>' if done else ""
        cards += (f'<div class="card" style="margin:0 16px 12px;padding:16px"><div style="font-size:17px;font-weight:500">{name} · {when}</div>'
                  f'<div style="color:var(--gray);font-size:14px;margin:4px 0 12px">{T(l, "rec_meta", clips, dur, ex)}</div>'
                  f'<span class="btn y">{T(l, "rec_make")}</span>{open_btn}</div>')
    return page(f'''<div class="hdr"><span class="btn">‹</span><h2>{T(l, "rec_title")}</h2></div>
<div class="info" style="padding-bottom:16px">{T(l, "rec_hint")}</div>{cards}''')


def s_menu(l):
    items = [T(l, "home2_menu"), T(l, "home_phone"), ic("🎬") + "  magicbtn.online", T(l, "home_add"), T(l, "host_menu"),
             T(l, "home_offline"), T(l, "rec_menu"), T(l, "game_menu"), T(l, "home_chat"), T(l, "home_settings")]
    rows = "".join(f'<div class="card" style="padding:12px 14px;margin-bottom:8px;font-weight:500">{t}</div>' for t in items)
    name = {"en": "Anna", "ru": "Анна", "sr": "Ана"}[l]
    drawer = f'''<div style="position:absolute;left:0;top:0;bottom:0;width:400px;background:var(--bg);padding:44px 16px 16px;
border-right:1px solid var(--line)"><div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
<div style="width:52px;height:52px;border-radius:50%;border:1px solid var(--line);background:url(img/{VIDEOS[0][0]}.jpg) center/cover"></div>
<div style="font-weight:500">{name}<br><span style="font-weight:400;color:var(--gray);font-size:14px">{T(l, "profile")}</span></div></div>{rows}</div>'''
    return s_phone(l).replace("</body>", f'<div style="position:absolute;inset:0;background:rgba(0,0,0,.55)"></div>{drawer}</body>')


SCREENS = [("01-home", s_home), ("02-chat", s_chat), ("03-game", s_game), ("04-phone", s_phone),
           ("05-player", s_player), ("06-source", s_source), ("07-views", s_views), ("08-menu", s_menu)]

if __name__ == "__main__":
    for lang in LANGS:
        d = OUT / lang
        d.mkdir(parents=True, exist_ok=True)
        for old in d.glob("*.png"):
            old.unlink()
        for name, fn in SCREENS:
            src = SRC / f"{lang}-{name}.html"
            src.write_text(fn(lang), encoding="utf-8")
            png = d / f"{name}.png"
            subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                            "--force-device-scale-factor=2", "--window-size=540,1200", "--virtual-time-budget=3000",
                            f"--screenshot={png}", src.as_uri()], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
            # headless-окно ниже заданного на высоту рамки — рендерим выше и обрезаем ровно до 1080×1920
            Image.open(png).convert("RGB").crop((0, 0, 1080, 1920)).save(png)
        print(lang, "готово")
