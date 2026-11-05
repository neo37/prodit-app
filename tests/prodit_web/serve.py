#!/usr/bin/env python3
"""Локальный стенд веб-клиента Production IT — без боевых серверов.

  PHP 8.3 (video_api.php + prodit_ext.php, чистая база) и Centrifugo v6 — в Docker, в своей сети;
  подставные SMTP (письма с кодом ловятся здесь), календарь встреч (/meet/api/) и выдача токенов LiveKit;
  один origin, как на spacecode.business:
     /production-it/app/  — веб-клиент из web/production-it/app этого репозитория
     /meet/               — страница календаря из meet/web, шрифты и /img/ — из ~/infra/spacecode-site
     /meet/api/…          — настоящий meet/meet_api.py с временной базой (письма не отправляются)
     /prodit/video_api.php → PHP в Docker
     /__test/code?email=  — последний код из подставной почты (для автотеста)
     /__test/mails        — все пойманные письма (кому, тема)

Запуск: python3 tests/prodit_web/serve.py [порт]   (по умолчанию 18150; Ctrl+C — остановить и удалить контейнеры)
"""
import base64, json, os, re, socket, subprocess, sys, tempfile, threading, time, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PHP_SRC = os.path.join(HERE, '..', '..', 'php')
SITE = os.path.expanduser('~/infra/spacecode-site/extra')
REPO = os.path.join(HERE, '..', '..')
os.environ.update(MEET_DATA=tempfile.mkdtemp(prefix='prodit-meet-'), MEET_KEY='mk', SMTP_HOST='', SMTP2_HOST='')
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(REPO, 'meet'))
import meet_api  # noqa: E402
meet_api.ROOMS = 'https://efir.invalid/rooms/'
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18150
PHP_PORT, SMTP_PORT, CF_PORT = PORT + 1, PORT + 2, PORT + 3
TAG = f'prodit-web-{PORT}'
mails = []          # (to, subject, text)
lock = threading.Lock()


def smtp_server():
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', SMTP_PORT)); srv.listen(5)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=smtp_session, args=(c,), daemon=True).start()


def smtp_session(c):
    f = c.makefile('rb'); c.sendall(b'220 fake\r\n'); data = None; rcpt = ''
    while True:
        line = f.readline()
        if not line:
            break
        if data is not None:
            if line == b'.\r\n':
                raw = b''.join(data).decode('utf-8', 'ignore')
                subj = re.search(r'^Subject: =\?UTF-8\?B\?([^?]+)\?=', raw, re.M)
                subj = base64.b64decode(subj.group(1)).decode() if subj else ''
                text = ''.join(base64.b64decode(p).decode('utf-8', 'ignore') for p in re.findall(r'base64\r?\n\r?\n([A-Za-z0-9+/=\r\n]+?)\r?\n--', raw))
                with lock:
                    mails.append({'to': rcpt.lower(), 'subject': subj, 'text': text})
                print(f'  [почта] → {rcpt}: {subj}', flush=True)
                data = None; c.sendall(b'250 ok\r\n')
            else:
                data.append(line)
            continue
        cmd = line.strip().upper()
        if cmd.startswith(b'EHLO'):
            c.sendall(b'250-fake\r\n250 AUTH LOGIN\r\n')
        elif cmd == b'AUTH LOGIN':
            c.sendall(b'334 VXNlcm5hbWU6\r\n'); f.readline(); c.sendall(b'334 UGFzc3dvcmQ6\r\n'); f.readline(); c.sendall(b'235 ok\r\n')
        elif cmd.startswith(b'RCPT TO:'):
            rcpt = line.strip()[8:].decode().strip('<>'); c.sendall(b'250 ok\r\n')
        elif cmd == b'DATA':
            data = []; c.sendall(b'354 go\r\n')
        elif cmd == b'QUIT':
            c.sendall(b'221 bye\r\n'); break
        else:
            c.sendall(b'250 ok\r\n')
    c.close()


def seed_meetings():
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    def m(title, dt, mins, org, org_email, emails):
        meet_api.create({'key': 'mk', 'title': title, 'start': (now + dt).isoformat(), 'tz': 'Europe/Moscow', 'duration': mins,
                         'organizer': org, 'organizer_email': org_email, 'emails': emails, 'lang': 'ru'})
    m('Планёрка команды', timedelta(minutes=-10), 30, 'Борис', 'boris@acme.io', ['anna@acme.io', 'boris@acme.io'])
    m('Демо заказчику', timedelta(hours=3), 60, 'Анна', 'anna@acme.io', ['client@example.com'])
    m('Ретро спринта', timedelta(days=1, hours=1), 45, 'Ольга', 'olga@other.org', ['olga@other.org'])


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype='application/json; charset=utf-8', extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def _static(self, root, rel):
        path = os.path.realpath(os.path.join(root, rel))
        if not path.startswith(os.path.realpath(root)):
            return self._send(403, b'forbidden', 'text/plain')
        if os.path.isdir(path):
            path = os.path.join(path, 'index.html')
        if not os.path.isfile(path):
            return self._send(404, b'not found', 'text/plain')
        ext = path.rsplit('.', 1)[-1]
        ct = {'html': 'text/html; charset=utf-8', 'js': 'text/javascript; charset=utf-8', 'css': 'text/css; charset=utf-8', 'png': 'image/png',
              'webmanifest': 'application/manifest+json', 'woff2': 'font/woff2', 'json': 'application/json', 'webp': 'image/webp'}.get(ext, 'application/octet-stream')
        self._send(200, open(path, 'rb').read(), ct)

    def _php(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n) if n else None
        url = f'http://127.0.0.1:{PHP_PORT}' + self.path[len('/prodit'):]
        hdr = {k: v for k, v in self.headers.items() if k.lower() in ('x-uid', 'content-type', 'x-api-key')}
        req = urllib.request.Request(url, data=body, headers=hdr, method=self.command)
        try:
            r = urllib.request.urlopen(req, timeout=30); code, data, ct = r.status, r.read(), r.headers.get('Content-Type', 'application/json')
        except urllib.error.HTTPError as e:
            code, data, ct = e.code, e.read(), e.headers.get('Content-Type', 'application/json')
        self._send(code, data, ct)

    def _meet_api(self):
        p = self.path.split('?')[0]
        ip = ''   # на стенде все ходят с одного адреса: лимит «5 действий с IP» иначе срабатывает между тестами
        if p.endswith('/list'):
            code, r = meet_api.upcoming(self.headers.get('X-Meet-Key', ''), '', self.headers.get('X-Meet-Viewer', ''))
            return self._send(code, r)
        if p.endswith('/connection-details'):
            return self._send(200, {'serverUrl': 'wss://lk.invalid', 'participantToken': 'tok', 'roomName': 'x'})
        if self.command == 'POST' and (p.endswith('/create') or p.endswith('/edit')):
            b = json.loads(self.rfile.read(int(self.headers.get('Content-Length') or 0)) or b'{}')
            code, r = (meet_api.create if p.endswith('/create') else meet_api.edit)(b, ip, str(b.get('client') or ''))
            return self._send(code, r)
        self._send(404, {'ok': False, 'error': 'not found'})

    def _route(self):
        p = self.path.split('?')[0]
        if p.startswith('/prodit/'):
            return self._php()
        if p.startswith('/meet/api/'):
            return self._meet_api()
        if p == '/__test/code':
            email = urllib.parse.parse_qs(self.path.split('?', 1)[1] if '?' in self.path else '').get('email', [''])[0].lower()
            with lock:
                for m in reversed(mails):
                    if m['to'] == email:
                        c = re.search(r'(\d{6})', m['text'])
                        return self._send(200, {'code': c.group(1) if c else ''})
            return self._send(404, {'code': ''})
        if p == '/__test/mails':
            with lock:
                return self._send(200, [{'to': m['to'], 'subject': m['subject']} for m in mails])
        if p == '/__test/meetings':
            return self._send(200, meet_api.upcoming('mk')[1]['items'])
        if p.startswith('/production-it/'):
            return self._static(os.path.join(REPO, 'web', 'production-it'), urllib.parse.unquote(p[len('/production-it/'):]))
        if p.startswith('/img/'):
            return self._static(os.path.join(SITE, '..', 'img'), urllib.parse.unquote(p[len('/img/'):]))
        if p.startswith('/meet/'):
            root = os.path.join(SITE, 'meet') if p.startswith('/meet/fonts/') else os.path.join(REPO, 'meet', 'web')
            return self._static(root, urllib.parse.unquote(p[len('/meet/'):]))
        if p == '/':
            return self._send(302, b'', 'text/plain', {'Location': '/production-it/app/'})
        self._send(404, b'not found', 'text/plain')

    do_GET = do_POST = do_HEAD = _route


import urllib.parse  # noqa: E402


def sh(*a, **kw):
    return subprocess.run(list(a), check=True, stdout=subprocess.DEVNULL, **kw)


def main():
    import signal
    signal.signal(signal.SIGTERM, lambda *a: (_ for _ in ()).throw(KeyboardInterrupt()))
    threading.Thread(target=smtp_server, daemon=True).start()
    seed_meetings()
    tmp = tempfile.mkdtemp(prefix='prodit-web-')
    os.makedirs(f'{tmp}/www/site'); os.makedirs(f'{tmp}/vapi_data'); os.makedirs(f'{tmp}/cf')
    src = open(os.path.join(PHP_SRC, 'video_api.php'), encoding='utf-8').read()
    src = re.sub(r"const API_KEY\s*=\s*'[^']*';", "const API_KEY = 'test-master-key';", src, count=1)
    open(f'{tmp}/www/site/video_api.php', 'w', encoding='utf-8').write(src)
    open(f'{tmp}/www/site/prodit_ext.php', 'w', encoding='utf-8').write(open(os.path.join(PHP_SRC, 'prodit_ext.php'), encoding='utf-8').read())
    origin = f'http://127.0.0.1:{PORT}'
    json.dump({'client': {'token': {'hmac_secret_key': 'rtsecret'}, 'allowed_origins': [origin, f'http://localhost:{PORT}']},
               'http_api': {'key': 'rk'}, 'channel': {'namespaces': [{'name': 'dm', 'allow_user_limited_channels': True}]}},
              open(f'{tmp}/cf/config.json', 'w'))
    os.chmod(tmp, 0o755)
    for d in ('www', 'www/site', 'cf'):
        os.chmod(f'{tmp}/{d}', 0o755)
    os.chmod(f'{tmp}/vapi_data', 0o777)
    for f in ('www/site/video_api.php', 'www/site/prodit_ext.php', 'cf/config.json'):
        os.chmod(f'{tmp}/{f}', 0o644)
    net = TAG
    subprocess.run(['docker', 'network', 'create', net], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sh('docker', 'run', '-d', '--rm', '--name', f'{TAG}-cf', '--network', net, '-p', f'127.0.0.1:{CF_PORT}:8000',
       '-v', f'{tmp}/cf:/centrifugo:ro', 'centrifugo/centrifugo:v6', 'centrifugo', '-c', '/centrifugo/config.json')
    env = ['-e', 'SMTP_HOST=host.docker.internal', '-e', f'SMTP_PORT={SMTP_PORT}', '-e', 'SMTP_USER=edu@test.local', '-e', 'SMTP_PASS=x',
           '-e', 'SMTP_TLS=0', '-e', 'MEET_KEY=mk', '-e', 'RT_SECRET=rtsecret', '-e', f'RT_API=http://{TAG}-cf:8000', '-e', 'RT_API_KEY=rk',
           '-e', 'PRODIT_CODE_GAP=2', '-e', 'REVIEW_EMAIL=review@prod-it.com', '-e', 'REVIEW_CODE=135790']
    sh('docker', 'run', '-d', '--rm', '--name', f'{TAG}-php', '--network', net, '--add-host', 'host.docker.internal:host-gateway', *env,
       '-p', f'127.0.0.1:{PHP_PORT}:8080', '-v', f'{tmp}:/srv', 'php:8.3-cli', 'php', '-S', '0.0.0.0:8080', '-t', '/srv/www/site')
    try:
        for _ in range(80):
            try:
                urllib.request.urlopen(f'http://127.0.0.1:{PHP_PORT}/video_api.php?action=prodit_config', timeout=2); break
            except Exception:
                time.sleep(0.25)
        # до первого входа: календарь и токены — подставные, комнаты — несуществующий домен (никаких боевых адресов)
        fake = f'http://host.docker.internal:{PORT}'
        sql = (f"INSERT OR REPLACE INTO settings VALUES ('prodit_meet_api','{fake}/meet/api/'),('prodit_token_url','{fake}/meet/api/connection-details'),"
               f"('prodit_rooms_url','https://efir.invalid/rooms/'),('prodit_rt_url','ws://127.0.0.1:{CF_PORT}/connection/websocket')")
        sh('docker', 'exec', f'{TAG}-php', 'php', '-r', f'$d=new PDO("sqlite:/srv/vapi_data/social.sqlite");$d->exec("{sql}");')
        cfg = json.loads(urllib.request.urlopen(f'http://127.0.0.1:{PHP_PORT}/video_api.php?action=prodit_config').read())
        assert 'invalid' in cfg['rooms_url'] and 'host.docker.internal' in cfg['meet_api'], cfg
        srv = ThreadingHTTPServer(('0.0.0.0', PORT), H)   # PHP в Docker ходит сюда за календарём (host.docker.internal)
        print(f'READY {origin}/production-it/app/', flush=True)
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        subprocess.run(['docker', 'rm', '-f', f'{TAG}-php', f'{TAG}-cf'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['docker', 'network', 'rm', net], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
