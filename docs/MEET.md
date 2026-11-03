# Production IT Meet — календарь встреч (spacecode.business/meet)

Страница `meet/web/index.html` + сервис `meet/meet_api.py` (только стандартная библиотека Python, SQLite).
Комнаты встреч — `productionit-…` на efir.umno.cloud (LiveKit Meet, оформлены в стиле Production IT).

## Что умеет
- Неделя / день (на телефоне — вкладки дней), «Сегодня», переход по неделям; языки RU / EN / SR.
- **Тёмная тема по умолчанию**, кнопка ☀/☾ в шапке (выбор запоминается в браузере, `localStorage pit.theme`).
- Клик по свободному времени — новая встреча: тема, время, длительность, гости (email), комментарий, запасная ссылка.
- Гостям уходит письмо: карточка с датой, кнопка «Присоединиться», «Как присоединиться», вложение `.ics`
  (METHOD:REQUEST). Правка — то же событие (UID, SEQUENCE+1), убранным гостям и при отмене — METHOD:CANCEL.
- Без кода доступа — 5 действий в сутки на браузер и IP; с кодом (`MEET_KEY`) — без лимита. Всего ≤ 50 встреч в сутки, ≤ 30 гостей.

## API (`/meet/api/`, nginx → 127.0.0.1:8823)
| Запрос | Что делает |
|---|---|
| `GET list` (`X-Client`, `X-Meet-Key`) | встречи от −2 до +120 дней |
| `POST create {key, client, title, start, tz, duration, organizer, organizer_email, emails[], note, backup_link, lang}` | создать и разослать |
| `POST edit {…, uid}` / `{uid, cancel: true}` | изменить / отменить с рассылкой |
| `GET health` | проверка |

Приложение Production IT ходит сюда через свой сервер (`prodit_meetings`, `prodit_meet_create`), подставляя ключ и почту организатора.

## Выкладка
```bash
meet/deploy_meet.sh   # страница (через сборку сайта), systemd-сервис spacecode-meet, location /meet/api/ в nginx
```
Настройки сервиса — `/etc/spacecode-meet.env`: `MEET_KEY`, `SMTP_*` / `SMTP2_*` (берутся из локального `~/send_mail_umno.py`),
необязательно — уведомления о посетителях в личку Умного плеера: `VISIT_TO`, `VISIT_TO_NAME`, `MAGICBTN_API_KEY`
(локальный файл `meet/.visit.env`, не в git).

## Шрифты
Страница использует Gilroy и Tactic Sans Extended (фирменные шрифты prod-it.com, коммерческие) — в репозиторий они
**не входят**. Положите файлы в `meet/web/fonts/` (`Gilroy-Regular.woff2`, `Gilroy-Medium.woff2`,
`TacticSansExtended-Bold.woff2`); без них страница работает на системном шрифте.
