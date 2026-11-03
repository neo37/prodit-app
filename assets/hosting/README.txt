Умный плеер — свой сайт как источник видео
==========================================

Вариант 1. Любой хостинг с PHP (reg.ru, Timeweb, Beget, cPanel и т. п.)
  1. Загрузите файл video_api.php в корень сайта (туда же, где лежат папки с видео).
     Можно в любую папку — тогда в источник попадёт она и всё, что внутри.
  2. В приложении: ☰ → «Свой сайт → источник» → «Подключить сайт» → адрес сайта
     (например, https://мой-сайт.ru). Ключ подставится сам.
  Видео и фото из всех вложенных папок появятся в приложении, каждая папка — разделом.
  Превью: положите картинку posters/<имя ролика>.jpg рядом с роликом (необязательно).

Вариант 2. Свой сервер с Python (Flask)
  pip install flask
  python3 video_api.py /путь/к/видео --port 8090
  Приложению нужен HTTPS: отдавайте сервер через nginx или Caddy с сертификатом,
  по адресу, оканчивающемуся на .php, например:
    location = /video_api.php { proxy_pass http://127.0.0.1:8090; }
  В приложение вводите https://ваш-сайт/video_api.php

Ключ доступа уже вписан в оба файла (строка API_KEY). Без ключа видео не видны.
Чтобы открыть источник для всех — замените ключ на пустые кавычки: ''.
Поддерживаются: mp4, m4v, mov, webm, mkv, 3gp, jpg, png, webp, gif. Перемотка работает.

----------------------------------------------------------------------------
Smart Player — your website as a video source
  PHP hosting: upload video_api.php to the site root, then in the app:
  ☰ → "Your website → source" → "Connect website" → https://your-site.
  Python: pip install flask; python3 video_api.py /path/to/videos — serve it over HTTPS
  (nginx/Caddy) at a URL ending in .php. The access key is already inside both files.
