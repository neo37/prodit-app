# Архитектура Production IT

```
 Android: Production IT (com.prodit.meet)                 Браузер
 ┌──────────────────────────────────────┐        ┌─────────────────────────────┐
 │ Вход по email+код · Чаты 1:1 · Встречи│        │ spacecode.business/meet     │
 │ Звонок: LiveKit Android SDK           │        │ (календарь, приглашения)     │
 │ Мгновенно: Centrifugo · Push: FCM     │        │ efir.umno.cloud/rooms/…     │
 └───────┬──────────────┬────────────────┘        │ (LiveKit Meet, та же комната)│
         │ HTTPS         │ WSS                     └──────────────┬──────────────┘
         ▼               ▼                                        │
 spacecode.business/prodit/           spacecode.business/meet/api/ ◄┘
 ┌─────────────────────────────┐      ┌───────────────────────────┐
 │ video_api.php + prodit_ext  │─────►│ meet_api.py (Python)       │
 │ (PHP 8.3, SQLite)           │      │ встречи, письма, .ics      │
 │ Centrifugo (WebSocket)      │      └───────────────────────────┘
 └──────────┬──────────────────┘
            │ токен комнаты: token_url (из админки)
            ▼
   efir.umno.cloud/api/connection-details ──► LiveKit (wss://…)
```

## Компоненты

| Где | Что | Код |
|---|---|---|
| Телефон | Приложение Production IT | `src/` (Java, общий с Умным плеером), `gradle-build/src/prodit/` (Kotlin: звонок, Centrifugo) |
| spacecode.business/prodit | API приложения: пользователи, личка, push, админка | `php/video_api.php` + `php/prodit_ext.php` |
| spacecode.business/prodit/connection | Centrifugo — мгновенная доставка событий | конфиг генерирует `prodit/deploy_prodit.sh` |
| spacecode.business/meet | Календарь встреч (страница + API) | `meet/web/index.html`, `meet/meet_api.py` |
| efir.umno.cloud | Видеовстречи LiveKit Meet и выдача токенов | отдельный проект (edu-platform-mvp/infra/livekit) |

## Потоки

**Вход.** `prodit_auth_start` → письмо с 6-значным кодом (SMTP, основной ящик → запасной) → `prodit_auth_verify` →
сервер выдаёт постоянный X-UID этого email; приложение подменяет свой X-UID на него — один аккаунт на всех телефонах.
Дальше все запросы — с заголовком `X-UID` (как в Умном плеере). Лимиты: 1 письмо в 50 с и 10 в сутки на адрес, 30 в сутки на IP, 5 попыток на код.

**Личка.** `dm_post` → запись в SQLite → `push_author()` → FCM (если приложение закрыто) и `rt_publish()` → Centrifugo
(канал `dm:#<author>`) → открытое приложение сразу вызывает проверку лички (`App.kick()`). Без Centrifugo — опрос раз в 5 с.

**Звонок.** Звонящий создаёт комнату `productionit-<14 символов>`, шлёт в личку «📞 … <rooms_url>/<комната>».
У собеседника — экран входящего звонка (push `type=call`). Оба получают токен с `token_url` и подключаются к LiveKit
(`livekit_url` из админки или `serverUrl` из ответа токена). Гость без приложения открывает ту же комнату в браузере.

**Встречи.** Приложение → `prodit_meetings` / `prodit_meet_create` (PHP проксирует в meet_api.py с ключом MEET_KEY и
почтой организатора) → meet_api рассылает приглашения с .ics. За 10 минут до начала в приложении загорается «Войти».

## Данные
- `/opt/prodit/vapi_data/social.sqlite` — пользователи (`users`, `prodit_accounts`), личка (`dm`), push-токены, настройки (`settings`, ключи `prodit_*`).
- `/var/lib/spacecode-meet/meetings.db` — встречи и журнал действий.
