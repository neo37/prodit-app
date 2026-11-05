# Админка Production IT

Адрес: `https://spacecode.business/prodit/video_api.php?action=prodit_admin&key=<ADMIN_KEY>`
(ключ — `ADMIN_KEY` в `/opt/prodit/.env` на сервере; `prodit/deploy_prodit.sh` печатает ссылку в конце).

## Настройки

| Поле | Что меняет | По умолчанию |
|---|---|---|
| Адрес выдачи токенов LiveKit | GET `?roomName=&participantName=` → `{serverUrl, participantToken}` | `https://efir.umno.cloud/api/connection-details` |
| Адрес сервера LiveKit | `wss://…`; пусто — берётся `serverUrl` из ответа токена | пусто |
| Ссылка на комнату для гостей | ссылки в приглашениях и кнопка 🔗 в звонке | `https://efir.umno.cloud/rooms/` |
| Префикс комнат | имена новых комнат звонков | `productionit-` |
| API календаря | откуда приложение берёт встречи | `https://spacecode.business/meet/api/` |
| Centrifugo WebSocket | мгновенная доставка; пусто — опрос | `wss://spacecode.business/prodit/connection/websocket` |
| Email поддержки | зарезервировано | пусто |

Приложения забирают настройки (`action=prodit_config`) при запуске и перед каждым звонком — **пересобирать приложение не нужно**.
Кнопка «Проверить LiveKit» запрашивает токен тестовой комнаты по текущим настройкам и показывает результат.

## Как переехать на другой сервер LiveKit
1. Поднимите LiveKit и сервис выдачи токенов (например, LiveKit Meet: `/api/connection-details`).
2. В админке впишите новый адрес выдачи токенов (и при необходимости `wss://` сервера), сохраните.
3. Нажмите «Проверить LiveKit» — должно быть «Токен получен».
4. Поменяйте «Ссылку на комнату для гостей», если браузерные комнаты тоже переехали.

Защита: неверные адреса (не `https://` / `wss://`) не сохраняются; поля, которых нет в форме, не стираются.

## Главные админы
Адреса — в `PRODIT_SUPERADMINS` на сервере (`/opt/prodit/.env`, через запятую). `prodit/deploy_prodit.sh` берёт их из локального файла
`prodit/.superadmins` (не в git). Изменить список: поправить файл и выкатить.
