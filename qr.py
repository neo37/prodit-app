#!/usr/bin/env python3
"""QR-код на скачивание APK.

    ./qr.py                  — постоянная ссылка https://hn.eyetrick.online/e.apk (всегда последняя версия)
    ./qr.py --versioned      — ссылка на конкретную сборку из dist/version.json
    ./qr.py --url https://…  — любая другая ссылка
    ./qr.py --base https://hn.eyetrick.online/app/

Результат: dist/qr.png (карточка в стиле сайта) + QR в терминале.
"""
import argparse
import json
import os
import sys

import qrcode
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "https://hn.eyetrick.online/app/"
PERMANENT = "https://hn.eyetrick.online/e.apk"
YELLOW, BLACK, WHITE = (255, 229, 0), (0, 0, 0), (255, 255, 255)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def card(url, title, subtitle, out):
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=2)
    qr.add_data(url)
    qr.make(fit=True)
    code = qr.make_image(fill_color=BLACK, back_color=WHITE).convert("RGB")

    pad, shadow, line = 40, 14, 6
    w = code.width + pad * 2
    h = code.height + pad * 2 + 170
    img = Image.new("RGB", (w + shadow + 40, h + shadow + 40), YELLOW)
    d = ImageDraw.Draw(img)
    x0, y0 = 20, 20
    d.rectangle([x0 + shadow, y0 + shadow, x0 + w + shadow, y0 + h + shadow], fill=BLACK)
    d.rectangle([x0, y0, x0 + w, y0 + h], fill=WHITE, outline=BLACK, width=line)

    ft, fs, fu = font(FONT, 44), font(FONT_REG, 24), font(FONT_REG, 18)
    d.text((x0 + w / 2, y0 + 36), title, font=ft, fill=BLACK, anchor="mt")
    d.text((x0 + w / 2, y0 + 96), subtitle, font=fs, fill=(90, 90, 90), anchor="mt")
    img.paste(code, (x0 + pad, y0 + 140))
    d.text((x0 + w / 2, y0 + h - 26), url, font=fu, fill=(90, 90, 90), anchor="mb")
    img.save(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", help="ссылка в QR (по умолчанию — постоянная)")
    ap.add_argument("--versioned", action="store_true", help="ссылка на конкретную версию, а не постоянная")
    ap.add_argument("--base", default=BASE, help="где на сервере лежит dist/ (по умолчанию %(default)s)")
    ap.add_argument("--out", default=os.path.join(HERE, "dist", "qr.png"))
    a = ap.parse_args()

    name = ""
    url = a.url
    if not url and not a.versioned:
        url = PERMANENT
    if not url:
        vj = os.path.join(HERE, "dist", "version.json")
        if not os.path.exists(vj):
            sys.exit("нет dist/version.json — сначала ./build.sh или передай --url")
        v = json.load(open(vj, encoding="utf-8"))
        url = a.base.rstrip("/") + "/" + v["apk"]
        name = v.get("versionName", "")

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    card(url, "HN Архив", f"Скачать для Android · v{name}" if name else "Скачать для Android", a.out)

    qr = qrcode.QRCode(border=1)
    qr.add_data(url)
    qr.print_ascii(invert=True)
    print(url)
    print("картинка:", a.out)


if __name__ == "__main__":
    main()
