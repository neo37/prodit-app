#!/usr/bin/env python3
"""spacecode.business/meet — встречи Production IT: создание, правка, отмена и рассылка приглашений.

Только стандартная библиотека. Слушает 127.0.0.1:8823, nginx проксирует /meet/api/ сюда (X-Real-IP — адрес клиента).
Комната всегда productionit-… на efir.umno.cloud (там она оформлена в стиле prod-it.com).
Письма — те же ящики, что у umno.cloud: основной, при ошибке — запасной (Reply-To — основной).
Без кода доступа — FREE_PER_DAY действий (создать/изменить) в сутки на браузер и на IP; дальше — код (MEET_KEY).
Править встречу может её создатель (тот же браузер, X-Client) или владелец кода; гостям — обновлённое приглашение
(тот же UID, SEQUENCE+1), убранным гостям и при отмене — METHOD:CANCEL.
Настройки — /etc/spacecode-meet.env (MEET_KEY, SMTP_*), данные — /var/lib/spacecode-meet/meetings.db.

  GET  /meet/api/list    (X-Client, X-Meet-Key)  — свои встречи; с кодом — все
  POST /meet/api/create  {key, client, title, start, tz, duration, organizer, organizer_email, emails[], note, backup_link, lang}
  POST /meet/api/edit    {key, client, uid, …те же поля… | cancel: true}
  GET  /meet/api/health
"""
import hmac
import json
import os
import re
import secrets
import smtplib
import sqlite3
import ssl
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import formataddr, make_msgid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ENV = os.environ
ROOMS = "https://efir.umno.cloud/rooms/"
PLAY = "https://play.google.com/store/apps/details?id=online.magicbtn.player"
CALENDAR = "https://spacecode.business/meet/"
DATA = ENV.get("MEET_DATA", "/var/lib/spacecode-meet")
LOG = os.path.join(DATA, "meetings.jsonl")   # журнал версии 1 — переносится в базу при первом запуске
DB = os.path.join(DATA, "meetings.db")
MAX_EMAILS, MAX_PER_DAY, FREE_PER_DAY = 30, 50, 5
EMAIL_RE = re.compile(r"^[^@\s<>,;]{1,64}@[^@\s<>,;]{1,190}\.[A-Za-z]{2,}$")
BOXES = [
    {"host": ENV.get("SMTP_HOST", ""), "port": int(ENV.get("SMTP_PORT", "465")), "user": ENV.get("SMTP_USER", ""), "password": ENV.get("SMTP_PASS", "")},
    {"host": ENV.get("SMTP2_HOST", ""), "port": int(ENV.get("SMTP2_PORT", "465")), "user": ENV.get("SMTP2_USER", ""), "password": ENV.get("SMTP2_PASS", "")},
]
_lock = threading.Lock()

T = {
    "ru": {"subj": "Встреча Production IT: {title}", "hello": "Вас пригласили на встречу Production IT.",
           "updated": "Встреча изменена — проверьте время и ссылку.", "cancelled": "Встреча отменена.",
           "upd_prefix": "Изменено: ", "cancel_prefix": "Отменено: ",
           "when": "Когда", "dur": "Длительность", "min": "мин", "who": "Организатор", "join": "Присоединиться",
           "note": "Комментарий", "backup": "Запасная ссылка", "calendar": "Управлять календарём Production IT", "inv": "Приглашение на встречу", "upd": "Встреча изменена", "cnc": "Встреча отменена", "how": "Как присоединиться", "how1": "Нажмите «Присоединиться» в назначенное время.", "how2": "Разрешите браузеру камеру и микрофон — и вы на встрече. Ничего устанавливать не нужно.", "months": ["января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря"], "wd": ["понедельник","вторник","среда","четверг","пятница","суббота","воскресенье"],
           "play": "Smart AI Player в Google Play", "tip2": "Встреча проходит в браузере, ничего ставить не нужно. Приглашение для календаря — во вложении.",
           "tip": "Ссылка открывается в браузере, установка не нужна. Приглашение для календаря — во вложении.",
           "ad": "Звонки, чат и видео с телефона — в приложении Smart AI Player:"},
    "en": {"subj": "Production IT meeting: {title}", "hello": "You are invited to a Production IT meeting.",
           "updated": "The meeting was changed — please check the time and the link.", "cancelled": "The meeting was cancelled.",
           "upd_prefix": "Updated: ", "cancel_prefix": "Cancelled: ",
           "when": "When", "dur": "Duration", "min": "min", "who": "Organizer", "join": "Join",
           "note": "Note", "backup": "Backup link", "calendar": "Manage the Production IT calendar", "inv": "Meeting invitation", "upd": "Meeting updated", "cnc": "Meeting cancelled", "how": "How to join", "how1": "Press “Join” at the meeting time.", "how2": "Allow camera and microphone in the browser — and you are in. Nothing to install.", "months": ["January","February","March","April","May","June","July","August","September","October","November","December"], "wd": ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"],
           "play": "Smart AI Player on Google Play", "tip2": "The meeting runs in the browser, nothing to install. A calendar invitation is attached.",
           "tip": "The link opens in a browser, nothing to install. A calendar invitation is attached.",
           "ad": "Calls, chat and videos on your phone — Smart AI Player app:"},
    "sr": {"subj": "Састанак Production IT: {title}", "hello": "Позвани сте на састанак Production IT.",
           "updated": "Састанак је измењен — проверите време и линк.", "cancelled": "Састанак је отказан.",
           "upd_prefix": "Измењено: ", "cancel_prefix": "Отказано: ",
           "when": "Када", "dur": "Трајање", "min": "мин", "who": "Организатор", "join": "Придружите се",
           "note": "Напомена", "backup": "Резервни линк", "calendar": "Управљај календаром Production IT", "inv": "Позивница за састанак", "upd": "Састанак је измењен", "cnc": "Састанак је отказан", "how": "Како се придружити", "how1": "Притисните „Придружите се” у време састанка.", "how2": "Дозволите прегледачу камеру и микрофон — и на састанку сте. Ништа не треба инсталирати.", "months": ["јануар","фебруар","март","април","мај","јун","јул","август","септембар","октобар","новембар","децембар"], "wd": ["понедељак","уторак","среда","четвртак","петак","субота","недеља"],
           "play": "Smart AI Player на Google Play", "tip2": "Састанак се одржава у прегледачу, ништа не треба инсталирати. Позивница за календар је у прилогу.",
           "tip": "Линк се отвара у прегледачу, ништа не треба инсталирати. Позивница за календар је у прилогу.",
           "ad": "Позиви, чет и видео на телефону — апликација Smart AI Player:"},
}


def slug(s):
    tr = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюяђјљњћџ",
                  ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f",
                   "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya", "dj", "j", "lj", "nj", "c", "dz"]))
    s = "".join(tr.get(ch, ch) for ch in (s or "").lower())
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:24] or "meet"


def ics(uid, title, start_utc, minutes, link, organizer, emails, note, backup, seq, cancel):
    fmt = "%Y%m%dT%H%M%SZ"
    esc = lambda v: (v or "").replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    desc = (note + "\n\n" if note else "") + link + ("\nBackup: " + backup if backup else "")
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Production IT//spacecode.business meet//EN",
             f"METHOD:{'CANCEL' if cancel else 'REQUEST'}", "BEGIN:VEVENT", f"UID:{uid}", f"SEQUENCE:{seq}",
             f"DTSTAMP:{datetime.now(timezone.utc).strftime(fmt)}",
             f"DTSTART:{start_utc.strftime(fmt)}", f"DTEND:{(start_utc + timedelta(minutes=minutes)).strftime(fmt)}",
             f"SUMMARY:{esc('Production IT · ' + title)}", f"LOCATION:{link}", f"URL:{link}", f"DESCRIPTION:{esc(desc)}",
             f"STATUS:{'CANCELLED' if cancel else 'CONFIRMED'}",
             f"ORGANIZER;CN={esc(organizer)}:mailto:{BOXES[0]['user']}"]
    lines += [f"ATTENDEE;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:{e}" for e in emails]
    if not cancel:
        lines += ["BEGIN:VALARM", "TRIGGER:-PT10M", "ACTION:DISPLAY", "DESCRIPTION:Production IT", "END:VALARM"]
    lines += ["END:VEVENT", "END:VCALENDAR"]
    return "\r\n".join(lines) + "\r\n"


def send(box, msg):
    ctx = ssl.create_default_context()
    if box["port"] == 465:
        with smtplib.SMTP_SSL(box["host"], box["port"], context=ctx, timeout=25) as s:
            s.login(box["user"], box["password"])
            s.send_message(msg)
    else:
        with smtplib.SMTP(box["host"], box["port"], timeout=25) as s:
            s.starttls(context=ctx)
            s.login(box["user"], box["password"])
            s.send_message(msg)


def mail(to, subject, text, html, ical, cancel=False):
    """Основной ящик, при ошибке — запасной. Возвращает 'primary' / 'fallback' / текст ошибки."""
    last = ""
    for i, box in enumerate(BOXES):
        if not box["host"]:
            continue
        msg = EmailMessage()
        msg["From"] = formataddr(("Production IT", box["user"]))
        msg["To"] = to
        msg["Subject"] = subject
        msg["Message-ID"] = make_msgid(domain=box["user"].split("@")[1])
        if i > 0:
            msg["Reply-To"] = BOXES[0]["user"]
        msg.set_content(text)
        msg.add_alternative(html, subtype="html")
        msg.add_attachment(ical.encode("utf-8"), maintype="text", subtype="calendar", filename="invite.ics",
                           params={"method": "CANCEL" if cancel else "REQUEST", "charset": "UTF-8"})
        try:
            send(box, msg)
            return "primary" if i == 0 else "fallback"
        except Exception as e:  # пробуем следующий ящик
            last = f"{box['user']}: {e}"
    return last or "no smtp configured"


# ---------- база ----------

def db():
    os.makedirs(DATA, exist_ok=True)
    c = sqlite3.connect(DB, timeout=10)
    c.row_factory = sqlite3.Row
    c.execute("""CREATE TABLE IF NOT EXISTS meetings (uid TEXT PRIMARY KEY, room TEXT, title TEXT, start TEXT, minutes INTEGER,
        organizer TEXT, org_email TEXT, emails TEXT, note TEXT, backup TEXT, lang TEXT, tz TEXT, owner TEXT, ip TEXT,
        created INTEGER, updated INTEGER, seq INTEGER DEFAULT 0, cancelled INTEGER DEFAULT 0)""")
    c.execute("CREATE TABLE IF NOT EXISTS actions (ts INTEGER, ip TEXT, client TEXT, kind TEXT)")
    if not c.execute("SELECT 1 FROM meetings LIMIT 1").fetchone() and os.path.exists(LOG):   # перенос журнала v1
        with open(LOG, encoding="utf-8") as f:
            for line in f:
                try:
                    m = json.loads(line)
                    c.execute("INSERT OR IGNORE INTO meetings (uid, room, title, start, minutes, organizer, org_email, emails, note,"
                              " backup, lang, tz, owner, ip, created, updated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                              (m["room"] + "@spacecode.business", m["room"], m.get("title", ""), m["start"], m.get("minutes", 60),
                               m.get("organizer", ""), "", json.dumps(m.get("emails", [])), "", m.get("backup", ""), "ru", "UTC",
                               "", "", m.get("ts", 0), m.get("ts", 0)))
                except Exception:
                    pass
    c.commit()
    return c


def key_ok(key):
    return hmac.compare_digest(ENV.get("MEET_KEY", "\0"), str(key or ""))


def allowed(c, key, ip, client):
    if key_ok(key):
        return True
    day = int(time.time()) - 86400
    n_client = c.execute("SELECT COUNT(*) FROM actions WHERE ts > ? AND client = ?", (day, client)).fetchone()[0] if client else 0
    n_ip = c.execute("SELECT COUNT(*) FROM actions WHERE ts > ? AND ip = ?", (day, ip)).fetchone()[0] if ip else 0
    return max(n_client, n_ip) < FREE_PER_DAY


def parse(d, old=None):
    """Поля встречи из запроса; old — текущие значения при правке."""
    o = dict(old) if old else {}
    title = str(d.get("title", o.get("title", ""))).strip()[:120]
    lang = d.get("lang") if d.get("lang") in T else o.get("lang") or "ru"
    tzname = str(d.get("tz", o.get("tz") or "UTC"))[:64]
    try:
        tz = ZoneInfo(tzname)
    except Exception:
        tz, tzname = timezone.utc, "UTC"
    try:
        minutes = max(15, min(480, int(d.get("duration", o.get("minutes", 60)))))
        start = datetime.fromisoformat(str(d.get("start", o.get("start", ""))).replace("Z", "+00:00"))
    except Exception:
        raise ValueError("bad date")
    if start.tzinfo is None:
        start = start.replace(tzinfo=tz)
    backup = str(d.get("backup_link", o.get("backup", "")) or "").strip()[:500]
    if backup and not re.match(r"^https://[^\s<>\"]+$", backup):
        raise ValueError("bad backup link")
    org_email = str(d.get("organizer_email", o.get("org_email", "")) or "").strip()[:254]
    raw = d.get("emails") if isinstance(d.get("emails"), list) else json.loads(o.get("emails") or "[]")
    emails = []
    for e in raw:
        e = str(e).strip()
        if e and EMAIL_RE.match(e) and e.lower() not in [x.lower() for x in emails]:
            emails.append(e)
    if org_email and EMAIL_RE.match(org_email) and org_email.lower() not in [x.lower() for x in emails]:
        emails.append(org_email)
    if not title or not emails:
        raise ValueError("title and emails are required")
    if len(emails) > MAX_EMAILS:
        raise ValueError(f"max {MAX_EMAILS} participants")
    return {"title": title, "lang": lang, "tz": tzname, "minutes": minutes, "start": start.astimezone(timezone.utc).isoformat(),
            "backup": backup, "org_email": org_email, "emails": json.dumps(emails),
            "organizer": str(d.get("organizer", o.get("organizer", "")) or "").strip()[:80],
            "note": str(d.get("note", o.get("note", "")) or "").strip()[:1000]}


# ---------- письма ----------

def render(m, kind):
    """kind: new | update | cancel. Возвращает (тема, текст, html, ics, адреса)."""
    t = T[m["lang"] if m["lang"] in T else "ru"]
    try:
        tz = ZoneInfo(m["tz"])
    except Exception:
        tz = timezone.utc
    start = datetime.fromisoformat(m["start"])
    local = start.astimezone(tz)
    when = local.strftime("%d.%m.%Y %H:%M") + f" ({m['tz'] or 'UTC'})"
    link = ROOMS + m["room"]
    esc = lambda v: (v or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    head = {"new": t["hello"], "update": t["updated"], "cancel": t["cancelled"]}[kind]
    text = (f"{head}\n\n{m['title']}\n{t['when']}: {when}\n{t['dur']}: {m['minutes']} {t['min']}\n"
            + (f"{t['who']}: {m['organizer']}\n" if m["organizer"] else "") + (f"{t['note']}: {m['note']}\n" if m["note"] else ""))
    if kind != "cancel":
        text += f"\n{t['join']}: {link}\n" + (f"{t['backup']}: {m['backup']}\n" if m["backup"] else "") + f"\n{t['tip']}"
    text += f"\n\n— Production IT\n\n{t['ad']} {PLAY}"
    def button(href, label, kind="ghost"):
        style = {"primary": "background:#5e27d4;color:#ffffff;border:1.5px solid #5e27d4;",
                 "ghost": "background:#ffffff;color:#5e27d4;border:1.5px solid #cfc3f5;"}[kind]
        return (f'<a href="{href}" style="{style}display:block;text-align:center;text-decoration:none;font:600 16px Arial,sans-serif;'
                f'padding:14px 20px;border-radius:40px;margin:0 0 10px">{esc(label)}</a>')
    end = local + timedelta(minutes=m["minutes"])
    tzshort = {"Europe/Moscow": "МСК" if m["lang"] == "ru" else "MSK"}.get(m["tz"], m["tz"] or "UTC")
    banner = {"new": t["inv"], "update": t["upd"], "cancel": t["cnc"]}[kind]
    btns = ""
    if kind != "cancel":
        btns = button(link, "▶  " + t["join"], "primary")
    btns += button(CALENDAR, "📅  " + t["calendar"])
    strike = "text-decoration:line-through;color:#a1acb8;" if kind == "cancel" else ""
    how = "" if kind == "cancel" else f"""<div style="margin:18px 0 0;padding:14px 16px;background:#f5f5f9;border-radius:16px">
<div style="font:600 14px Arial;color:#1e1d30;margin:0 0 6px">{esc(t['how'])}</div>
<div style="font:14px/1.5 Arial;color:#435971">1. {esc(t['how1'])}<br>2. {esc(t['how2'])}</div></div>"""
    html = f"""<div style="font-family:Arial,sans-serif;background:#f5f5f9;padding:24px 12px;color:#1e1d30">
<div style="max-width:520px;margin:auto;background:#ffffff;border:1.5px solid #e3e6ec;border-radius:24px;overflow:hidden">
<div style="background:#5e27d4;background-image:linear-gradient(120deg,#5e27d4,#8a4fd8 60%,#e58fb0);padding:22px 26px;color:#ffffff">
<div style="font:700 20px Arial;letter-spacing:.03em">Production IT</div>
<div style="font:14px Arial;opacity:.9;margin-top:4px">{esc(banner)}</div></div>
<div style="padding:24px 26px 26px">
<table role="presentation" style="border-collapse:collapse;width:100%;margin:0 0 18px"><tr>
<td style="width:74px;vertical-align:top"><div style="width:64px;border:1.5px solid #e3e6ec;border-radius:16px;overflow:hidden;text-align:center">
<div style="background:#5e27d4;color:#fff;font:600 11px Arial;padding:4px 0;text-transform:uppercase">{esc(t['months'][local.month - 1][:3])}</div>
<div style="font:700 26px Arial;color:#1e1d30;padding:6px 0 8px">{local.day}</div></div></td>
<td style="vertical-align:top;padding-left:6px">
<div style="font:600 21px/1.25 Arial;color:#1e1d30;{strike}">{esc(m['title'])}</div>
<div style="font:15px Arial;color:#435971;margin-top:6px">{esc(t['wd'][local.weekday()])}, {local.day} {esc(t['months'][local.month - 1])}</div>
<div style="font:600 15px Arial;color:#1e1d30;margin-top:2px">{local.strftime('%H:%M')} – {end.strftime('%H:%M')} <span style="font-weight:400;color:#697a8d">({esc(tzshort)})</span></div>
</td></tr></table>
{f'<div style="font:14px Arial;color:#697a8d;margin:0 0 16px">{t["who"]}: <b style="color:#1e1d30">{esc(m["organizer"])}</b></div>' if m["organizer"] else ''}
{f'<div style="margin:0 0 18px;color:#435971;background:#f5f5f9;border-radius:14px;padding:12px 14px;font:14px/1.5 Arial">{esc(m["note"])}</div>' if m["note"] else ''}
{btns}{how}
</div></div>
<div style="max-width:520px;margin:16px auto 0;text-align:center">
<a href="{PLAY}" style="display:inline-block;background:#0d1013;color:#f7b83d;text-decoration:none;font:600 14px Arial,sans-serif;padding:11px 20px;border-radius:40px">▶ {esc(t['play'])}</a>
<p style="color:#a1acb8;font:12px Arial;margin:10px 0 0">{esc(t['ad'].rstrip(':'))}</p>
{f'<a href="{m["backup"]}" style="display:inline-block;margin:14px 0 0;color:#697a8d;text-decoration:none;font:12px Arial,sans-serif;padding:5px 12px;border:1px solid #d9dee3;border-radius:40px">{esc(t["backup"])}</a>' if m["backup"] and kind != "cancel" else ''}
</div></div>"""
    prefix = {"new": "", "update": t["upd_prefix"], "cancel": t["cancel_prefix"]}[kind]
    subject = prefix + t["subj"].format(title=m["title"]) + " · " + local.strftime("%d.%m %H:%M")
    emails = json.loads(m["emails"] or "[]")
    ical = ics(m["uid"], m["title"], start.astimezone(timezone.utc), m["minutes"], link, m["organizer"] or "Production IT",
               emails, m["note"] or "", m["backup"] or "", m["seq"], kind == "cancel")
    return subject, text, html, ical, emails


def send_all(m, kind):
    subject, text, html, ical, emails = render(m, kind)
    sent = {e: mail(e, subject, text, html, ical, kind == "cancel") for e in emails}
    return {e: r for e, r in sent.items() if r not in ("primary", "fallback")}, len(emails)


# ---------- действия ----------

def create(d, ip="", client=""):
    with _lock:
        c = db()
        if not allowed(c, d.get("key"), ip, client):
            return 401, {"ok": False, "error": "need key"}
        if c.execute("SELECT COUNT(*) FROM meetings WHERE created > ?", (int(time.time()) - 86400,)).fetchone()[0] >= MAX_PER_DAY:
            return 429, {"ok": False, "error": "daily limit"}
        try:
            m = parse(d)
        except ValueError as e:
            return 400, {"ok": False, "error": str(e)}
        now = int(time.time())
        m.update(room=f"productionit-{slug(m['title'])}-{secrets.token_hex(3)}", uid=f"{uuid.uuid4()}@spacecode.business",
                 owner=client, ip=ip, created=now, updated=now, seq=0, cancelled=0)
        c.execute(f"INSERT INTO meetings ({','.join(m)}) VALUES ({','.join('?' * len(m))})", list(m.values()))
        c.execute("INSERT INTO actions VALUES (?,?,?,?)", (now, ip, client, "create"))
        c.commit()
    failed, n = send_all(m, "new")
    return 200, {"ok": True, "uid": m["uid"], "room": m["room"], "link": ROOMS + m["room"], "sent": n - len(failed), "failed": failed}


def edit(d, ip="", client=""):
    with _lock:
        c = db()
        row = c.execute("SELECT * FROM meetings WHERE uid = ?", (str(d.get("uid", "")),)).fetchone()
        if not row:
            return 404, {"ok": False, "error": "not found"}
        old = dict(row)
        # 02.11.2026: менять может любой посетитель страницы (лимит действий без кода — ниже, как у создания)
        if not allowed(c, d.get("key"), ip, client):
            return 401, {"ok": False, "error": "need key"}
        now = int(time.time())
        if d.get("cancel"):
            m = dict(old, seq=old["seq"] + 1, cancelled=1, updated=now)
        else:
            try:
                m = dict(old, **parse(d, old))
            except ValueError as e:
                return 400, {"ok": False, "error": str(e)}
            m.update(seq=old["seq"] + 1, updated=now)
        c.execute("UPDATE meetings SET title=?, start=?, minutes=?, organizer=?, org_email=?, emails=?, note=?, backup=?, lang=?, tz=?,"
                  " seq=?, cancelled=?, updated=? WHERE uid=?",
                  (m["title"], m["start"], m["minutes"], m["organizer"], m["org_email"], m["emails"], m["note"], m["backup"],
                   m["lang"], m["tz"], m["seq"], m["cancelled"], m["updated"], m["uid"]))
        c.execute("INSERT INTO actions VALUES (?,?,?,?)", (now, ip, client, "cancel" if d.get("cancel") else "edit"))
        c.commit()
    if d.get("cancel"):
        failed, n = send_all(m, "cancel")
    else:
        failed, n = send_all(m, "update")
        new = [x.lower() for x in json.loads(m["emails"])]
        removed = [e for e in json.loads(old["emails"] or "[]") if e.lower() not in new]
        if removed:   # убранным из гостей — отмена в их календарях
            send_all(dict(m, emails=json.dumps(removed)), "cancel")
    return 200, {"ok": True, "uid": m["uid"], "link": ROOMS + m["room"], "sent": n - len(failed), "failed": failed}


def upcoming(key, client=""):
    """Календарь: все встречи (с 02.11.2026 видны и редактируются всеми посетителями)."""
    c = db()
    lo = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()
    hi = (datetime.now(timezone.utc) + timedelta(days=120)).isoformat()
    q = "SELECT * FROM meetings WHERE cancelled = 0 AND start BETWEEN ? AND ?"
    rows = c.execute(q + " ORDER BY start", (lo, hi)).fetchall()   # 02.11.2026: все встречи видны всем
    out = [{"uid": r["uid"], "title": r["title"], "start": r["start"], "minutes": r["minutes"], "link": ROOMS + r["room"],
            "organizer": r["organizer"], "organizer_email": r["org_email"] or "", "emails": json.loads(r["emails"] or "[]"),
            "note": r["note"] or "", "tz": r["tz"], "mine": True} for r in rows]   # любой может открыть и изменить
    return 200, {"ok": True, "items": out, "all": True}


# ---------- уведомление о заходе на страницу: личка «ururu» в Умном плеере ----------
VISIT_API = "https://magicbtn.online/video_api.php?action=dm_post"
VISIT_TO, VISIT_TO_NAME = ENV.get("VISIT_TO", ""), ENV.get("VISIT_TO_NAME", "")   # кому в личку Умного плеера
VISIT_EVERY = 30 * 60          # один и тот же посетитель — не чаще раза в 30 минут
_seen_visit = {}


def visit(who, ua, with_key):
    """Страницу открыли (запрос списка встреч) — сообщить в личку ururu, в фоне и без задержки ответа."""
    now = time.time()
    if not VISIT_TO:
        return
    if not who or now - _seen_visit.get(who, 0) < VISIT_EVERY:
        return
    first = who not in _seen_visit
    _seen_visit[who] = now
    dev = "телефон" if re.search(r"Mobile|Android|iPhone", ua) else "компьютер"
    when = datetime.now(ZoneInfo("Europe/Moscow")).strftime("%d.%m %H:%M")
    text = (f"👀 Кто-то открыл spacecode.business/meet — {when} МСК, {dev}"
            f"{', с кодом доступа' if with_key else ''}{'' if first else ' (снова)'}")

    def send():
        try:
            import urllib.request
            req = urllib.request.Request(VISIT_API, data=json.dumps({"to": VISIT_TO, "to_name": VISIT_TO_NAME,
                                                                       "name": "Production IT · встречи", "text": text}).encode(),
                                         headers={"X-UID": "support-telegram", "X-API-Key": ENV.get("MAGICBTN_API_KEY", ""),
                                                  "Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=15).read()
        except Exception:
            pass
    threading.Thread(target=send, daemon=True).start()


class H(BaseHTTPRequestHandler):
    def _out(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _ip(self):
        return (self.headers.get("X-Real-IP") or self.client_address[0])[:64]

    def _client(self, d=None):
        c = (d or {}).get("client") or self.headers.get("X-Client") or ""
        return c if re.match(r"^[A-Za-z0-9_-]{8,64}$", str(c)) else ""

    def do_GET(self):
        if self.path.startswith("/meet/api/health"):
            return self._out(200, {"ok": True})
        if self.path.startswith("/meet/api/list"):
            visit(self._client() or self._ip(), self.headers.get("User-Agent", ""), bool(self.headers.get("X-Meet-Key")))
            return self._out(*upcoming(self.headers.get("X-Meet-Key", ""), self._client()))
        self._out(404, {"ok": False})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 20000:
            return self._out(413, {"ok": False, "error": "too large"})
        try:
            d = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._out(400, {"ok": False, "error": "bad json"})
        if self.path.startswith("/meet/api/create"):
            return self._out(*create(d, self._ip(), self._client(d)))
        if self.path.startswith("/meet/api/edit"):
            return self._out(*edit(d, self._ip(), self._client(d)))
        self._out(404, {"ok": False})

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    db()   # перенос журнала v1 при первом запуске
    ThreadingHTTPServer(("127.0.0.1", int(ENV.get("MEET_PORT", "8823"))), H).serve_forever()
