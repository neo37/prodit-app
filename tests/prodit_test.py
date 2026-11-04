#!/usr/bin/env python3
"""Автотест надстройки Production IT (php/prodit_ext.php) на чистой базе в Docker (php:8.3-cli).
Подставные: SMTP (ловит письма с кодом), календарь встреч и выдача токенов LiveKit — локальные серверы на хосте.
Запуск: python3 tests/prodit_test.py — выходит с кодом 1 при ошибке."""
import json, os, re, socket, subprocess, sys, tempfile, threading, time, urllib.parse, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PHP = os.path.join(HERE, '..', 'php')
KEY = 'test-master-key'
PORT, SMTP_PORT, FAKE_PORT = 18124, 18125, 18126
fails = 0
mails = []


def check(name, cond):
    global fails
    print(('  ok  ' if cond else 'FAIL  ') + name)
    if not cond:
        fails += 1


def smtp_server():
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', SMTP_PORT)); srv.listen(5)
    while True:
        c, _ = srv.accept()
        f = c.makefile('rb')
        c.sendall(b'220 fake\r\n')
        data = None
        while True:
            line = f.readline()
            if not line:
                break
            if data is not None:
                if line in (b'.\r\n',):
                    mails.append(b''.join(data).decode()); data = None; c.sendall(b'250 ok\r\n')
                else:
                    data.append(line)
                continue
            cmd = line.strip().upper()
            if cmd.startswith(b'EHLO'):
                c.sendall(b'250-fake\r\n250 AUTH LOGIN\r\n')
            elif cmd == b'AUTH LOGIN':
                c.sendall(b'334 VXNlcm5hbWU6\r\n'); f.readline(); c.sendall(b'334 UGFzc3dvcmQ6\r\n'); f.readline(); c.sendall(b'235 ok\r\n')
            elif cmd == b'DATA':
                data = []; c.sendall(b'354 go\r\n')
            elif cmd == b'QUIT':
                c.sendall(b'221 bye\r\n'); break
            else:
                c.sendall(b'250 ok\r\n')
        c.close()


created = []


class Fake(BaseHTTPRequestHandler):
    def _out(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith('/meet/api/list'):
            return self._out({'ok': True, 'items': [
                {'uid': '1', 'title': 'Планёрка', 'start': '2026-11-04T09:00:00+00:00', 'minutes': 30, 'organizer': 'X',
                 'organizer_email': 'boss@example.com', 'emails': ['A@example.com', 'boss@example.com'], 'link': 'https://efir.umno.cloud/rooms/productionit-plan-1'},
                {'uid': '2', 'title': 'Чужая', 'start': '2026-11-04T10:00:00+00:00', 'minutes': 30, 'organizer': 'Y',
                 'organizer_email': 'z@example.com', 'emails': ['z@example.com'], 'link': 'https://efir.umno.cloud/rooms/productionit-other-2'}]})
        if self.path.startswith('/api/connection-details'):
            return self._out({'serverUrl': 'wss://lk.example', 'participantToken': 'tok', 'roomName': 'x'})
        self._out({'ok': False}, 404)

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        created.append(json.loads(self.rfile.read(n)))
        self._out({'ok': True, 'room': 'productionit-new-1', 'link': 'https://efir.umno.cloud/rooms/productionit-new-1'})

    def log_message(self, *a):
        pass


def call(action, body=None, uid='device00000001', key=None, qs=''):
    req = urllib.request.Request(f'http://127.0.0.1:{PORT}/video_api.php?action={action}{qs}',
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={'X-UID': uid, 'Content-Type': 'application/json', **({'X-API-Key': key} if key else {})})
    try:
        r = urllib.request.urlopen(req, timeout=15)
        raw = r.read()
        return r.status, (json.loads(raw or b'{}') if raw[:1] in (b'{', b'') else raw.decode())
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw or b'{}') if raw[:1] in (b'{', b'') else raw.decode())


def login(email, name, uid):
    n = len(mails)
    s, o = call('prodit_auth_start', {'email': email, 'lang': 'ru'}, uid=uid)
    for _ in range(40):
        if len(mails) > n:
            break
        time.sleep(0.1)
    import base64
    body = mails[-1] if len(mails) > n else ''
    text = ''.join(base64.b64decode(p).decode('utf-8', 'ignore') for p in re.findall(r'base64\r?\n\r?\n([A-Za-z0-9+/=\r\n]+?)\r?\n--', body))
    code = (re.search(r'(\d{6})', text) or [None, ''])[1]
    return s, code, call('prodit_auth_verify', {'email': email, 'code': code, 'name': name}, uid=uid)


def main():
    threading.Thread(target=smtp_server, daemon=True).start()
    threading.Thread(target=ThreadingHTTPServer(('0.0.0.0', FAKE_PORT), Fake).serve_forever, daemon=True).start()
    tmp = tempfile.mkdtemp()
    os.makedirs(f'{tmp}/www/site'); os.makedirs(f'{tmp}/vapi_data')
    src = open(os.path.join(PHP, 'video_api.php'), encoding='utf-8').read()
    src = re.sub(r"const API_KEY\s*=\s*'[^']*';", f"const API_KEY = '{KEY}';", src, count=1)
    open(f'{tmp}/www/site/video_api.php', 'w', encoding='utf-8').write(src)
    open(f'{tmp}/www/site/prodit_ext.php', 'w', encoding='utf-8').write(open(os.path.join(PHP, 'prodit_ext.php'), encoding='utf-8').read())
    name = f'prodit-test-{os.getpid()}'
    env = ['-e', 'SMTP_HOST=host.docker.internal', '-e', f'SMTP_PORT={SMTP_PORT}', '-e', 'SMTP_USER=edu@test.local', '-e', 'SMTP_PASS=x',
           '-e', 'SMTP_TLS=0', '-e', 'MEET_KEY=mk', '-e', 'RT_SECRET=rtsecret', '-e', 'PRODIT_CODE_GAP=3', '-e', 'REVIEW_EMAIL=review@prod-it.com', '-e', 'REVIEW_CODE=135790']
    subprocess.run(['docker', 'run', '-d', '--rm', '--name', name, '--add-host', 'host.docker.internal:host-gateway', *env,
                    '-p', f'127.0.0.1:{PORT}:8080', '-v', f'{tmp}:/srv', 'php:8.3-cli', 'php', '-S', '0.0.0.0:8080', '-t', '/srv/www/site'],
                   check=True, stdout=subprocess.DEVNULL)
    try:
        for _ in range(60):
            try:
                call('prodit_config'); break
            except Exception:
                time.sleep(0.25)
        print('настройки и админка')
        s, o = call('prodit_config')
        check('config по умолчанию', s == 200 and o.get('token_url', '').startswith('https://efir.umno.cloud'))
        check('админка без ключа — 401', call('prodit_admin')[0] == 401)
        s, o = call('prodit_admin', key=KEY)
        check('админка открывается', s == 200 and 'Production IT · настройки' in o)
        fake = f'http://host.docker.internal:{FAKE_PORT}'
        form = urllib.parse.urlencode({'token_url': 'https://lk-tokens.example/api', 'livekit_url': 'wss://lk.example',
                                       'rooms_url': 'https://efir.umno.cloud/rooms/', 'room_prefix': 'productionit-',
                                       'meet_api': 'https://spacecode.business/meet/api/', 'rt_url': 'wss://rt.example/ws', 'support_email': ''}).encode()
        r = urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{PORT}/video_api.php?action=prodit_admin&key={KEY}', data=form), timeout=10)
        check('админка сохраняет', 'Сохранено' in r.read().decode())
        s, o = call('prodit_config')
        check('адрес LiveKit поменялся', o.get('livekit_url') == 'wss://lk.example' and o.get('token_url') == 'https://lk-tokens.example/api')
        bad = urllib.parse.urlencode({'token_url': 'http://insecure', 'livekit_url': 'nope'}).encode()
        r = urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{PORT}/video_api.php?action=prodit_admin&key={KEY}', data=bad), timeout=10)
        cfg = call('prodit_config')[1]
        check('неверные адреса не сохраняются', 'нужен адрес' in r.read().decode() and cfg.get('livekit_url') == 'wss://lk.example')
        check('поля не из формы не стираются', cfg.get('rt_url') == 'wss://rt.example/ws')
        # для проверок ниже — подставные календарь и токены (http разрешён только в тесте — пишем в базу напрямую нельзя, поэтому через sqlite в контейнере)
        subprocess.run(['docker', 'exec', name, 'php', '-r',
                        f"$d=new PDO('sqlite:/srv/vapi_data/social.sqlite');$d->exec(\"INSERT OR REPLACE INTO settings VALUES('prodit_meet_api','{fake}/meet/api/'),('prodit_token_url','{fake}/api/connection-details')\");"], check=True)
        s, o = call('prodit_admin', key=KEY, qs='&check=1')
        check('проверка LiveKit из админки', 'Токен получен' in o)

        print('вход по email')
        check('без входа личка закрыта', call('dm_threads')[0] == 401)
        check('общий чат — только после входа', call('chat')[0] == 401)
        check('кривой email — 400', call('prodit_auth_start', {'email': 'nope'})[0] == 400)
        s, code, (s2, a) = login('A@example.com', 'Анна', 'device00000001')
        check('письмо с кодом ушло', s == 200 and len(code) == 6)
        check('код принят', s2 == 200 and a.get('uid', '').startswith('p') and a.get('email') == 'a@example.com')
        check('повторный код сразу — 429', call('prodit_auth_start', {'email': 'a@example.com'})[0] == 429)
        s, o = call('prodit_auth_verify', {'email': 'a@example.com', 'code': '000000'})
        check('старый код не работает', s == 401)
        s, _, (s2, b) = login('b@example.com', 'Борис', 'device00000002')
        check('второй пользователь', s2 == 200 and b['uid'] != a['uid'])
        UA, UB = a['uid'], b['uid']

        print('поиск, личка, приглашение')
        s, o = call('prodit_find', uid=UA, qs='&email=B@example.com')
        check('найден по email', s == 200 and o.get('author') == b['author'] and o.get('name') == 'Борис')
        check('незарегистрированный — 404', call('prodit_find', uid=UA, qs='&email=c@example.com')[0] == 404)
        check('сам себя — 400', call('prodit_find', uid=UA, qs='&email=a@example.com')[0] == 400)
        s, o = call('dm_post', {'to': b['author'], 'to_name': 'Борис', 'name': 'Анна', 'text': 'привет'}, uid=UA)
        check('личка отправлена', s == 200 and o.get('ok'))
        s, o = call('dm_threads', uid=UB)
        check('у Бориса есть диалог', s == 200 and len(o.get('items', o.get('threads', []))) == 1)
        n = len(mails)
        s, o = call('prodit_invite', {'email': 'c@example.com'}, uid=UA)
        time.sleep(0.5)
        check('приглашение отправлено', s == 200 and len(mails) == n + 1 and 'c@example.com' in mails[-1])
        check('повтор приглашения не шлётся', call('prodit_invite', {'email': 'c@example.com'}, uid=UA)[1].get('already') is True)
        time.sleep(3.5)
        s, _, (s2, a2) = login('a@example.com', '', 'device00000009')
        check('вход с другого телефона — тот же пользователь', s2 == 200 and a2['uid'] == UA and a2['name'] == 'Анна')

        print('аккаунт для проверки Google Play')
        n = len(mails)
        s, o = call('prodit_auth_start', {'email': 'Review@prod-it.com'}, uid='device00000050')
        check('без письма', s == 200 and len(mails) == n)
        check('неверный код — 401', call('prodit_auth_verify', {'email': 'review@prod-it.com', 'code': '000000'}, uid='device00000050')[0] == 401)
        s, o = call('prodit_auth_verify', {'email': 'review@prod-it.com', 'code': '135790', 'name': 'Google Review'}, uid='device00000050')
        check('постоянный код пускает', s == 200 and o.get('uid', '').startswith('p'))

        print('общий чат команды')
        s, o = call('chat_post', {'text': 'всем привет', 'name': 'Анна'}, uid=UA)
        check('сообщение в общий чат', s == 200 and o.get('ok'))
        s, o = call('chat', uid=UB)
        check('виден другому участнику', s == 200 and any(m.get('text') == 'всем привет' for m in o.get('items', [])))

        print('встречи и Centrifugo')
        s, o = call('prodit_meetings', uid=UA)
        items = {i['title']: i for i in o.get('items', [])}
        check('встречи из календаря', s == 200 and len(items) == 2)
        check('моя встреча отмечена', items.get('Планёрка', {}).get('mine') is True and items.get('Чужая', {}).get('mine') is False)
        check('имя комнаты', items.get('Планёрка', {}).get('room') == 'productionit-plan-1')
        s, o = call('prodit_meet_create', {'title': 'Созвон', 'start': '2026-11-05T10:00', 'emails': ['b@example.com']}, uid=UA)
        check('встреча создаётся с ключом и почтой организатора', s == 200 and created and created[-1]['key'] == 'mk'
              and created[-1]['organizer_email'] == 'a@example.com' and created[-1]['organizer'] == 'Анна')
        s, o = call('prodit_rt_token', uid=UA)
        check('токен Centrifugo', s == 200 and o.get('channel') == 'dm:#' + a['author'] and o.get('token', '').count('.') == 2)
        check('без входа токена нет', call('prodit_rt_token', uid='device00000077')[0] == 401)
    finally:
        subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print('ошибок:', fails)
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
