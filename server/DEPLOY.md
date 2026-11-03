# Деплой лайков/комментариев на hn.eyetrick.online

Сервер root@167.86.100.28, код в /root/tgbot_project, сервис hn-django.service, ключ ~/.ssh/qwer.

ВНИМАНИЕ: models.py и urls.py здесь — это уже пропатченные КОПИИ рабочих файлов сервера
(сняты 08.10). Если на сервере с тех пор что-то менялось — сверить перед заливкой.

## 1. Бэкап и заливка файлов
    cd ~/infra/hn-archive-app/server
    ssh root@167.86.100.28 'mkdir -p /root/hn-bak-social && cd /root/tgbot_project && cp core/models.py core/urls.py core/templates/core/archive.html core/templates/core/archive2.html /root/hn-bak-social/'
    scp views/reactions.py        root@167.86.100.28:/root/tgbot_project/core/views/
    scp models.py urls.py         root@167.86.100.28:/root/tgbot_project/core/
    scp templates/_media_social.html templates/archive.html templates/archive2.html \
        root@167.86.100.28:/root/tgbot_project/core/templates/core/

## 2. Миграция и перезапуск
    ssh root@167.86.100.28 'cd /root/tgbot_project && venv/bin/python manage.py makemigrations core && venv/bin/python manage.py migrate && systemctl restart hn-django && echo OK'

## 3. Проверка
    curl -s "https://hn.eyetrick.online/api/media/stats?folder=archive" -H "X-UID: test"
Ожидается JSON {"ok": true, "stats": {...}}.

## Откат
    ssh root@167.86.100.28 'cp /root/hn-bak-social/models.py /root/hn-bak-social/urls.py /root/tgbot_project/core/; cp /root/hn-bak-social/archive*.html /root/tgbot_project/core/templates/core/; rm -f /root/tgbot_project/core/templates/core/_media_social.html; systemctl restart hn-django'
Таблицы лайков/комментариев можно оставить — без шаблонов они ни на что не влияют.

## APK 1.3
    scp ~/infra/hn-archive-app/dist/hn-archive-4.apk ~/infra/hn-archive-app/dist/e.apk ~/infra/hn-archive-app/dist/version.json root@167.86.100.28:/var/www/hn-app/
