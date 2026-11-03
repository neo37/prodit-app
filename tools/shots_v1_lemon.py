#!/usr/bin/env python3
"""Скриншоты для Google Play: экраны приложения в его стиле (Ui.java: #FFE500, рамка 2px, жёсткая тень),
строки — из tools/i18n.py, постеры — настоящие с magicbtn.online. 540×960 CSS × 2 = 1080×1920.

Запуск: python3 tools/shots.py  → play/screenshots/<lang>/NN-*.png
"""
import html
import importlib.util
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "play" / "shots-src"
OUT = ROOT / "play" / "screenshots"
spec = importlib.util.spec_from_file_location("i18n", ROOT / "tools" / "i18n.py")
i18n = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i18n)
LANGS = {"en": 0, "ru": 1, "sr": 2}

VIDEOS = [  # реальные файлы папки review на magicbtn.online
    ("review-series-film-bp3", "182 MB", 12, 4), ("studio-review-v2-bp3", "92.6 MB", 9, 3),
    ("gitlab-review-final-bp3", "56.5 MB", 7, 2), ("trending-vnedrenie-bp3", "29.0 MB", 5, 1),
    ("studio-review-bp3", "94.3 MB", 4, 0),
]


def T(lang, key, *a):
    s = i18n.S[key][LANGS[lang]]
    for k, v in enumerate(a, 1):
        s = s.replace(f"%{k}$s", str(v)).replace(f"%{k}$d", str(v))
    return html.escape(s)


CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{position:relative;width:540px;height:960px;overflow:hidden;font-family:Roboto,"Noto Sans",sans-serif;background:#FAFAF5;color:#000}
.sb{height:28px;background:#FAFAF5;display:flex;justify-content:space-between;align-items:center;padding:0 18px;font-size:13px;font-weight:500}
.btn{border:2px solid #000;background:#fff;box-shadow:3px 3px 0 #000;font-weight:700;padding:9px 14px;display:inline-block;font-size:15px}
.y{background:#FFE500}
.card{border:2px solid #000;box-shadow:3px 3px 0 #000;background:#fff;padding:13px 16px 15px;margin:0 3px 12px 0}
.card b{display:block;font-size:19px}.card span{color:#666;font-size:13px}
.wrap{padding:16px 16px}
h1{font-size:28px;font-weight:700}
.sub{color:#666;font-size:13px;margin:4px 0 16px}
.hdr{display:flex;align-items:center;gap:10px;padding:10px 16px 4px}
.hdr h2{font-size:20px;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.tabs{display:flex;gap:8px;padding:10px 16px 4px}.tabs .btn{font-size:13px;padding:7px 12px}
.info{color:#666;font-size:13px;padding:6px 16px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:6px 16px}
.tile .img{height:118px;border:2px solid #000;background:#222 center/cover;position:relative}
.tile .img i{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#FFE500;font-size:30px;font-style:normal;text-shadow:0 0 6px #000}
.tile b{display:block;font-size:13px;margin-top:4px;word-break:break-all}.tile span{font-size:11px;color:#666}
.player{position:absolute;inset:0;background:#000}
.player .frame{position:absolute;left:0;right:0;top:300px;height:304px;background:center/contain no-repeat}
.player .ttl{position:absolute;top:40px;left:16px;right:16px;color:#fff;font-size:15px;text-shadow:0 0 4px #000}
.soc{position:absolute;right:14px;top:380px;color:#fff;text-align:center;font-size:18px;line-height:1.25;text-shadow:0 0 6px #000}
.soc div{margin-bottom:18px}
.ctrl{position:absolute;left:0;right:0;bottom:0;background:rgba(30,30,30,.9);height:84px;color:#fff;display:flex;flex-direction:column;justify-content:center;padding:0 18px}
.ctrl .row{display:flex;justify-content:center;gap:52px;font-size:26px}
.ctrl .bar{height:4px;background:#555;margin-top:12px;position:relative}.ctrl .bar i{position:absolute;left:0;top:0;bottom:0;width:38%;background:#FFE500}
.msg{border:2px solid #000;box-shadow:2px 2px 0 #000;background:#fff;padding:10px 12px;margin:10px 40px 0 16px}
.msg.me{background:#FFE500;margin:10px 16px 0 56px}
.msg .w{font-size:12px;color:#666;font-weight:700}.msg .t{font-size:15px}
.msg .btn{font-size:13px;margin-top:6px;padding:6px 10px}
.input{position:absolute;left:0;right:0;bottom:0;background:#fff;padding:10px;display:flex;gap:8px;align-items:center;border-top:1px solid #ddd}
.input .f{flex:1;border-bottom:2px solid #FFE500;color:#999;padding:8px 4px;font-size:15px}
.dialog{position:absolute;inset:0;background:rgba(0,0,0,.45)}
.dialog .box{position:absolute;left:28px;right:28px;top:250px;background:#fff;border-radius:4px;padding:22px 22px 12px;box-shadow:0 10px 30px rgba(0,0,0,.4)}
.dialog h3{font-size:20px;margin-bottom:14px}.dialog .it{font-size:16px;padding:13px 0}
.dialog .act{display:flex;justify-content:flex-end;gap:26px;color:#7a6400;font-weight:700;font-size:14px;padding-top:10px;text-transform:uppercase}
.row2{display:flex;gap:8px;margin-top:10px}.row2 .btn{flex:none}
"""


def page(body, extra=""):
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}{extra}</style></head>
<body><div class="sb"><span>12:00</span><span>▾ ▴ ▮ 87%</span></div>{body}</body></html>"""


def poster(n):
    return f"img/{n}.jpg"


def s_home(l):
    phone = [("VID_20261012_181530", "1:12 · 48 MB", VIDEOS[3][0]), ("VID_20261010_094402", "0:37 · 21 MB", VIDEOS[1][0]),
             ("VID_20261007_200115", "2:05 · 96 MB", VIDEOS[2][0]), ("VID_20261003_131948", "0:58 · 37 MB", VIDEOS[4][0])]
    tiles = "".join(f'<div class="tile"><div class="img" style="background-image:url({poster(p)})"><i>▶</i></div>'
                    f'<b>{n}.mp4</b><span>{m}</span></div>' for n, m, p in phone)
    cam = {"en": "Camera", "ru": "Камера", "sr": "Камера"}[l]
    return page(f'''<div class="hdr"><span class="btn">☰</span><h2>{T(l, "on_phone")}</h2>
<span class="btn y">{T(l, "slideshow")}</span><span class="btn">⇅</span></div>
<div class="tabs"><span class="btn y">{T(l, "all")}</span><span class="btn">{cam}</span><span class="btn">WhatsApp</span></div>
<div class="info">{T(l, "count", 24)}</div><div class="grid">{tiles}</div>''')


def s_menu(l):
    items = [(T(l, "home_phone"), "y"), ("🎬  magicbtn.online  <span style='float:right'>📤</span>", ""),
             (T(l, "home_add"), ""), (T(l, "home_offline"), ""), (T(l, "home_my"), ""), (T(l, "home_chat"), ""),
             (T(l, "home_settings"), ""), ("🎨  " + T(l, "theme") + ": " + T(l, "theme_glass"), "")]
    rows = "".join(f'<div class="card {k}" style="padding:11px 12px"><b style="font-size:15px">{t}</b></div>' for t, k in items)
    name = {"en": "Anna", "ru": "Анна", "sr": "Ана"}[l]
    drawer = f'''<div style="position:absolute;left:0;top:0;bottom:0;width:390px;background:#FAFAF5;padding:44px 16px 16px;
box-shadow:4px 0 24px rgba(0,0,0,.35)"><div style="display:flex;align-items:center;gap:12px;margin-bottom:14px">
<div style="width:52px;height:52px;border:2px solid #000;background:#FFE500 url(img/{VIDEOS[0][0]}.jpg) center/cover"></div>
<b style="font-size:15px">{name}<br><span style="font-weight:400;color:#666">{T(l, "profile")}</span></b></div>{rows}</div>'''
    return s_home(l).replace("</body>", f'<div class="dialog" style="background:rgba(0,0,0,.4)"></div>{drawer}</body>')


def s_glass(l):
    # фон — настоящий кадр 3D-сцены темы «Аврора» (assets/glass/index.html)
    css = """body{background:#120E33 url(img/aurora.jpg) center/cover;color:#fff}
.sb{background:transparent;color:#fff}
.btn{background:rgba(255,255,255,.15);border:1px solid rgba(255,255,255,.35);border-radius:22px;box-shadow:0 4px 0 rgba(0,0,0,.2);color:#fff;backdrop-filter:blur(14px)}
.y{background:rgba(124,136,252,.85);color:#fff}
.tile .img{border:1px solid rgba(255,255,255,.35);border-radius:22px;box-shadow:0 6px 18px rgba(0,0,0,.35)}
.tile span,.info{color:#C9C9E6}.tile b{color:#fff}.hdr,.tabs,.grid,.info{position:relative;z-index:1}"""
    return s_list(l).replace("</style>", css + "</style>")


def s_list(l):
    tiles = "".join(
        f'<div class="tile"><div class="img" style="background-image:url({poster(n)})"><i>▶</i></div>'
        f'<b>{n}.mp4</b><span>{size} · ♥ {lk} · 💬 {cm}</span></div>' for n, size, lk, cm in VIDEOS[:4])
    return page(f'''<div class="hdr"><span class="btn">‹</span><h2>🎬 magicbtn.online</h2>
<span class="btn y">{T(l, "slideshow")}</span><span class="btn">⇅</span></div>
<div class="tabs"><span class="btn y">{T(l, "all")}</span><span class="btn">review</span><span class="btn">files</span></div>
<div class="info">{T(l, "count", 6)}</div><div class="grid">{tiles}</div>''')


def s_player(l):
    n = VIDEOS[1][0]
    return page(f'''<div class="player"><div class="frame" style="background-image:url({poster(n)})"></div>
<div class="ttl">2 / 6 · {n}.mp4</div>
<div class="soc"><div style="color:#FFE500">♥<br>10</div><div>💬<br>3</div><div>🚩</div></div>
<div class="ctrl"><div class="row"><span>⏮</span><span>⏸</span><span>⏭</span></div><div class="bar"><i></i></div></div></div>''')


def s_comments(l):
    demo = {
        "en": [("Anna", "Great overview, thanks!"), ("Mark", "Watched it offline on the train 👍")],
        "ru": [("Анна", "Отличный обзор, спасибо!"), ("Марк", "Смотрел офлайн в электричке 👍")],
        "sr": [("Ана", "Одличан преглед, хвала!"), ("Марко", "Гледао сам офлајн у возу 👍")],
    }[l]
    items = "".join(f'<div style="margin-bottom:12px"><b style="font-size:13px">{a}</b>'
                    f'<div style="font-size:15px">{b}</div></div>' for a, b in demo)
    return s_player(l).replace("</body>", f'''<div class="dialog"><div class="box"><h3>{T(l, "comments_title")}</h3>{items}
<div style="border-bottom:2px solid #FFE500;color:#999;padding:8px 0;margin-top:6px">{T(l, "comment_hint")}</div>
<div class="act"><span>{T(l, "report")}</span><span>{T(l, "close")}</span><span>{T(l, "send")}</span></div></div></div></body>''')


def s_chat(l):
    demo = {
        "en": [("Anna", "Hi all! Where can I find the BP3 review videos?", None, False),
               ("Mark", "Here is my source — 3 short clips", "My clips", False),
               ("You", "Thanks, added it 🙌", None, True)],
        "ru": [("Анна", "Всем привет! Где посмотреть ролики про BP3?", None, False),
               ("Марк", "Вот мой источник — 3 коротких видео", "Мои ролики", False),
               ("Вы", "Спасибо, подключил 🙌", None, True)],
        "sr": [("Ана", "Здраво свима! Где могу да гледам BP3 снимке?", None, False),
               ("Марко", "Ево мог извора — 3 кратка снимка", "Моји снимци", False),
               ("Ви", "Хвала, додао сам 🙌", None, True)],
    }[l]
    msgs = ""
    for i, (who, text, share, me) in enumerate(demo):
        btn = f'<div class="btn">＋ {T(l, "chat_add_source", share)}</div>' if share else ""
        msgs += f'<div class="msg{" me" if me else ""}"><div class="w">{who}  12:0{i + 1}</div><div class="t">{text}</div>{btn}</div>'
    return page(f'''<div class="hdr"><span class="btn">‹</span><h2>{T(l, "chat_title")}</h2></div>
<div class="info">{T(l, "chat_rules_short")}</div>{msgs}
<div class="input"><span class="btn">📎</span><span class="f">{T(l, "message_hint")}</span><span class="btn y">➤</span></div>''')


def s_my(l):
    files = "".join(f'<div style="display:flex;align-items:center;margin-top:10px"><span style="flex:1">▶ {n}.mp4 · {s}</span>'
                    f'<span class="btn">🗑</span></div>' for n, s in [("20261016_clip_1", "41.2 MB"), ("20261016_clip_2", "28.7 MB")])
    name = {"en": "My clips", "ru": "Мои ролики", "sr": "Моји снимци"}[l]
    return page(f'''<div class="hdr"><span class="btn">‹</span><h2>{T(l, "my_source")}</h2></div>
<div class="wrap"><div class="sub">{T(l, "my_source_count", 2, 3, 3)}</div>
<b style="font-size:18px">🎬 {name}</b>{files}
<div class="btn y" style="display:block;margin-top:16px;text-align:center">{T(l, "my_source_upload")}</div>
<div class="btn" style="display:block;margin-top:10px;text-align:center">{T(l, "my_source_share")}</div></div>''')


def s_offline(l):
    tiles = "".join(
        f'<div class="tile"><div class="img" style="background-image:url({poster(n)})"><i>▶</i></div>'
        f'<b>{n}.mp4</b><span>{T(l, "offline_meta")}</span></div>' for n, *_ in VIDEOS[1:5])
    return page(f'''<div class="hdr"><span class="btn">{T(l, "home")}</span><h2></h2></div>
<div class="wrap" style="padding-bottom:0"><h1 style="font-size:22px">{T(l, "gallery_title")}</h1>
<div class="tabs" style="padding:10px 0 4px"><span class="btn y">magicbtn.online/review</span><span class="btn">{T(l, "all_media")}</span></div>
<div class="sub">{T(l, "gallery_counts", 0, 4)}</div>
<div class="row2"><span class="btn y" style="flex:1;text-align:center">{T(l, "slideshow")}</span><span class="btn">🔀</span><span class="btn">5 s</span><span class="btn">⇅</span></div></div>
<div class="grid" style="margin-top:8px">{tiles}</div>''')


SCREENS = [("01-aurora", s_glass), ("02-phone", s_home), ("03-menu", s_menu), ("04-source", s_list), ("05-player", s_player),
           ("06-comments", s_comments), ("07-chat", s_chat), ("08-my-source", s_my)]

for lang in LANGS:
    (OUT / lang).mkdir(parents=True, exist_ok=True)
    for name, fn in SCREENS:
        src = SRC / f"{lang}-{name}.html"
        src.write_text(fn(lang), encoding="utf-8")
        png = OUT / lang / f"{name}.png"
        subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=2", "--window-size=540,1200",
                        f"--screenshot={png}", src.as_uri()], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        # headless-окно ниже заданного на высоту рамки — рендерим выше и обрезаем ровно до 1080×1920
        from PIL import Image
        Image.open(png).convert("RGB").crop((0, 0, 1080, 1920)).save(png)
    print(lang, "готово")
