#!/usr/bin/env python3
"""Smart Player — источник видео на своём сервере (Python 3.8+, Flask). Аналог video_api.php.

Все видео и фото из папки ROOT и всех вложенных папок появятся в приложении «Умный плеер»,
каждая папка — отдельным разделом. Действия те же: folders, list, stream (с перемоткой).

Запуск:
    pip install flask
    python3 video_api.py /путь/к/видео            # слушает 127.0.0.1:8090
    python3 video_api.py /путь/к/видео --host 0.0.0.0 --port 8090
Приложению нужен HTTPS: поставьте перед ним nginx/Caddy с сертификатом и отдавайте по адресу
https://ваш-сайт/video_api.php (proxy_pass на этот сервер) — или любой путь, оканчивающийся на .php.
"""
import argparse
import hmac
import mimetypes
import os
import re
from pathlib import Path

from flask import Flask, Response, abort, jsonify, request

API_KEY = "__KEY__"   # ключ этого источника; "" — без пароля
MAX_DEPTH = 6
SKIP_DIRS = {"cgi-bin", "node_modules", "vendor", "tmp", "cache", "logs", "posters", "__pycache__"}
MIME = {"mp4": "video/mp4", "m4v": "video/mp4", "mov": "video/quicktime", "webm": "video/webm",
        "mkv": "video/x-matroska", "3gp": "video/3gpp", "jpg": "image/jpeg", "jpeg": "image/jpeg",
        "png": "image/png", "webp": "image/webp", "gif": "image/gif"}

app = Flask(__name__)
ROOT = Path(".").resolve()


def mime_of(name):
    return MIME.get(name.rsplit(".", 1)[-1].lower()) if "." in name else None


def fail(code, error):
    r = jsonify(ok=False, error=error)
    r.status_code = code
    return r


def folder_dir(folder):
    if folder in ("", "."):
        return ROOT
    if ".." in folder or folder.startswith("/") or re.search(r"(^|/)\.", folder):
        abort(fail(400, "bad folder"))
    d = (ROOT / folder).resolve()
    if not d.is_dir() or not str(d).startswith(str(ROOT) + os.sep):
        abort(fail(404, "folder not found"))
    return d


def has_media(d):
    return any(not p.name.startswith(".") and p.is_file() and mime_of(p.name) for p in d.iterdir())


def folders(rel="", depth=0, acc=None):
    acc = [] if acc is None else acc
    d = ROOT / rel if rel else ROOT
    if has_media(d):
        acc.append(rel or ".")
    if depth < MAX_DEPTH:
        subs = sorted((p.name for p in d.iterdir() if p.is_dir() and not p.is_symlink()
                       and not p.name.startswith(".") and p.name.lower() not in SKIP_DIRS), key=str.lower)
        for n in subs:
            folders(f"{rel}/{n}" if rel else n, depth + 1, acc)
    return acc


@app.before_request
def check_key():
    if API_KEY:
        given = request.headers.get("X-API-Key") or request.args.get("key") or request.cookies.get("vapi_key") or ""
        if not hmac.compare_digest(API_KEY, given):
            return fail(401, "Unauthorized")


@app.route("/", defaults={"path": ""})
@app.route("/<path:path>")
def api(path):
    action = request.args.get("action", "folders")
    folder = request.args.get("folder", ".")
    if action == "folders":
        return jsonify(ok=True, folders=folders(), app="smart-player-hosting/1")
    if action == "list":
        d = folder_dir(folder)
        q = "?action=stream&folder=" + _enc(folder) + "&file="
        items = []
        for p in d.iterdir():
            m = mime_of(p.name)
            if p.name.startswith(".") or not m or not p.is_file():
                continue
            poster = None
            for e in ("jpg", "png", "webp"):
                pp = d / "posters" / (p.stem + "." + e)
                if pp.is_file():
                    pf = "posters" if folder in ("", ".") else folder + "/posters"
                    poster = "?action=stream&folder=" + _enc(pf) + "&file=" + _enc(pp.name)
                    break
            video = m.startswith("video/")
            st = p.stat()
            items.append(dict(fname=p.name, url=q + _enc(p.name), type="video" if video else "image",
                              size=st.st_size, mtime=int(st.st_mtime), poster=poster or (None if video else q + _enc(p.name))))
        items.sort(key=lambda x: -x["mtime"])
        return jsonify(ok=True, items=items)
    if action == "stream":
        d = folder_dir(folder)
        name = request.args.get("file", "")
        if not name or name != os.path.basename(name) or name.startswith("."):
            return fail(400, "bad file name")
        p = (d / name).resolve()
        m = mime_of(name)
        if p.parent != d or not p.is_file() or not m:
            return fail(404, "not found")
        return _send_range(p, m)
    return fail(400, "unknown action")


def _enc(s):
    from urllib.parse import quote
    return quote(s, safe="")


def _send_range(p, mime):
    size = p.stat().st_size
    start, end, status = 0, size - 1, 200
    rng = request.headers.get("Range")
    if rng:
        m = re.match(r"bytes=(\d*)-(\d*)$", rng.strip())
        if not m or (m.group(1) == "" and m.group(2) == ""):
            return Response(status=416, headers={"Content-Range": f"bytes */{size}"})
        if m.group(1) == "":
            start = max(0, size - int(m.group(2)))
        else:
            start = int(m.group(1))
            if m.group(2):
                end = min(int(m.group(2)), size - 1)
        if start > end or start >= size:
            return Response(status=416, headers={"Content-Range": f"bytes */{size}"})
        status = 206

    def gen():
        with open(p, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(1 << 16, left))
                if not chunk:
                    break
                left -= len(chunk)
                yield chunk

    h = {"Accept-Ranges": "bytes", "Content-Length": str(end - start + 1), "Cache-Control": "private, max-age=86400"}
    if status == 206:
        h["Content-Range"] = f"bytes {start}-{end}/{size}"
    return Response(gen() if request.method != "HEAD" else b"", status=status, mimetype=mime, headers=h, direct_passthrough=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Smart Player: источник видео из папки")
    ap.add_argument("root", nargs="?", default=".", help="папка с видео (по умолчанию — текущая)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    a = ap.parse_args()
    ROOT = Path(a.root).resolve()
    mimetypes.init()
    print(f"Smart Player source: {ROOT} → http://{a.host}:{a.port}/video_api.php")
    app.run(host=a.host, port=a.port, threaded=True)
