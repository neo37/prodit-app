# Production IT — Android-приложение (и Умный плеер)

Один код — два приложения (варианты сборки Gradle в `gradle-build/`):

| Вариант | Пакет | Что это |
|---|---|---|
| `prodit` | `com.prodit.meet` | **Production IT**: вход по рабочему email и коду, чаты 1:1, звонки и встречи на LiveKit, календарь spacecode.business/meet |
| `play` / `site` | `online.magicbtn.player` / `online.eyetrick.hnarchive` | Smart AI Video Player (Умный плеер) |

## Production IT

**Приложение** (`Flags.PRODIT`):
- `src/.../Prodit*.java` — вход по email (ProditLoginActivity), главный экран, встречи (ProditMeetingsActivity), настройки с сервера (`Prodit.cfg`);
- `gradle-build/src/prodit/java` (Kotlin, только в этой сборке) — `LkCall`/`LkCallActivity` (звонок на LiveKit Android SDK),
  `LkCallService` (звонок в свёрнутом приложении), `Rt` (мгновенная доставка через Centrifugo);
- общий код с плеером: личка (`ChatActivity`, `DmListActivity`), входящий звонок (`IncomingCallActivity`), push (`Push`, `PushService`).

**Сервер** — `php/video_api.php` (тот же API, что у плеера) + `php/prodit_ext.php` (подключается, только если лежит рядом):
вход по коду из письма, поиск по email, приглашения, встречи (прокси к spacecode.business/meet), токены Centrifugo
и **админка** `?action=prodit_admin&key=…` — адреса LiveKit (выдача токенов, сервер wss://), комнат, календаря и Centrifugo
меняются без пересборки приложения.

**Звонки**: токен — `token_url` (по умолчанию `https://efir.umno.cloud/api/connection-details`), комнаты `productionit-…` —
те же, что открываются в браузере на efir.umno.cloud, поэтому гости без приложения заходят по ссылке.

## Production IT Meet — календарь встреч
`meet/` — страница https://spacecode.business/meet/ (`meet/web/index.html`, тёмная тема по умолчанию) и сервис
`meet/meet_api.py`: встречи, рассылка приглашений с .ics, правка и отмена. Подробно — [docs/MEET.md](docs/MEET.md).

## Документация
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — схема, потоки (вход, личка, звонки, встречи), данные
- [docs/PRODIT_APP.md](docs/PRODIT_APP.md) — Android-приложение: экраны, отличия от плеера, сборка, разрешения
- [docs/ADMIN.md](docs/ADMIN.md) — админка: адреса LiveKit, переезд на другой сервер
- [docs/MEET.md](docs/MEET.md) — календарь встреч и его API

## Сборка и выкладка

```bash
prodit/build_prodit.sh          # dist/prodit/production-it-<code>.aab и .apk (версия — prodit/version.properties)
prodit/deploy_prodit.sh         # сервер на spacecode.business/prodit (docker: php + centrifugo, nginx)
prodit/firebase_add_app.py      # один раз: регистрация com.prodit.meet в Firebase (push)
python3 tests/prodit_test.py    # автотест сервера Production IT (Docker)
python3 tests/meet_api_test.py  # автотест регулярных встреч (без сети и Docker)
python3 tests/api_test.py       # автотест сервера плеера
meet/deploy_meet.sh             # календарь встреч spacecode.business/meet
```

Секреты (ключи подписи, `*keystore.properties`, `fcm-service-account.json`, `google-services.json`, `.ftp.env`) в репозиторий
не входят — см. `.gitignore`.

## Лицензии сторонних библиотек
LiveKit Android SDK — Apache-2.0, centrifuge-java — MIT, Firebase — условия Google, шрифт Onest — OFL.
