#!/usr/bin/env python3
"""Автотест серверного API (php/video_api.php) на чистой базе в Docker (php:8.3-cli).
Запуск: python3 tests/api_test.py — печатает проверки и выходит с кодом 1 при ошибке.
Проверяет: общий чат (номера не сбрасываются после очистки), удаление своих/чужих, админ-ключ,
закрытие чата, снятие блокировок, личку."""
import json, os, re, shutil, subprocess, sys, tempfile, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'php', 'video_api.php')
KEY = 'test-master-key'
PORT = 18123
fails = 0


def check(name, cond):
    global fails
    print(('  ok  ' if cond else 'FAIL  ') + name)
    if not cond:
        fails += 1


def call(action, body=None, uid='tester000001', key=None):
    req = urllib.request.Request(f'http://127.0.0.1:{PORT}/video_api.php?action={action}',
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={'X-UID': uid, 'Content-Type': 'application/json', **({'X-API-Key': key} if key else {})})
    try:
        r = urllib.request.urlopen(req, timeout=10)
        return r.status, json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


def main():
    tmp = tempfile.mkdtemp()
    os.makedirs(f'{tmp}/www/site'); os.makedirs(f'{tmp}/vapi_data')
    src = open(SRC, encoding='utf-8').read()
    src = re.sub(r"const API_KEY\s*=\s*'[^']*';", f"const API_KEY = '{KEY}';", src, count=1)
    open(f'{tmp}/www/site/video_api.php', 'w', encoding='utf-8').write(src)
    name = f'vapi-test-{os.getpid()}'
    subprocess.run(['docker', 'run', '-d', '--rm', '--name', name, '-p', f'127.0.0.1:{PORT}:8080', '-v', f'{tmp}:/srv',
                    'php:8.3-cli', 'php', '-S', '0.0.0.0:8080', '-t', '/srv/www/site'], check=True, stdout=subprocess.DEVNULL)
    try:
        for _ in range(40):
            try:
                call('chat'); break
            except Exception:
                time.sleep(0.25)
        print('общий чат')
        s, o = call('chat_post', {'text': 'hello', 'name': 'A'})
        check('сообщение принято', s == 200 and o.get('ok'))
        first = o.get('id', 0)
        check('номер не меньше 2001', first >= 2001)
        time.sleep(3)
        s, o = call('chat_delete', {'ref': str(first)}, uid='tester000002')
        check('чужое без ключа не удалить', s == 403)
        s, o = call('chat_delete', {'ref': str(first)}, uid='tester000002', key=KEY)
        check('админ удаляет чужое', s == 200 and o.get('ok'))
        s, o = call('chat_post', {'text': 'again', 'name': 'B'}, uid='tester000003')
        check('после очистки номер растёт', o.get('id', 0) > first)

        print('служебный отправитель поддержки')
        s, o = call('dm_post', {'to': '0123456789abcdef', 'text': 'фишинг', 'name': 'Support'}, uid='support-telegram')
        check('без ключа писать от поддержки нельзя', s == 403)
        s, o = call('dm_post', {'to': '0123456789abcdef', 'text': 'ответ', 'name': 'Support'}, uid='support-telegram', key=KEY)
        check('с мастер-ключом можно', s == 200 and o.get('ok'))

        print('админ')
        check('ключ проверяется', call('admin_check', key=KEY)[0] == 200)
        check('неверный ключ — 401', call('admin_check', key='wrong')[0] == 401)
        s, o = call('admin_chat_close', {'on': '1'}, key=KEY)
        check('чат закрыт', o.get('chat_closed') is True)
        check('список знает, что закрыт', call('chat')[1].get('closed') is True)
        s, o = call('chat_post', {'text': 'x', 'name': 'C'}, uid='tester000004')
        check('в закрытый чат не написать', s == 423 and o.get('error') == 'chat closed')
        s, o = call('chat_post', {'text': 'admin', 'name': 'Smart Player'}, uid='tester000005', key=KEY)
        check('админ пишет в закрытый', s == 200 and o.get('ok'))
        call('admin_chat_close', {'on': '0'}, key=KEY)
        check('чат открыт', call('chat')[1].get('closed') is False)
        check('без ключа не закрыть', call('admin_chat_close', {'on': '1'})[0] == 401)
        s, o = call('admin_unblock_all', {}, key=KEY)
        check('снятие блокировок', s == 200 and 'unblocked' in o)
    finally:
        subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.rmtree(tmp, ignore_errors=True)
    print('ошибок:', fails)
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
