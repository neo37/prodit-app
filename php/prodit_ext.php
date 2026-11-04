<?php
/**
 * Production IT — надстройка над video_api.php (тот же код чата/лички/push, что у Умного плеера).
 * Подключается из video_api.php, только если этот файл лежит рядом: на magicbtn.online его нет — плеер не меняется.
 * Сервер Production IT — отдельная копия (spacecode.business/prodit/), своя база и свои пользователи.
 *
 *   GET  ?action=prodit_config                 — адреса LiveKit / токенов / комнат / встреч / Centrifugo (меняются в админке)
 *   POST ?action=prodit_auth_start {email,lang} — письмо с кодом
 *   POST ?action=prodit_auth_verify {email,code,name} → {uid, author, name} — приложение берёт этот X-UID себе
 *   GET  ?action=prodit_find&email=            → {author, name} | 404 not registered
 *   POST ?action=prodit_invite {email,lang}    — письмо «установите приложение»
 *   GET  ?action=prodit_meetings               — календарь spacecode.business/meet (mine — я в гостях)
 *   POST ?action=prodit_meet_create {title,start,tz,duration,emails[],note,lang}
 *   GET  ?action=prodit_rt_token               — токен подключения к Centrifugo (канал dm:#<author>)
 *   GET  ?action=prodit_admin&key=<мастер-ключ> — админка: адреса LiveKit и прочие настройки; POST — сохранить
 *
 * Остальные действия — как в video_api.php, но лично писать/звонить можно только после входа по email.
 * Почта — SMTP из окружения (SMTP_HOST/PORT/USER/PASS, запасной SMTP2_*), встречи — MEET_API + MEET_KEY.
 */

const PRODIT_DEFAULTS = [
    'token_url'    => 'https://efir.umno.cloud/api/connection-details',   // выдаёт serverUrl + participantToken
    'livekit_url'  => '',                                                 // пусто — берётся serverUrl из ответа токена
    'rooms_url'    => 'https://efir.umno.cloud/rooms/',                   // ссылки для гостей из браузера
    'room_prefix'  => 'productionit-',
    'meet_api'     => 'https://spacecode.business/meet/api/',
    'rt_url'       => 'wss://spacecode.business/prodit/connection/websocket',   // Centrifugo; пусто — без мгновенной доставки
    'support_email'=> '',
];
const PRODIT_LABELS = [
    'token_url'    => 'Адрес выдачи токенов LiveKit (GET ?roomName=&participantName= → serverUrl, participantToken)',
    'livekit_url'  => 'Адрес сервера LiveKit (wss://…) — если пусто, берётся из ответа выдачи токенов',
    'rooms_url'    => 'Ссылка на комнату для гостей в браузере (…/rooms/)',
    'room_prefix'  => 'Префикс имён комнат звонков',
    'meet_api'     => 'API календаря встреч (…/meet/api/)',
    'rt_url'       => 'Centrifugo WebSocket (мгновенная доставка; пусто — опрос)',
    'support_email'=> 'Email поддержки (кнопка «Написать в поддержку»)',
];
const PRODIT_ACTIONS = ['prodit_config', 'prodit_auth_start', 'prodit_auth_verify', 'prodit_find', 'prodit_invite',
    'prodit_meetings', 'prodit_meet_create', 'prodit_rt_token', 'prodit_admin', 'prodit_company', 'prodit_remind', 'prodit_backlog'];
// публичные почтовые сервисы — у их пользователей нет «компании» по домену
const PRODIT_PUBLIC_MAIL = ['gmail.com', 'googlemail.com', 'yandex.ru', 'ya.ru', 'yandex.com', 'mail.ru', 'bk.ru', 'inbox.ru', 'list.ru',
    'rambler.ru', 'outlook.com', 'hotmail.com', 'live.com', 'icloud.com', 'me.com', 'yahoo.com', 'proton.me', 'protonmail.com', 'gmx.com',
    'gmx.de', 'aol.com', 'zoho.com', 'tutanota.com', 'mail.com'];
// действия плеера, которые в Production IT только после входа по email
const PRODIT_NEED_LOGIN = ['dm_threads', 'dm', 'dm_post', 'dm_delete', 'dm_block', 'users', 'push_register', 'avatar_set', 'hello',
    'chat', 'chat_post', 'chat_delete', 'report'];
// общий чат Production IT — своя тема push: Firebase-проект общий с Умным плеером, тема «chat» у него своя
const PUSH_CHAT_TOPIC = 'prodit_chat';

function prodit_db(): PDO
{
    static $init = false;
    $db = social();
    if (!$init) {
        $db->exec('CREATE TABLE IF NOT EXISTS prodit_accounts (email TEXT PRIMARY KEY COLLATE NOCASE, uid TEXT UNIQUE, author TEXT, ts INTEGER)');
        $db->exec('CREATE TABLE IF NOT EXISTS prodit_codes (email TEXT COLLATE NOCASE, hash TEXT, exp INTEGER, tries INTEGER, ts INTEGER, ip TEXT)');
        $db->exec('CREATE TABLE IF NOT EXISTS prodit_invites (author TEXT, email TEXT, ts INTEGER)');
        $init = true;
    }
    return $db;
}

function prodit_setting(string $k): string
{
    $q = prodit_db()->prepare('SELECT v FROM settings WHERE k = ?');
    $q->execute(['prodit_' . $k]);
    $v = $q->fetchColumn();
    return $v === false ? (PRODIT_DEFAULTS[$k] ?? '') : (string)$v;
}

function prodit_body(): array
{
    static $b = null;
    if ($b === null) {
        $b = $_SERVER['REQUEST_METHOD'] === 'POST' ? (json_decode(file_get_contents('php://input') ?: '{}', true) ?: []) : [];
    }
    return $b;
}

function prodit_account(string $uid): ?array
{
    $q = prodit_db()->prepare('SELECT * FROM prodit_accounts WHERE uid = ?');
    $q->execute([$uid]);
    return $q->fetch(PDO::FETCH_ASSOC) ?: null;
}

function prodit_me(): array
{
    $acc = prodit_account(uid(true));
    if (!$acc) {
        fail(401, 'login required');
    }
    return $acc;
}

/** Домен компании по email; '' — публичная почта (gmail и т.п.) */
function prodit_domain(string $email): string
{
    $d = strtolower(substr(strrchr($email, '@') ?: '', 1));
    return ($d === '' || in_array($d, PRODIT_PUBLIC_MAIL, true)) ? '' : $d;
}

/** Комнаты общего чата Production IT: 'backlog' — всем вошедшим; 'co:<домен>' — только сотрудникам домена. */
function chat_room_allowed(string $room): bool
{
    $acc = prodit_account(uid(true));
    if (!$acc) {
        return false;
    }
    if ($room === 'backlog') {
        return true;
    }
    return str_starts_with($room, 'co:') && substr($room, 3) !== '' && substr($room, 3) === prodit_domain($acc['email']);
}

/** push сообщения комнаты: чат компании — сотрудникам домена (кроме автора), бэклог — без push. */
function chat_room_push(string $room, string $sender, array $data): void
{
    if (!str_starts_with($room, 'co:')) {
        return;
    }
    $q = prodit_db()->prepare('SELECT author FROM prodit_accounts WHERE email LIKE ?');
    $q->execute(['%@' . substr($room, 3)]);
    foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $a) {
        if ($a !== $sender) {
            push_author($a, $data, false);
        }
    }
}

function prodit_company(): void
{
    $me = prodit_me();
    $d = prodit_domain($me['email']);
    if ($d === '') {
        json_out(['ok' => true, 'domain' => '', 'members' => []]);
        return;
    }
    $q = prodit_db()->prepare('SELECT a.author, a.email, COALESCE(u.name, \'\') AS name, COALESCE(u.seen, 0) AS seen
        FROM prodit_accounts a LEFT JOIN users u ON u.uid = a.uid WHERE a.email LIKE ? ORDER BY name, a.email');
    $q->execute(['%@' . $d]);
    $members = [];
    foreach ($q->fetchAll(PDO::FETCH_ASSOC) as $r) {
        if ($r['author'] === $me['author']) {
            continue;
        }
        $members[] = ['author' => $r['author'], 'email' => $r['email'], 'name' => $r['name'] ?: explode('@', $r['email'])[0],
            'online' => time() - (int)$r['seen'] < 300];
    }
    json_out(['ok' => true, 'domain' => $d, 'room' => 'co:' . $d, 'members' => $members]);
}

/** Публичное чтение «Бэклога» — для страницы spacecode.business/production-it/ (только чтение, без email). */
function prodit_backlog(): void
{
    $q = prodit_db()->prepare("SELECT id, name, text, ts FROM chat WHERE room = 'backlog' ORDER BY id DESC LIMIT 60");
    $q->execute();
    $items = array_map(fn($r) => ['id' => (int)$r['id'], 'name' => $r['name'] ?: 'Production IT', 'text' => $r['text'],
        'ts' => gmdate('c', (int)$r['ts'])], array_reverse($q->fetchAll(PDO::FETCH_ASSOC)));
    header('Access-Control-Allow-Origin: https://spacecode.business');
    json_out(['ok' => true, 'items' => $items]);
}

/** Напоминания: за ~10 минут до встречи — push участникам с приложением. Вызывает контейнер cron раз в минуту. */
function prodit_remind(): void
{
    $k = (string)getenv('RT_API_KEY');
    if ($k === '' || !hash_equals($k, (string)($_SERVER['HTTP_X_REMIND_KEY'] ?? ''))) {
        fail(401, 'Unauthorized');
    }
    $db = prodit_db();
    $db->exec('CREATE TABLE IF NOT EXISTS prodit_reminded (uid TEXT, start TEXT, PRIMARY KEY (uid, start))');
    $d = prodit_meet_call('list', null);
    $now = time();
    $sent = 0;
    foreach ($d['items'] ?? [] as $m) {
        $t = strtotime((string)$m['start']);
        if ($t === false || $t <= $now || $t > $now + 11 * 60) {
            continue;
        }
        $ins = $db->prepare('INSERT OR IGNORE INTO prodit_reminded VALUES (?, ?)');
        $ins->execute([(string)$m['uid'], (string)$m['start']]);
        if (!$ins->rowCount()) {
            continue;
        }
        $emails = array_map('strtolower', array_merge($m['emails'] ?? [], [(string)($m['organizer_email'] ?? '')]));
        $emails = array_values(array_unique(array_filter($emails)));
        if (!$emails) {
            continue;
        }
        $q = $db->prepare('SELECT author FROM prodit_accounts WHERE email IN (' . implode(',', array_fill(0, count($emails), '?')) . ')');
        $q->execute($emails);
        $room = basename(parse_url((string)$m['link'], PHP_URL_PATH));
        foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $a) {
            push_author($a, ['type' => 'meeting', 'title' => (string)$m['title'], 'room' => $room,
                'minutes' => (string)max(1, (int)round(($t - $now) / 60))], true);
            $sent++;
        }
    }
    json_out(['ok' => true, 'sent' => $sent]);
}

function prodit_lang(): string
{
    $l = (string)(prodit_body()['lang'] ?? ($_GET['lang'] ?? 'ru'));
    return in_array($l, ['ru', 'en', 'sr'], true) ? $l : 'ru';
}

const PRODIT_T = [
    'ru' => ['code_subj' => 'Код входа: %s — Production IT', 'code' => 'Ваш код для входа в приложение Production IT',
        'code_tip' => 'Код действует 15 минут. Если вы не запрашивали вход — просто удалите письмо.',
        'inv_subj' => '%s приглашает вас в Production IT', 'inv' => '%s хочет переписываться и созваниваться с вами в приложении Production IT.',
        'inv_btn' => 'Установить из Google Play', 'inv_tip' => 'Войдите по этому адресу почты — и диалог появится сам.'],
    'en' => ['code_subj' => 'Sign-in code: %s — Production IT', 'code' => 'Your sign-in code for the Production IT app',
        'code_tip' => 'The code is valid for 15 minutes. If you did not request it, just delete this email.',
        'inv_subj' => '%s invites you to Production IT', 'inv' => '%s wants to chat and call with you in the Production IT app.',
        'inv_btn' => 'Get it on Google Play', 'inv_tip' => 'Sign in with this email address and the chat will appear.'],
    'sr' => ['code_subj' => 'Код за пријаву: %s — Production IT', 'code' => 'Ваш код за пријаву у апликацију Production IT',
        'code_tip' => 'Код важи 15 минута. Ако нисте тражили пријаву — само обришите писмо.',
        'inv_subj' => '%s вас позива у Production IT', 'inv' => '%s жели да се дописује и позива са вама у апликацији Production IT.',
        'inv_btn' => 'Инсталирај са Google Play', 'inv_tip' => 'Пријавите се овом адресом и разговор ће се појавити.'],
];
const PRODIT_PLAY = 'https://play.google.com/store/apps/details?id=com.prodit.meet';

// ---------- почта (SMTP over SSL, основной ящик → запасной) ----------

function prodit_smtp(string $pfx, string $to, string $subject, string $text, string $html): bool
{
    $host = getenv($pfx . 'HOST');
    $user = getenv($pfx . 'USER');
    if (!$host || !$user) {
        return false;
    }
    $port = (int)(getenv($pfx . 'PORT') ?: 465);
    $s = @stream_socket_client(($port === 465 ? 'ssl://' : 'tcp://') . $host . ':' . $port, $en, $es, 15);
    if (!$s) {
        return false;
    }
    stream_set_timeout($s, 20);
    $read = function () use ($s) {
        $r = '';
        while (($l = fgets($s, 1024)) !== false) {
            $r .= $l;
            if (strlen($l) < 4 || $l[3] === ' ') {
                break;
            }
        }
        return (int)substr($r, 0, 3);
    };
    $cmd = function (string $c) use ($s, $read) {
        fwrite($s, $c . "\r\n");
        return $read();
    };
    $ok = $read() === 220 && $cmd('EHLO prodit') === 250;
    if ($ok && $port !== 465 && getenv($pfx . 'TLS') !== '0') {   // TLS=0 — только для локального теста
        $ok = $cmd('STARTTLS') === 220 && stream_socket_enable_crypto($s, true, STREAM_CRYPTO_METHOD_TLS_CLIENT) && $cmd('EHLO prodit') === 250;
    }
    $ok = $ok && $cmd('AUTH LOGIN') === 334 && $cmd(base64_encode($user)) === 334 && $cmd(base64_encode((string)getenv($pfx . 'PASS'))) === 235;
    $ok = $ok && $cmd("MAIL FROM:<$user>") === 250 && $cmd("RCPT TO:<$to>") === 250 && $cmd('DATA') === 354;
    if ($ok) {
        $b = bin2hex(random_bytes(12));
        $enc = fn($v) => '=?UTF-8?B?' . base64_encode($v) . '?=';
        $msg = 'From: ' . $enc('Production IT') . " <$user>\r\nTo: <$to>\r\nSubject: " . $enc($subject) . "\r\n"
            . 'Date: ' . date('r') . "\r\nMessage-ID: <" . bin2hex(random_bytes(8)) . '@' . substr(strrchr($user, '@'), 1) . ">\r\n"
            . "MIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=\"$b\"\r\n\r\n"
            . "--$b\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" . chunk_split(base64_encode($text))
            . "--$b\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" . chunk_split(base64_encode($html))
            . "--$b--\r\n.";
        $ok = $cmd($msg) === 250;
    }
    @$cmd('QUIT');
    fclose($s);
    return $ok;
}

function prodit_mail(string $to, string $subject, string $text, string $html): bool
{
    return prodit_smtp('SMTP_', $to, $subject, $text, $html) || prodit_smtp('SMTP2_', $to, $subject, $text, $html);
}

function prodit_card(string $inner): string
{
    return '<div style="font-family:Arial,sans-serif;background:#f5f5f9;padding:24px 12px"><div style="max-width:460px;margin:auto;'
        . 'background:#fff;border:1.5px solid #e3e6ec;border-radius:24px;padding:26px">'
        . '<div style="font:700 18px Arial;color:#5e27d4;letter-spacing:.03em">Production IT</div>' . $inner . '</div></div>';
}

// ---------- вход ----------

function prodit_email(string $v): string
{
    $e = strtolower(trim(mb_substr($v, 0, 254)));
    if (!preg_match('/^[^@\s<>,;]{1,64}@[^@\s<>,;]{1,190}\.[a-z]{2,}$/', $e)) {
        fail(400, 'bad email');
    }
    return $e;
}

/** Аккаунт для проверки Google Play: постоянный код из настроек сервера (REVIEW_EMAIL / REVIEW_CODE), письмо не шлётся. */
function prodit_review(string $email): ?string
{
    $re = strtolower((string)getenv('REVIEW_EMAIL'));
    $rc = (string)getenv('REVIEW_CODE');
    return ($re !== '' && preg_match('/^\d{6}$/', $rc) && $email === $re) ? $rc : null;
}

function prodit_auth_start(): void
{
    $email = prodit_email((string)(prodit_body()['email'] ?? ''));
    if (prodit_review($email) !== null) {
        json_out(['ok' => true]);
        return;
    }
    $ip = (string)($_SERVER['HTTP_X_REAL_IP'] ?? $_SERVER['REMOTE_ADDR'] ?? '');
    $db = prodit_db();
    $now = time();
    $q = $db->prepare('SELECT MAX(ts), COUNT(*) FROM prodit_codes WHERE email = ? AND ts > ?');
    $q->execute([$email, $now - 86400]);
    [$last, $nMail] = $q->fetch(PDO::FETCH_NUM);
    $q = $db->prepare('SELECT COUNT(*) FROM prodit_codes WHERE ip = ? AND ts > ?');
    $q->execute([$ip, $now - 86400]);
    $gap = getenv('PRODIT_CODE_GAP') !== false ? (int)getenv('PRODIT_CODE_GAP') : 50;   // секунд между письмами на один адрес
    if ($now - (int)$last < $gap) {
        fail(429, 'wait');
    }
    if ((int)$nMail >= 10 || (int)$q->fetchColumn() >= 30) {
        fail(429, 'daily limit');
    }
    $code = sprintf('%06d', random_int(0, 999999));
    $db->prepare('DELETE FROM prodit_codes WHERE exp < ?')->execute([$now - 86400 * 2]);
    $db->prepare('INSERT INTO prodit_codes VALUES (?,?,?,?,?,?)')
        ->execute([$email, hash('sha256', $email . ':' . $code), $now + 900, 0, $now, $ip]);
    $t = PRODIT_T[prodit_lang()];
    $html = prodit_card('<p style="font:15px Arial;color:#435971;margin:16px 0 10px">' . $t['code'] . '</p>'
        . '<div style="font:700 34px/1 \'Courier New\',monospace;letter-spacing:.25em;color:#1e1d30;background:#efeaff;border-radius:16px;'
        . 'padding:16px 0;text-align:center">' . $code . '</div><p style="font:13px Arial;color:#a1acb8;margin:16px 0 0">' . $t['code_tip'] . '</p>');
    if (!prodit_mail($email, sprintf($t['code_subj'], $code), $t['code'] . ': ' . $code . "\n\n" . $t['code_tip'], $html)) {
        fail(502, 'mail failed');
    }
    json_out(['ok' => true]);
}

function prodit_auth_verify(): void
{
    $b = prodit_body();
    $email = prodit_email((string)($b['email'] ?? ''));
    $code = substr(preg_replace('/\D/', '', (string)($b['code'] ?? '')), 0, 6);
    $db = prodit_db();
    $rc = prodit_review($email);
    if ($rc !== null && !hash_equals($rc, $code)) {
        fail(401, 'wrong code');
    }
    if ($rc === null) {
    $q = $db->prepare('SELECT rowid, * FROM prodit_codes WHERE email = ? AND exp > ? ORDER BY ts DESC LIMIT 1');
    $q->execute([$email, time()]);
    $row = $q->fetch(PDO::FETCH_ASSOC);
    if (!$row || (int)$row['tries'] >= 5) {
        fail(401, 'code expired');
    }
    if (!hash_equals($row['hash'], hash('sha256', $email . ':' . $code))) {
        $db->prepare('UPDATE prodit_codes SET tries = tries + 1 WHERE rowid = ?')->execute([$row['rowid']]);
        fail(401, 'wrong code');
    }
    $db->prepare('UPDATE prodit_codes SET exp = 0 WHERE email = ?')->execute([$email]);   // гасим, но строки нужны для лимитов
    }
    $q = $db->prepare('SELECT * FROM prodit_accounts WHERE email = ?');
    $q->execute([$email]);
    $acc = $q->fetch(PDO::FETCH_ASSOC);
    if (!$acc) {   // первый вход с этим email — новый постоянный X-UID (одинаковый на всех телефонах этого человека)
        $uid = 'p' . bin2hex(random_bytes(20));
        $acc = ['email' => $email, 'uid' => $uid, 'author' => author_id($uid), 'ts' => time()];
        $db->prepare('INSERT INTO prodit_accounts VALUES (?,?,?,?)')->execute(array_values($acc));
    }
    $name = trim(mb_substr((string)($b['name'] ?? ''), 0, 40));
    if ($name === '') {
        $q = $db->prepare('SELECT name FROM users WHERE uid = ?');
        $q->execute([$acc['uid']]);
        $name = (string)($q->fetchColumn() ?: '');
    }
    if ($name !== '') {
        $db->prepare('INSERT INTO users (uid, author, name, ts, seen) VALUES (?,?,?,?,?)
            ON CONFLICT(uid) DO UPDATE SET name = excluded.name, seen = excluded.seen')
            ->execute([$acc['uid'], $acc['author'], $name, time(), time()]);
    }
    json_out(['ok' => true, 'uid' => $acc['uid'], 'author' => $acc['author'], 'email' => $email, 'name' => $name,
        'domain' => prodit_domain($email)]);
}

function prodit_find(): void
{
    $me = prodit_me();
    $email = prodit_email((string)($_GET['email'] ?? ''));
    $q = prodit_db()->prepare('SELECT a.author, COALESCE(u.name, \'\') AS name FROM prodit_accounts a LEFT JOIN users u ON u.uid = a.uid WHERE a.email = ?');
    $q->execute([$email]);
    $r = $q->fetch(PDO::FETCH_ASSOC);
    if (!$r) {
        fail(404, 'not registered');
    }
    if ($r['author'] === $me['author']) {
        fail(400, 'self');
    }
    json_out(['ok' => true, 'author' => $r['author'], 'name' => $r['name'] ?: explode('@', $email)[0]]);
}

function prodit_invite(): void
{
    $me = prodit_me();
    $email = prodit_email((string)(prodit_body()['email'] ?? ''));
    $db = prodit_db();
    $q = $db->prepare('SELECT COUNT(*) FROM prodit_invites WHERE author = ? AND ts > ?');
    $q->execute([$me['author'], time() - 86400]);
    if ((int)$q->fetchColumn() >= 10) {
        fail(429, 'daily limit');
    }
    $q = $db->prepare('SELECT 1 FROM prodit_invites WHERE author = ? AND email = ? AND ts > ?');
    $q->execute([$me['author'], $email, time() - 86400]);
    if ($q->fetchColumn()) {
        json_out(['ok' => true, 'already' => true]);
        return;
    }
    $db->prepare('INSERT INTO prodit_invites VALUES (?,?,?)')->execute([$me['author'], $email, time()]);
    $q = $db->prepare('SELECT name FROM users WHERE uid = ?');
    $q->execute([$me['uid']]);
    $who = (string)($q->fetchColumn() ?: $me['email']);
    $t = PRODIT_T[prodit_lang()];
    $h = fn($v) => htmlspecialchars($v, ENT_QUOTES);
    $html = prodit_card('<p style="font:16px/1.5 Arial;color:#1e1d30;margin:16px 0">' . $h(sprintf($t['inv'], $who)) . '</p>'
        . '<a href="' . PRODIT_PLAY . '" style="display:block;text-align:center;background:#5e27d4;color:#fff;text-decoration:none;'
        . 'font:600 16px Arial;padding:14px;border-radius:40px">' . $t['inv_btn'] . '</a>'
        . '<p style="font:13px Arial;color:#697a8d;margin:14px 0 0">' . $t['inv_tip'] . ' (' . $h($email) . ')</p>');
    if (!prodit_mail($email, sprintf($t['inv_subj'], $who), sprintf($t['inv'], $who) . "\n\n" . PRODIT_PLAY . "\n\n" . $t['inv_tip'], $html)) {
        fail(502, 'mail failed');
    }
    json_out(['ok' => true]);
}

// ---------- встречи (календарь spacecode.business/meet) ----------

function prodit_meet_call(string $path, ?array $body): array
{
    $ch = curl_init(rtrim(prodit_setting('meet_api'), '/') . '/' . $path);
    $hdr = ['Content-Type: application/json', 'X-Real-IP: ' . ($_SERVER['HTTP_X_REAL_IP'] ?? $_SERVER['REMOTE_ADDR'] ?? '')];
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 30, CURLOPT_HTTPHEADER => $hdr]
        + ($body !== null ? [CURLOPT_POST => true, CURLOPT_POSTFIELDS => json_encode($body, JSON_UNESCAPED_UNICODE)] : []));
    $r = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    $d = is_string($r) ? json_decode($r, true) : null;
    if (!is_array($d)) {
        fail(502, 'meet api');
    }
    if ($code >= 400) {
        fail($code, (string)($d['error'] ?? 'meet api'));
    }
    return $d;
}

function prodit_meetings(): void
{
    $me = prodit_me();
    $d = prodit_meet_call('list', null);
    $items = [];
    foreach ($d['items'] ?? [] as $it) {
        $emails = array_map('strtolower', $it['emails'] ?? []);
        $it['mine'] = in_array($me['email'], $emails, true) || strtolower((string)($it['organizer_email'] ?? '')) === $me['email'];
        $it['room'] = basename(parse_url((string)$it['link'], PHP_URL_PATH));
        $items[] = $it;
    }
    json_out(['ok' => true, 'items' => $items]);
}

function prodit_meet_create(): void
{
    $me = prodit_me();
    $q = prodit_db()->prepare('SELECT name FROM users WHERE uid = ?');
    $q->execute([$me['uid']]);
    $b = prodit_body();
    $emails = array_values(array_filter(array_map('strval', is_array($b['emails'] ?? null) ? $b['emails'] : [])));
    if (!empty($b['with'])) {   // «Запланировать встречу» из диалога: собеседник по номеру автора
        $q = prodit_db()->prepare('SELECT email FROM prodit_accounts WHERE author = ?');
        $q->execute([(string)$b['with']]);
        $e = $q->fetchColumn();
        if (!$e) {
            fail(404, 'not registered');
        }
        $emails[] = $e;
    }
    $d = prodit_meet_call('create', [
        'key' => (string)getenv('MEET_KEY'), 'client' => 'prodit-' . $me['author'],
        'title' => (string)($b['title'] ?? ''), 'start' => (string)($b['start'] ?? ''), 'tz' => (string)($b['tz'] ?? 'Europe/Moscow'),
        'duration' => (int)($b['duration'] ?? 60), 'emails' => $emails, 'note' => (string)($b['note'] ?? ''),
        'organizer' => (string)($q->fetchColumn() ?: $me['email']), 'organizer_email' => $me['email'], 'lang' => prodit_lang(),
    ]);
    json_out($d);
}

// ---------- Centrifugo: мгновенная доставка (личка, звонки) ----------

function prodit_jwt(array $claims, string $secret): string
{
    $b64 = fn($s) => rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
    $h = $b64(json_encode(['alg' => 'HS256', 'typ' => 'JWT']));
    $p = $b64(json_encode($claims));
    return "$h.$p." . $b64(hash_hmac('sha256', "$h.$p", $secret, true));
}

function prodit_rt_token(): void
{
    $me = prodit_me();
    $secret = (string)getenv('RT_SECRET');
    if ($secret === '' || prodit_setting('rt_url') === '') {
        json_out(['ok' => true, 'url' => '']);
        return;
    }
    json_out(['ok' => true, 'url' => prodit_setting('rt_url'), 'channel' => 'dm:#' . $me['author'],
        'token' => prodit_jwt(['sub' => $me['author'], 'exp' => time() + 86400], $secret)]);
}

/** Вызывается из push_author(): то же событие — сразу в открытое приложение через Centrifugo. */
function rt_publish(string $author, array $data): void
{
    $api = getenv('RT_API');
    if (!$api) {
        return;
    }
    $ch = curl_init(rtrim($api, '/') . '/api/publish');
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 3, CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode(['channel' => 'dm:#' . $author, 'data' => $data + ['ts' => time()]], JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'X-API-Key: ' . getenv('RT_API_KEY')]]);
    curl_exec($ch);
    curl_close($ch);
}

// ---------- админка ----------

function prodit_admin(): void
{
    if (!key_ok()) {
        fail(401, 'Unauthorized');
    }
    $h = fn($v) => htmlspecialchars((string)$v, ENT_QUOTES);
    $saved = '';
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $st = prodit_db()->prepare('INSERT OR REPLACE INTO settings (k, v) VALUES (?, ?)');
        foreach (array_keys(PRODIT_DEFAULTS) as $k) {
            if (!array_key_exists($k, $_POST)) {   // поля нет в форме — настройку не трогаем
                continue;
            }
            $v = trim((string)$_POST[$k]);
            if ($v !== '' && in_array($k, ['token_url', 'rooms_url', 'meet_api'], true) && !preg_match('~^https://[^\s<>"]+$~', $v)) {
                $saved .= "<p class=err>{$h($k)}: нужен адрес https://…</p>";
                continue;
            }
            if ($v !== '' && in_array($k, ['livekit_url', 'rt_url'], true) && !preg_match('~^wss?://[^\s<>"]+$~', $v)) {
                $saved .= "<p class=err>{$h($k)}: нужен адрес wss://…</p>";
                continue;
            }
            $st->execute(['prodit_' . $k, $v === '' && $k === 'token_url' ? PRODIT_DEFAULTS[$k] : $v]);
        }
        $saved .= '<p class=ok>Сохранено. Приложения подхватят новые адреса при следующем запуске или звонке.</p>';
    }
    $check = '';
    if (isset($_GET['check'])) {   // проверка: получить токен тестовой комнаты по текущим настройкам
        $u = prodit_setting('token_url') . '?roomName=' . urlencode(prodit_setting('room_prefix') . 'check') . '&participantName=admin';
        $ch = curl_init($u);
        curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 10]);
        $r = curl_exec($ch);
        $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        $d = json_decode((string)$r, true);
        $check = $code === 200 && !empty($d['participantToken'])
            ? '<p class=ok>Токен получен, сервер LiveKit: ' . $h(prodit_setting('livekit_url') ?: ($d['serverUrl'] ?? '?')) . '</p>'
            : '<p class=err>Не удалось получить токен (HTTP ' . $code . '): ' . $h(mb_substr((string)$r, 0, 200)) . '</p>';
    }
    $n = prodit_db()->query('SELECT COUNT(*) FROM prodit_accounts')->fetchColumn();
    $rows = '';
    foreach (PRODIT_DEFAULTS as $k => $def) {
        $rows .= '<label>' . $h(PRODIT_LABELS[$k]) . '<input name="' . $k . '" value="' . $h(prodit_setting($k)) . '" placeholder="' . $h($def) . '"></label>';
    }
    $key = $h(request_key());
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-store');
    echo <<<HTML
<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Production IT · админка</title><style>
body{margin:0;font:15px/1.5 system-ui,sans-serif;background:#12111c;color:#f1f0f7}main{max-width:760px;margin:auto;padding:24px 18px}
h1{font-size:22px;margin:0 0 4px}.sub{color:#9c99b5;margin:0 0 18px}form{display:grid;gap:14px;background:#1a1928;border:1px solid #2f2c4a;border-radius:20px;padding:20px}
label{display:grid;gap:6px;color:#c7c5d9;font-size:13px}input{font:15px system-ui;padding:11px 14px;border-radius:12px;border:1px solid #36335a;background:#12111c;color:#f1f0f7}
.row{display:flex;gap:10px;flex-wrap:wrap}button,a.btn{font:600 15px system-ui;padding:11px 20px;border-radius:40px;border:0;background:#5e27d4;color:#fff;cursor:pointer;text-decoration:none}
a.btn{background:#2c2a47}.ok{color:#7ee2a8}.err{color:#ff8a8a}</style><main>
<h1>Production IT · настройки</h1><p class=sub>Пользователей приложения: {$n}</p>{$saved}{$check}
<form method=post action="?action=prodit_admin&key={$key}">{$rows}
<div class=row><button>Сохранить</button><a class=btn href="?action=prodit_admin&key={$key}&check=1">Проверить LiveKit</a>
<a class=btn href="?action=admin&key={$key}">Заявки и Telegram</a></div></form></main>
HTML;
}

// ---------- маршрутизация ----------

$prodit_action = (string)($_GET['action'] ?? '');
if (in_array($prodit_action, PRODIT_ACTIONS, true)) {
    $post = in_array($prodit_action, ['prodit_auth_start', 'prodit_auth_verify', 'prodit_invite', 'prodit_meet_create', 'prodit_remind'], true);
    if ($prodit_action !== 'prodit_admin' && $_SERVER['REQUEST_METHOD'] !== ($post ? 'POST' : 'GET')) {
        fail(405, 'method not allowed');
    }
    match ($prodit_action) {
        'prodit_config' => json_out(['ok' => true] + array_combine(array_keys(PRODIT_DEFAULTS), array_map('prodit_setting', array_keys(PRODIT_DEFAULTS)))),
        'prodit_auth_start' => prodit_auth_start(),
        'prodit_auth_verify' => prodit_auth_verify(),
        'prodit_find' => prodit_find(),
        'prodit_invite' => prodit_invite(),
        'prodit_meetings' => prodit_meetings(),
        'prodit_meet_create' => prodit_meet_create(),
        'prodit_rt_token' => prodit_rt_token(),
        'prodit_admin' => prodit_admin(),
        'prodit_company' => prodit_company(),
        'prodit_backlog' => prodit_backlog(),
        'prodit_remind' => prodit_remind(),
    };
    exit;
}
if (in_array($prodit_action, PRODIT_NEED_LOGIN, true)) {
    prodit_me();
}
