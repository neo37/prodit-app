#!/usr/bin/env python3
"""Опубликовать планы развития Production IT в чат «Бэклог» (комната backlog) от имени «Production IT».
Пишет через API под тестовым аккаунтом (REVIEW_EMAIL/код — аргументы), каждое сообщение подписано «Production IT · бэклог».
Запуск: python3 post_backlog.py review@prod-it.com <код>   (повторный запуск не дублирует — проверяет, что уже опубликовано)."""
import json
import sys
import time
import urllib.request

API = "https://spacecode.business/prodit/video_api.php"
NAME = "Production IT · бэклог"
ITEMS = [
    "📞 Звонок в свёрнутом приложении (1.3). Сейчас разговор может прерваться, если свернуть Production IT. Вернём фоновую службу звонка с уведомлением «Идёт звонок» — для этого Google нужна видеозапись работы, снимаем на реальном телефоне.",
    "🏢 Пространства компаний — СДЕЛАНО в 1.2: коллеги по домену почты, каталог команды и отдельный чат компании.",
    "📅 «Запланировать встречу» из диалога и push-напоминание за 10 минут — СДЕЛАНО в 1.2.",
    "#️⃣ Каналы и групповые чаты: #проекты, #клиент-X, упоминания @имя, закреплённые сообщения.",
    "📎 Файлы и голосовые: вложения (ТЗ, макеты, PDF), голосовые сообщения, предпросмотр ссылок.",
    "🧠 ИИ-итоги встреч: запись → расшифровка → краткие итоги и задачи участникам в чат.",
    "🟢 Статусы: «в сети», «на встрече» (из календаря), «не беспокоить».",
    "🔎 Поиск по сообщениям и встречам.",
    "🖥 Веб-версия и приложение для Windows — в работе.",
    "🖥 Демонстрация экрана в звонке из приложения (сейчас — в браузерной встрече).",
    "👋 Онбординг: пригласить коллег при первом входе, демо-чат с ботом-помощником.",
    "🤖 Боты в чате: помощник по встречам, напоминания, интеграции.",
]


def call(action, uid, body=None):
    req = urllib.request.Request(f"{API}?action={action}", data=None if body is None else json.dumps(body).encode(),
                                 headers={"X-UID": uid, "Content-Type": "application/json", "User-Agent": "prodit-backlog/1.0"})
    return json.loads(urllib.request.urlopen(req, timeout=20).read())


def main():
    email, code = sys.argv[1], sys.argv[2]
    dev = "backlogposter01"
    o = call("prodit_auth_verify", dev, {"email": email, "code": code, "name": "Production IT"})
    uid = o["uid"]
    have = {m["text"] for m in call("chat&room=backlog", uid).get("items", [])}
    n = 0
    for t in ITEMS:
        if t in have:
            continue
        r = call("chat_post", uid, {"text": t, "name": NAME, "room": "backlog"})
        if not r.get("ok"):
            print("ошибка:", r)
            break
        n += 1
        time.sleep(3.2)   # сервер не принимает чаще раза в 3 с
    print(f"опубликовано: {n}, уже было: {len(ITEMS) - n}")


if __name__ == "__main__":
    main()
