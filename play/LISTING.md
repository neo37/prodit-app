# Google Play — Умный плеер / Smart Player (online.magicbtn.player)

Сборка: `dist/play/smart-player-<code>.aab` (сейчас code 21, v3.0) — `./build.sh <code> <ver> "notes"` → `./build_play.sh`.
Скриншоты: `play/screenshots/{en,ru,sr}/01…08.png` (1080×1920) — генератор `tools/shots.py`.
Графика: `play/icon-512.png`, `play/feature-1024x500.png`.

## Create app
| Поле | Значение |
| --- | --- |
| App name | Smart Player — videos *(en)* / Умный плеер — видео *(ru)* / Паметни плејер — видео *(sr)* |
| Package name | online.magicbtn.player |
| Default language | English (United States) – en-US *(приложение по умолчанию на английском)* |
| App or game | App |
| Free or paid | Free |

## Store listing
**en-US — short:** Your phone videos, magicbtn.online and your own sources in one player.
**en-US — full:**
Smart Player brings videos from different places into one app.
• Videos on this phone — by folder, with previews.
• magicbtn.online — ready to watch, no sign-up.
• Your sources — any server with the simple video_api.php API or any website with videos. Sources are remembered.
• Slideshow online and offline. Everything you watch is saved for offline viewing (hidden from other apps).
• Likes, comments and sorting — online and offline, synced when you’re back online.
• Community chat — share your sources with one tap.
• My source — upload up to 3 videos (up to 3 min each) from your phone.
• Profile with avatar, 4 themes (Aurora 3D with live glass, Lemon, Graphite, Neon), English / Русский / Српски.
• Share a source with friends by a link in Telegram, WhatsApp and other messengers.
• App password and fingerprint unlock.
No ads, no analytics.

**ru-RU — short:** Видео с телефона, с magicbtn.online и из ваших источников — в одном плеере.
**ru-RU — full:**
Умный плеер собирает видео из разных мест в одном приложении.
• Видео на телефоне — по папкам, с превью.
• magicbtn.online — смотрите сразу, без регистрации.
• Свои источники — сервер с простым API video_api.php или любой сайт с видео. Источники запоминаются.
• Слайдшоу онлайн и офлайн. Просмотренное сохраняется для офлайна (скрыто от других приложений).
• Лайки, комментарии и сортировка — онлайн и офлайн, синхронизация при появлении сети.
• Общий чат — делитесь своими источниками в одно касание.
• Мой источник — загрузите до 3 видео (до 3 минут) с телефона.
• Профиль с аватаром, 4 темы (Аврора 3D с живым стеклом, Лимон, Графит, Неон), English / Русский / Српски.
• Поделиться источником с другом ссылкой в Telegram, WhatsApp и других мессенджерах.
• Пароль на вход и вход по отпечатку.
Без рекламы и аналитики.

**sr — short:** Видео са телефона, са magicbtn.online и из ваших извора — у једном плејеру.
**sr — full:**
Паметни плејер окупља видео снимке са различитих места у једној апликацији.
• Видео на телефону — по фасциклама, са прегледом.
• magicbtn.online — гледајте одмах, без регистрације.
• Ваши извори — сервер са API video_api.php или било који сајт са видео снимцима.
• Слајдшоу на мрежи и офлајн. Погледано се чува за гледање без мреже.
• Свиђања, коментари и сортирање — на мрежи и офлајн.
• Заједнички чет — делите своје изворе једним додиром.
• Мој извор — отпремите до 3 видео снимка (до 3 мин) са телефона.
• Лозинка за улаз. Без реклама и аналитике.

## Сайт и документы
- Website: https://magicbtn.online/ai-video.htm
- Privacy policy: https://magicbtn.online/ai-video/privacy.htm
- Data deletion: https://magicbtn.online/ai-video/delete.htm
- Email: v.kiselev@core.business-pad.com

## App content
- **App access:** все функции без входа.
- **Ads:** No.
- **User-generated content:** YES — чат, комментарии, загрузка видео. Есть: правила (принимаются до первой публикации), жалобы на сообщения/видео/комментарии, автоскрытие после 3 жалоб, удаление модератором (мастер-ключ API, action=delete).
- **Permissions — READ_MEDIA_VIDEO:** core functionality — video player.
- **Content rating (IARC):** отметить пользовательский контент и общение пользователей (chat).
- **Target audience:** 18+.
- **Data safety:**
  - Collected: Device or other IDs (случайный ID приложения) — app functionality; Name (по желанию) — app functionality; Photos (аватар, загруженные фото) — app functionality; Messages: other in-app messages (чат, комментарии) — app functionality; Videos (загруженные) — app functionality.
  - Shared with third parties: No. Encrypted in transit: Yes. Users can request deletion: Yes (email, удаление своих видео в приложении).
- **News / Gov / Health / Financial:** No.

## Выпуск
1. Testing → Internal testing → Create release → AAB.
2. Play App Signing — согласиться; ключ загрузки — play-upload.keystore (пароль в play-keystore.properties). После создания: Setup → App integrity → скопировать SHA-256 ключа подписи Google и добавить в site/.well-known/assetlinks.json (пакет online.magicbtn.player), иначе ссылки «поделиться источником» будут открываться через страницу, а не сразу в приложении.
3. Новый личный аккаунт: закрытое тестирование 12+ тестировщиков 14 дней, затем Production.
