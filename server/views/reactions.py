"""Лайки и комментарии к медиа архива. Агрегируются на сервере.

Один пользователь (uid из сессии или заголовка X-UID) — один лайк на файл.
Комментарии без ограничения по числу. Есть сортировка списков.
"""
import json

from django.db.models import Count
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt

from ..auth import get_session_id
from ..models import MediaReaction, MediaComment, MediaLog


def _uid(request) -> str:
    return (get_session_id(request) or request.headers.get("X-UID", "") or "").strip()


def _safe(name: str) -> bool:
    return bool(name) and not any(c in name for c in ["/", "\\", "..", "\0"])


def _body(request) -> dict:
    try:
        return json.loads(request.body or "{}")
    except Exception:
        return {}


@csrf_exempt
def media_like(request):
    """Переключает лайк текущего пользователя. Возвращает итоговое число лайков."""
    if request.method != "POST":
        return JsonResponse({"ok": False}, status=405)
    uid = _uid(request)
    fn = (_body(request).get("file_name") or "").strip()
    if not uid or not _safe(fn):
        return JsonResponse({"ok": False, "error": "bad request"}, status=400)
    existing = MediaReaction.objects.filter(uid=uid, file_name=fn).first()
    if existing:
        existing.delete()
        liked = False
    else:
        MediaReaction.objects.create(uid=uid, file_name=fn)
        liked = True
    likes = MediaReaction.objects.filter(file_name=fn).count()
    return JsonResponse({"ok": True, "liked": liked, "likes": likes})


@csrf_exempt
def media_comment(request):
    if request.method != "POST":
        return JsonResponse({"ok": False}, status=405)
    uid = _uid(request)
    data = _body(request)
    fn = (data.get("file_name") or "").strip()
    text = (data.get("text") or "").strip()[:2000]
    name = (data.get("name") or "").strip()[:60]
    if not uid or not _safe(fn) or not text:
        return JsonResponse({"ok": False, "error": "bad request"}, status=400)
    c = MediaComment.objects.create(uid=uid, name=name, file_name=fn, text=text)
    return JsonResponse({"ok": True, "comment": {
        "name": name or "Гость", "text": text, "ts": c.created_at.isoformat(), "mine": True,
    }})


def media_comments(request):
    """Список комментариев к файлу. sort=new|old."""
    fn = (request.GET.get("file_name") or "").strip()
    if not _safe(fn):
        return JsonResponse({"ok": False}, status=400)
    uid = _uid(request)
    order = "created_at" if request.GET.get("sort") == "old" else "-created_at"
    qs = MediaComment.objects.filter(file_name=fn).order_by(order)[:500]
    items = [{
        "name": c.name or "Гость", "text": c.text,
        "ts": c.created_at.isoformat(), "mine": c.uid == uid,
    } for c in qs]
    return JsonResponse({"ok": True, "items": items})


def media_stats(request):
    """Агрегаты по всем файлам папки: лайки, число комментариев, мой ли лайк.

    Нужно для сортировки слайдшоу по популярности.
    """
    folder = (request.GET.get("folder") or "").strip()
    uid = _uid(request)
    names = (list(MediaLog.objects.filter(folder=folder).values_list("file_name", flat=True))
             if folder else [])
    likes, comments, mine = {}, {}, set()
    if names:
        for r in (MediaReaction.objects.filter(file_name__in=names)
                  .values("file_name").annotate(c=Count("id"))):
            likes[r["file_name"]] = r["c"]
        for r in (MediaComment.objects.filter(file_name__in=names)
                  .values("file_name").annotate(c=Count("id"))):
            comments[r["file_name"]] = r["c"]
        if uid:
            mine = set(MediaReaction.objects.filter(uid=uid, file_name__in=names)
                       .values_list("file_name", flat=True))
    stats = {n: {"likes": likes.get(n, 0), "comments": comments.get(n, 0), "liked": n in mine}
             for n in names}
    return JsonResponse({"ok": True, "stats": stats})
