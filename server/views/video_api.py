"""API для приложения «Умный плеер» — тот же контракт, что video_api.php на magicbtn.online.

  GET /video_api.php?action=folders                      → {"ok":true,"folders":[...]} — папки, открытые этим ключом
  GET /video_api.php?action=list&folder=archive          → {"ok":true,"items":[{fname,url,type,size,mtime,poster}]}
  GET /video_api.php?action=poster&folder=archive&file=X → JPEG-превью (ffmpeg, кэш в thumbs/)
  GET  ?action=stats&folder=F                    → {"ok":true,"stats":{fname:{likes,comments,liked}}}
  POST ?action=like     {folder,file}            → {"ok":true,"liked":bool,"likes":N}
  GET  ?action=comments&folder=F&file=X[&sort=old] → {"ok":true,"items":[{name,text,ts,mine}]}
  POST ?action=comment  {folder,file,text,name}  → {"ok":true,"comment":{…}}
  Лайки/комментарии — общие с сайтом (MediaReaction/MediaComment); пользователь — заголовок X-UID.

Ключ — пароль архива (или мастер-пароль): заголовок X-API-Key или ?key=. archive3 открыт всем.
Сами файлы отдаёт nginx по /media/<file> (с поддержкой Range).
"""
import hashlib
import json
import re
import subprocess
from pathlib import Path

from django.conf import settings
from django.db.models import Count
from django.http import FileResponse, JsonResponse
from django.views.decorators.csrf import csrf_exempt

from ..models import MediaComment, MediaLog, MediaReaction

FOLDERS = ("archive", "archive2", "archive3")
PUBLIC = ("archive3",)
THUMBS = Path(settings.BASE_DIR) / "thumbs"


def _key(request) -> str:
    return (request.headers.get("X-API-Key") or request.GET.get("key") or "").strip()


def _allowed(request, folder: str) -> bool:
    if folder in PUBLIC:
        return True
    key = _key(request)
    if not key:
        return False
    master = getattr(settings, "ARCHIVE_MASTER", "")
    own = settings.ARCHIVE2_PASSWORD if folder == "archive2" else settings.MEDIA_FILE_PASSWORD
    return key == own or bool(master and key == master)


def _fail(code: int, error: str):
    return JsonResponse({"ok": False, "error": error}, status=code)


def _media_path(name: str):
    if not name or "/" in name or "\\" in name or name.startswith("."):
        return None
    p = Path(settings.MEDIA_ROOT) / name
    return p if p.is_file() else None


def _uid(request) -> str:
    uid = (request.headers.get("X-UID") or "").strip()
    return "app:" + uid if re.fullmatch(r"[A-Za-z0-9_-]{8,64}", uid) else ""


def _social(request, action: str, folder: str):
    body = {}
    if request.method == "POST":
        try:
            body = json.loads(request.body or "{}")
        except ValueError:
            return _fail(400, "bad json")
    uid = _uid(request)

    if action == "stats":
        names = list(MediaLog.objects.filter(folder=folder).values_list("file_name", flat=True))
        likes = {r["file_name"]: r["c"] for r in MediaReaction.objects.filter(file_name__in=names)
                 .values("file_name").annotate(c=Count("id"))}
        comments = {r["file_name"]: r["c"] for r in MediaComment.objects.filter(file_name__in=names)
                    .values("file_name").annotate(c=Count("id"))}
        mine = set(MediaReaction.objects.filter(uid=uid, file_name__in=names)
                   .values_list("file_name", flat=True)) if uid else set()
        stats = {n: {"likes": likes.get(n, 0), "comments": comments.get(n, 0), "liked": n in mine}
                 for n in names if n in likes or n in comments or n in mine}
        return JsonResponse({"ok": True, "stats": stats})

    name = (body.get("file") if request.method == "POST" else request.GET.get("file")) or ""
    if not MediaLog.objects.filter(folder=folder, file_name=name).exists():
        return _fail(404, "not found")

    if action == "comments":
        order = "created_at" if request.GET.get("sort") == "old" else "-created_at"
        items = [{"name": c.name or "Guest", "text": c.text, "ts": c.created_at.isoformat(),
                  "mine": bool(uid) and c.uid == uid, "id": c.id,
                  # анонимный номер автора — чтобы пользователь мог его заблокировать у себя
                  "author": hashlib.sha1(("author:" + c.uid).encode()).hexdigest()[:16]}
                 for c in MediaComment.objects.filter(file_name=name).order_by(order)[:500]]
        return JsonResponse({"ok": True, "items": items})

    if request.method != "POST":
        return _fail(405, "method not allowed")
    if not uid:
        return _fail(400, "X-UID required")

    if action == "like":
        existing = MediaReaction.objects.filter(uid=uid, file_name=name).first()
        if existing:
            existing.delete()
        else:
            MediaReaction.objects.create(uid=uid, file_name=name)
        return JsonResponse({"ok": True, "liked": existing is None,
                             "likes": MediaReaction.objects.filter(file_name=name).count()})

    text = str(body.get("text") or "").strip()[:2000]
    author = str(body.get("name") or "").strip()[:60]
    if not text:
        return _fail(400, "empty comment")
    c = MediaComment.objects.create(uid=uid, name=author, file_name=name, text=text)
    return JsonResponse({"ok": True, "comment": {"name": author or "Guest", "text": text,
                                                 "ts": c.created_at.isoformat(), "mine": True}})


@csrf_exempt
def video_api(request):
    action = request.GET.get("action", "list")
    if action == "forget":  # «Удалить мои данные» из приложения: комментарии и лайки этого устройства
        if request.method != "POST":
            return _fail(405, "method not allowed")
        uid = _uid(request)
        if not uid:
            return _fail(400, "X-UID required")
        c = MediaComment.objects.filter(uid=uid).delete()[0]
        r = MediaReaction.objects.filter(uid=uid).delete()[0]
        return JsonResponse({"ok": True, "deleted": {"comments": c, "likes": r}})
    if action in ("like", "comment") and request.method == "POST":
        try:  # папка — из тела JSON, проверяется так же, как в GET
            request.GET = request.GET.copy()
            request.GET["folder"] = json.loads(request.body or "{}").get("folder", "")
        except ValueError:
            return _fail(400, "bad json")
    if action == "folders":
        return JsonResponse({"ok": True, "folders": [f for f in FOLDERS if _allowed(request, f)]})

    folder = request.GET.get("folder", "")
    if folder not in FOLDERS:
        return _fail(400, "unknown folder")
    if not _allowed(request, folder):
        return _fail(401, "Unauthorized")

    if action in ("stats", "like", "comments", "comment"):
        return _social(request, action, folder)

    if action == "list":
        items = []
        for m in MediaLog.objects.filter(folder=folder).order_by("-created_at"):
            p = _media_path(m.file_name)
            if p is None:
                continue
            video = "video" in (m.file_type or "") or p.suffix.lower() in (".mp4", ".webm", ".mov", ".m4v", ".mkv")
            st = p.stat()
            items.append({
                "fname": m.file_name,
                "url": f"/media/{m.file_name}",
                "type": "video" if video else "image",
                "size": st.st_size,
                "mtime": int(st.st_mtime),
                # превью 320 px и для фото — не качать оригиналы ради плиток
                "poster": f"?action=poster&folder={folder}&file={m.file_name}",
            })
        resp = JsonResponse({"ok": True, "items": items})
        resp["Cache-Control"] = "no-store"
        return resp

    if action == "poster":
        name = request.GET.get("file", "")
        if not MediaLog.objects.filter(folder=folder, file_name=name).exists():
            return _fail(404, "not found")
        src = _media_path(name)
        if src is None:
            return _fail(404, "not found")
        THUMBS.mkdir(exist_ok=True)
        out = THUMBS / (hashlib.sha1(name.encode()).hexdigest() + ".jpg")
        if not out.exists():
            # видео — кадр на 1-й секунде (короткие — с начала); фото — без -ss, иначе ffmpeg не отдаёт кадр
            for ss in (["-ss", "1"], ["-ss", "0"], []):
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *ss, "-i", str(src),
                                "-frames:v", "1", "-update", "1", "-vf", "scale=320:-2", str(out)],
                               timeout=30, check=False)
                if out.exists() and out.stat().st_size > 0:
                    break
        if not out.exists():
            return _fail(500, "no poster")
        resp = FileResponse(open(out, "rb"), content_type="image/jpeg")
        resp["Cache-Control"] = "private, max-age=604800"
        return resp

    return _fail(400, "unknown action")
