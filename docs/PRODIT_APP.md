# Приложение Production IT (Android)

Пакет `com.prodit.meet`, minSdk 24, targetSdk 36. Вариант сборки `prodit` общего кода с Умным плеером: всё, что
относится только к Production IT, включается флагом `Flags.PRODIT` (`BuildConfig.PRODIT`).

## Экраны
| Экран | Класс |
|---|---|
| Вход: email + имя → код из письма | `ProditLoginActivity` |
| Главный: Чаты, Встречи, Написать по email, Профиль, Выйти | `ProditHomeActivity` |
| Список диалогов | `DmListActivity` (общий) |
| Чат 1:1, кнопка 📞 | `ChatActivity` (общий; вложения в Production IT скрыты) |
| Входящий звонок | `IncomingCallActivity` (общий) |
| Встречи: список (от серии — ближайшая дата), карточка встречи, «+» новая встреча с повтором, правка серии или одной даты | `ProditMeetingsActivity` |
| Календарь: неделя и день по часам, нативный экран; нажатие на свободное время — новая встреча | `ProditCalendarView` |
| Звонок LiveKit: вдвоём — собеседник на весь экран, больше — сетка; 🎤 📷 🔄 🔗 ✕ | `LkCallActivity` (Kotlin) |

## Что отличается от Умного плеера
- Свой первый экран и тёмная палитра Production IT (`Look.apply`: фон #12111C, акцент #5E27D4).
- Общий чат «на весь мир», плеер, игра, подсказки с Алисой, вложения — выключены; их ресурсы не попадают в сборку.
- Звонки — внутри приложения (`Calls.open` → `Prodit.openCall` → `LkCallActivity`), а не в браузере.
- Служба `LkCallService` (foregroundServiceType `microphone|camera`) держит звонок в свёрнутом приложении.
- `Rt` — клиент Centrifugo: события лички и звонков приходят сразу.

## Зависимости только этой сборки
`io.livekit:livekit-android:2.30.0` (Apache-2.0), `io.github.centrifugal:centrifuge-java:0.8.0` (MIT), Kotlin.
`audioswitch` для LiveKit берётся с JitPack (ограничено группой `com.github.davidliu` в `settings.gradle`).
Демонстрация экрана LiveKit из манифеста убрана (иначе в Play нужна декларация mediaProjection).

## Сборка
```bash
prodit/build_prodit.sh     # dist/prodit/production-it-<code>.aab/.apk
```
Нужны (не в git): `prodit-upload.keystore` + `prodit-keystore.properties` (`PRODIT_KS_PASS=…`),
`google-services.json` с клиентом `com.prodit.meet` (`prodit/firebase_add_app.py`). Версия — `prodit/version.properties`
(`CODE` +1 на каждую загрузку в Play).

## Разрешения
Камера, микрофон, уведомления, Bluetooth (гарнитура), службы переднего плана microphone/camera.
Для Play Console: декларация FGS microphone/camera — «видеозвонок, продолжающийся в свёрнутом приложении».
