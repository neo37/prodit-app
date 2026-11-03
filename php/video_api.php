<?php
/**
 * Отдача видео по API — тот же формат, что у /api/media_list сайта, плюс стрим с перемоткой.
 *
 *   GET video_api.php?action=folders                      → {"ok":true,"folders":["review","files",…]}
 *   GET video_api.php?action=list&folder=review         → {"ok":true,"items":[{fname,url,type,size,mtime,poster}]}
 *   GET video_api.php?action=stream&folder=review&file=X → сам файл, поддерживает Range (206) для перемотки
 *   GET  video_api.php?action=stats&folder=F               → {"ok":true,"stats":{fname:{likes,comments,liked}}}
 *   POST video_api.php?action=like      {folder,file}       → {"ok":true,"liked":bool,"likes":N}
 *   GET  video_api.php?action=comments&folder=F&file=X     → {"ok":true,"items":[{name,text,ts,mine}]}
 *   POST video_api.php?action=comment   {folder,file,text,name} → {"ok":true,"comment":{…}}
 *        лайки/комментарии: пользователь — заголовок X-UID (его генерирует приложение); база SQLite вне сайта
 *   GET video_api.php?action=app&folder=review           → HTML-плеер (сетка + слайдшоу) для браузера и приложения
 *
 * Папки из PUBLIC_FOLDERS открыты всем; остальные — по ключу: заголовок X-API-Key: <ключ> или параметр ?key=<ключ>. Ключ из ?key= запоминается
 * в cookie на 30 дней, поэтому <video src> и повторные запросы идут уже без него.
 * Настройка — константы ниже. Папки только из белого списка FOLDERS.
 */

const API_KEY   = 'change-me';                 // свой ключ; пустая строка — без проверки (не надо так)
const MEDIA_DIR = __DIR__;                     // корень сайта; пути папок ниже — от него
const FOLDERS   = [                            // имя в API => папка на сервере
    'review' => 'review',
    'files'  => 'files',
];
const PUBLIC_FOLDERS = ['review', 'files'];    // открыты без ключа (эти файлы и так доступны по прямым ссылкам сайта)
const SOCIAL_DB = __DIR__ . '/../../vapi_data/social.sqlite';   // вне www — по ссылке не скачать
const UPLOADS   = __DIR__ . '/../../vapi_data/uploads';         // видео пользователей — тоже вне www
const AVATARS   = __DIR__ . '/../../vapi_data/avatars';         // аватарки чата (id = sha1(X-UID), 16 знаков)
const CRASHES   = __DIR__ . '/../../vapi_data/crashes';         // отчёты о падениях приложения (отправляются с согласия)
const SPACE_MAX_FILES = 3;                                       // до 3 видео в источнике пользователя
const SPACE_MAX_SECONDS = 180;                                   // каждое до 3 минут
const SPACE_MAX_BYTES = 250 * 1024 * 1024;
const SPACE_MAX_PHOTOS = 10;                                     // и до 10 фото
const SPACE_MAX_PHOTO_BYTES = 20 * 1024 * 1024;
const HIDE_AFTER_REPORTS = 3;
// 3.4: видео с телефона в чат/личку — кусками, до 500 МБ, хранится 30 дней, не входит в лимит «3 видео»
const SHARE_MAX_BYTES = 500 * 1024 * 1024;
const SHARE_MAX_TOTAL = 2048 * 1024 * 1024;                      // всего активных «общих» видео у одного источника
const SHARE_KEEP_DAYS = 30;
// 3.4: пуши Firebase Cloud Messaging — ключ сервисного аккаунта вне www (не в git, не в APK)
const FCM_SA = __DIR__ . '/../../vapi_data/fcm-service-account.json';                                    // жалобы от 3 разных людей — скрыть
const MIME      = [
    'mp4' => 'video/mp4', 'm4v' => 'video/mp4', 'webm' => 'video/webm', 'mov' => 'video/quicktime',
    'mkv' => 'video/x-matroska', 'jpg' => 'image/jpeg', 'jpeg' => 'image/jpeg', 'png' => 'image/png',
    'gif' => 'image/gif', 'webp' => 'image/webp',
];

function fail(int $code, string $error): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['ok' => false, 'error' => $error], JSON_UNESCAPED_UNICODE);
    exit;
}

function request_key(): string
{
    $key = $_SERVER['HTTP_X_API_KEY'] ?? ($_GET['key'] ?? ($_COOKIE['vapi_key'] ?? ''));
    return is_string($key) ? $key : '';
}

/** Мастер-ключ сайта — доступ ко всем папкам и модерация. */
function key_ok(): bool
{
    return API_KEY !== '' && hash_equals(API_KEY, request_key());
}

/** Папка пользователя u_<id>: открывается ключом этого источника (им делятся в чате). */
function space_row(string $folder): ?array
{
    if (!preg_match('/^u_([a-f0-9]{12})$/', $folder, $m)) {
        return null;
    }
    $q = social()->prepare('SELECT * FROM spaces WHERE id = ?');
    $q->execute([$m[1]]);
    return $q->fetch(PDO::FETCH_ASSOC) ?: null;
}

function folder_allowed(string $folder): bool
{
    if (in_array($folder, PUBLIC_FOLDERS, true) || key_ok()) {
        return true;
    }
    $sp = space_row($folder);
    return $sp !== null && hash_equals($sp['view_key'], request_key());
}

function check_key(): void
{
    $action = $_GET['action'] ?? 'list';
    if (in_array($action, ['folders', 'chat', 'chat_post', 'report', 'space_create', 'chat_delete', 'space_delete', 'avatar_set', 'avatar', 'crash', 'forget',
        'dm_threads', 'dm', 'dm_post', 'dm_delete', 'dm_block', 'idea', 'admin', 'admin_idea', 'admin_tg', 'tg_hook', 'dm_image', 'hello', 'users', 'push_register', 'admin_unblock_all', 'admin_check', 'admin_chat_close'], true)) {
        return;
    }
    $folder = $_GET['folder'] ?? '';
    if (!is_string($folder) || !folder_allowed($folder)) {
        fail(401, 'Unauthorized');
    }
    if (isset($_GET['key']) && key_ok() && ($_COOKIE['vapi_key'] ?? '') !== request_key()) {
        setcookie('vapi_key', request_key(), ['expires' => time() + 30 * 86400, 'path' => '/',
            'secure' => true, 'httponly' => true, 'samesite' => 'Lax']);
    }
}

function folder_path(): string
{
    $folder = $_GET['folder'] ?? '';
    if (is_string($folder) && space_row($folder) !== null) {
        $dir = UPLOADS . '/' . substr($folder, 2);
        if (!is_dir($dir)) {
            mkdir($dir, 0700, true);
        }
        return realpath($dir);
    }
    if (!is_string($folder) || !array_key_exists($folder, FOLDERS)) {
        fail(400, 'unknown folder');
    }
    $dir = realpath(MEDIA_DIR . '/' . FOLDERS[$folder]);
    if ($dir === false || !is_dir($dir)) {
        fail(404, 'folder not found');
    }
    return $dir;
}

function media_type(string $name): ?string
{
    return MIME[strtolower(pathinfo($name, PATHINFO_EXTENSION))] ?? null;
}

function action_list(): void
{
    $dir = folder_path();
    $folder = $_GET['folder'];
    $items = [];
    foreach (scandir($dir) as $name) {
        $path = $dir . '/' . $name;
        $mime = media_type($name);
        if ($name[0] === '.' || !is_file($path) || $mime === null || is_hidden($folder, $name)) {
            continue;
        }
        // превью: posters/<имя>.jpg|png рядом с роликами (если есть), иначе приложение берёт кадр из видео
        $poster = null;
        foreach (['jpg', 'png', 'webp'] as $ext) {
            $pp = 'posters/' . pathinfo($name, PATHINFO_FILENAME) . '.' . $ext;
            if (is_file($dir . '/' . $pp)) {
                $poster = '/' . FOLDERS[$folder] . '/' . $pp;
                break;
            }
        }
        $items[] = [
            'fname' => $name,
            'url'   => '?action=stream&folder=' . rawurlencode($folder) . '&file=' . rawurlencode($name),
            'type'  => str_starts_with($mime, 'video/') ? 'video' : 'image',
            'size'  => filesize($path),
            'mtime' => filemtime($path),
            'poster' => $poster ?? (str_starts_with($mime, 'image/') ? '?action=stream&folder=' . rawurlencode($folder) . '&file=' . rawurlencode($name) : null),
        ];
    }
    usort($items, fn($a, $b) => $b['mtime'] <=> $a['mtime']);   // свежие первыми
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode(['ok' => true, 'items' => $items], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

function action_stream(): void
{
    $dir = folder_path();
    $name = $_GET['file'] ?? '';
    if (!is_string($name) || $name === '' || $name !== basename($name) || $name[0] === '.') {
        fail(400, 'bad file name');
    }
    $path = realpath($dir . '/' . $name);
    if ($path === false || dirname($path) !== $dir || !is_file($path)) {
        fail(404, 'not found');
    }
    $mime = media_type($name);
    if ($mime === null) {
        fail(415, 'unsupported type');
    }

    $size = filesize($path);
    $start = 0;
    $end = $size - 1;
    $etag = '"' . md5($path . $size . filemtime($path)) . '"';

    header('Content-Type: ' . $mime);
    header('Accept-Ranges: bytes');
    header('ETag: ' . $etag);
    header('Cache-Control: private, max-age=86400');
    header('Content-Disposition: inline; filename="' . rawurlencode($name) . '"');

    // Range: bytes=START-END | bytes=START- | bytes=-SUFFIX
    if (isset($_SERVER['HTTP_RANGE'])) {
        if (!preg_match('/^bytes=(\d*)-(\d*)$/', trim($_SERVER['HTTP_RANGE']), $m) || ($m[1] === '' && $m[2] === '')) {
            header("Content-Range: bytes */$size");
            fail(416, 'bad range');
        }
        if ($m[1] === '') {                       // последние N байт
            $start = max(0, $size - (int)$m[2]);
        } else {
            $start = (int)$m[1];
            if ($m[2] !== '') {
                $end = min((int)$m[2], $size - 1);
            }
        }
        if ($start > $end || $start >= $size) {
            header("Content-Range: bytes */$size");
            fail(416, 'range not satisfiable');
        }
        http_response_code(206);
        header("Content-Range: bytes $start-$end/$size");
    }
    header('Content-Length: ' . ($end - $start + 1));
    if ($_SERVER['REQUEST_METHOD'] === 'HEAD') {
        return;
    }

    @set_time_limit(0);
    while (ob_get_level()) {
        ob_end_clean();
    }
    $fp = fopen($path, 'rb');
    fseek($fp, $start);
    $left = $end - $start + 1;
    while ($left > 0 && !feof($fp) && !connection_aborted()) {
        $chunk = fread($fp, min(1 << 16, $left));
        echo $chunk;
        flush();
        $left -= strlen($chunk);
    }
    fclose($fp);
}

// ---------- лайки и комментарии ----------

function social(): PDO
{
    static $db = null;
    if ($db === null) {
        $dir = dirname(SOCIAL_DB);
        if (!is_dir($dir)) {
            mkdir($dir, 0700, true);
        }
        $db = new PDO('sqlite:' . SOCIAL_DB, null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
        $db->exec('CREATE TABLE IF NOT EXISTS likes (folder TEXT, file TEXT, uid TEXT, ts INTEGER, PRIMARY KEY(folder, file, uid))');
        $db->exec('CREATE TABLE IF NOT EXISTS comments (id INTEGER PRIMARY KEY, folder TEXT, file TEXT, uid TEXT, name TEXT, text TEXT, ts INTEGER)');
        $db->exec('CREATE INDEX IF NOT EXISTS c_file ON comments(folder, file)');
        $db->exec('CREATE TABLE IF NOT EXISTS spaces (id TEXT PRIMARY KEY, owner TEXT, name TEXT, view_key TEXT, ts INTEGER)');
        $db->exec('CREATE TABLE IF NOT EXISTS chat (id INTEGER PRIMARY KEY, uid TEXT, name TEXT, text TEXT, ts INTEGER,
                   share_name TEXT, share_url TEXT, share_key TEXT)');
        try {   // ссылка на конкретное видео в сообщении (добавлено позже)
            $db->exec('ALTER TABLE chat ADD COLUMN share_file TEXT');
        } catch (PDOException $e) {
        }
        $db->exec('CREATE TABLE IF NOT EXISTS reports (kind TEXT, ref TEXT, uid TEXT, reason TEXT, ts INTEGER, PRIMARY KEY(kind, ref, uid))');
        // личные сообщения: отправитель — X-UID, получатель — его анонимный номер author_id
        $db->exec('CREATE TABLE IF NOT EXISTS dm (id INTEGER PRIMARY KEY, uid TEXT, src TEXT, dst TEXT, name TEXT, dst_name TEXT,
                   text TEXT, ts INTEGER, seen INTEGER DEFAULT 0, share_name TEXT, share_url TEXT, share_key TEXT, share_file TEXT)');
        $db->exec('CREATE INDEX IF NOT EXISTS dm_pair ON dm(src, dst)');
        $db->exec('CREATE INDEX IF NOT EXISTS dm_dst ON dm(dst)');
        $db->exec('CREATE TABLE IF NOT EXISTS dm_blocks (who TEXT, blocked TEXT, PRIMARY KEY(who, blocked))');
        // заявки «💡 Идея» из приложения — смотрятся в админке (action=admin, мастер-ключ)
        $db->exec('CREATE TABLE IF NOT EXISTS ideas (id INTEGER PRIMARY KEY, uid TEXT, name TEXT, contact TEXT, text TEXT,
                   lang TEXT, app TEXT, ts INTEGER, status TEXT DEFAULT \'new\', note TEXT DEFAULT \'\')');
        try {   // картинка в личке — ответ из Telegram (добавлено позже)
            $db->exec('ALTER TABLE dm ADD COLUMN image TEXT');
        } catch (PDOException $e) {
        }
        // настройки админки (токен бота, чат) и связи «сообщение в Telegram → кому из пользователей отвечать»
        $db->exec('CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT)');
        $db->exec('CREATE TABLE IF NOT EXISTS tg_msgs (chat TEXT, msg INTEGER, author TEXT, idea INTEGER, ts INTEGER, PRIMARY KEY(chat, msg))');
        $db->exec('CREATE TABLE IF NOT EXISTS tg_topics (chat TEXT, author TEXT, thread INTEGER, PRIMARY KEY(chat, author))');
        // 3.3: каталог пользователей приложения (контакты → «Все пользователи»); hidden — человек скрыл себя
        $db->exec('CREATE TABLE IF NOT EXISTS users (uid TEXT PRIMARY KEY, author TEXT, name TEXT, ts INTEGER, seen INTEGER,
                   hidden INTEGER DEFAULT 0)');
        $db->exec('CREATE INDEX IF NOT EXISTS users_seen ON users(seen)');
        // 3.4: токены FCM устройств (author — анонимный номер, ему шлём личку/звонки/комментарии)
        $db->exec('CREATE TABLE IF NOT EXISTS push_tokens (token TEXT PRIMARY KEY, uid TEXT, author TEXT, app TEXT, ts INTEGER)');
        $db->exec('CREATE INDEX IF NOT EXISTS push_author ON push_tokens(author)');
        // 02.11.2026: общий чат очищали целиком — SQLite начал номера заново с 1, а приложения помнят «видел до №91»
        // и не замечали новые. Один раз сдвигаем номера за 1000 (дальше нумерация продолжается оттуда).
        if (!$db->query("SELECT 1 FROM settings WHERE k = 'chat_floor'")->fetchColumn()) {
            $db->exec('UPDATE chat SET id = id + 1000 WHERE id < 1000');
            $db->exec("UPDATE reports SET ref = CAST(CAST(ref AS INTEGER) + 1000 AS TEXT) WHERE kind = 'chat' AND CAST(ref AS INTEGER) < 1000");
            $db->exec("INSERT OR REPLACE INTO settings (k, v) VALUES ('chat_floor', '1000')");
        }
        // 02.11.2026 (вторая очистка): сообщения, пришедшие до выкладки с номерами < 2000, — один раз за 2000
        if (!$db->query("SELECT 1 FROM settings WHERE k = 'chat_floor2'")->fetchColumn()) {
            $db->exec('UPDATE chat SET id = id + 2000 WHERE id < 2000');
            $db->exec("UPDATE reports SET ref = CAST(CAST(ref AS INTEGER) + 2000 AS TEXT) WHERE kind = 'chat' AND CAST(ref AS INTEGER) < 2000");
            $db->exec("INSERT OR REPLACE INTO settings (k, v) VALUES ('chat_floor2', '1')");
        }
    }
    return $db;
}

function uid(bool $required): string
{
    $uid = $_SERVER['HTTP_X_UID'] ?? '';
    if (!is_string($uid) || !preg_match('/^[A-Za-z0-9_-]{8,64}$/', $uid)) {
        if ($required) {
            fail(400, 'X-UID required');
        }
        return '';
    }
    // служебный «отправитель» поддержки — только с мастер-ключом (иначе любой мог бы писать в личку от имени поддержки)
    if ($uid === SUPPORT_UID && !key_ok()) {
        fail(403, 'reserved uid');
    }
    return $uid;
}

function json_out(array $data): void
{
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

/** Имя файла из запроса — только существующий файл этой папки. */
function social_file(): string
{
    $dir = folder_path();
    $name = $_GET['file'] ?? '';
    if (!is_string($name) || $name === '' || $name !== basename($name) || !is_file($dir . '/' . $name)) {
        fail(404, 'not found');
    }
    return $name;
}

function action_stats(): void
{
    folder_path();
    $folder = $_GET['folder'];
    $uid = uid(false);
    $stats = [];
    $q = social()->prepare('SELECT file, COUNT(*) c, SUM(uid = ?) mine FROM likes WHERE folder = ? GROUP BY file');
    $q->execute([$uid, $folder]);
    foreach ($q as $r) {
        $stats[$r['file']] = ['likes' => (int)$r['c'], 'comments' => 0, 'liked' => (int)$r['mine'] > 0];
    }
    $q = social()->prepare('SELECT file, COUNT(*) c FROM comments WHERE folder = ? GROUP BY file');
    $q->execute([$folder]);
    foreach ($q as $r) {
        $stats[$r['file']] = $stats[$r['file']] ?? ['likes' => 0, 'comments' => 0, 'liked' => false];
        $stats[$r['file']]['comments'] = (int)$r['c'];
    }
    json_out(['ok' => true, 'stats' => (object)$stats]);
}

function action_like(): void
{
    $file = social_file();
    $folder = $_GET['folder'];
    $uid = uid(true);
    $db = social();
    $del = $db->prepare('DELETE FROM likes WHERE folder = ? AND file = ? AND uid = ?');
    $del->execute([$folder, $file, $uid]);
    $liked = $del->rowCount() === 0;
    if ($liked) {
        $db->prepare('INSERT INTO likes VALUES (?, ?, ?, ?)')->execute([$folder, $file, $uid, time()]);
    }
    $c = $db->prepare('SELECT COUNT(*) FROM likes WHERE folder = ? AND file = ?');
    $c->execute([$folder, $file]);
    json_out(['ok' => true, 'liked' => $liked, 'likes' => (int)$c->fetchColumn()]);
}

function action_comments(): void
{
    $file = social_file();
    $uid = uid(false);
    $order = ($_GET['sort'] ?? '') === 'old' ? 'ASC' : 'DESC';
    $q = social()->prepare("SELECT id, uid, name, text, ts FROM comments WHERE folder = ? AND file = ?
        AND (SELECT COUNT(*) FROM reports r WHERE r.kind = 'comment' AND r.ref = CAST(comments.id AS TEXT)) < " . (int)HIDE_AFTER_REPORTS . "
        ORDER BY id $order LIMIT 500");
    $q->execute([$_GET['folder'], $file]);
    $items = [];
    foreach ($q as $r) {
        $items[] = ['name' => $r['name'] !== '' ? $r['name'] : 'Guest', 'text' => $r['text'],
            'ts' => gmdate('c', (int)$r['ts']), 'mine' => $uid !== '' && $r['uid'] === $uid, 'author' => author_id($r['uid']), 'id' => (int)$r['id']];
    }
    json_out(['ok' => true, 'items' => $items]);
}

function action_comment(): void
{
    $file = social_file();
    $uid = uid(true);
    $text = trim(mb_substr((string)($_GET['text'] ?? ''), 0, 2000));
    $name = trim(mb_substr((string)($_GET['name'] ?? ''), 0, 60));
    if ($text === '') {
        fail(400, 'empty comment');
    }
    $ts = time();
    social()->prepare('INSERT INTO comments (folder, file, uid, name, text, ts) VALUES (?, ?, ?, ?, ?, ?)')
        ->execute([$_GET['folder'], $file, $uid, $name, $text, $ts]);
    $sp = space_row((string)$_GET['folder']);   // комментарий к видео пользователя — пуш владельцу
    if ($sp !== null && $sp['owner'] !== $uid) {
        $owner = author_id($sp['owner']);
        push_later(fn() => push_author($owner, ['type' => 'comment', 'name' => $name !== '' ? $name : 'Guest',
            'text' => mb_substr($text, 0, 150), 'file' => $file]));
    }
    json_out(['ok' => true, 'comment' => ['name' => $name !== '' ? $name : 'Guest', 'text' => $text,
        'ts' => gmdate('c', $ts), 'mine' => true]]);
}

// ---------- источники пользователей: до 3 видео по 3 минуты ----------

/** Длительность MP4/MOV в секундах по атому moov/mvhd (ffmpeg на хостинге нет). null — не разобрать. */
function mp4_duration(string $path): ?float
{
    $f = fopen($path, 'rb');
    if (!$f) {
        return null;
    }
    $size = filesize($path);
    $find = function (int $from, int $to, string $type) use ($f): ?array {
        $pos = $from;
        while ($pos + 8 <= $to) {
            fseek($f, $pos);
            $h = fread($f, 8);
            if (strlen($h) < 8) {
                return null;
            }
            $len = unpack('N', substr($h, 0, 4))[1];
            $t = substr($h, 4, 4);
            $hdr = 8;
            if ($len === 1) {
                $ext = unpack('J', fread($f, 8))[1];
                $len = $ext;
                $hdr = 16;
            } elseif ($len === 0) {
                $len = $to - $pos;
            }
            if ($len < $hdr) {
                return null;
            }
            if ($t === $type) {
                return [$pos + $hdr, $pos + $len];
            }
            $pos += $len;
        }
        return null;
    };
    $moov = $find(0, $size, 'moov');
    $mvhd = $moov ? $find($moov[0], $moov[1], 'mvhd') : null;
    if (!$mvhd) {
        fclose($f);
        return null;
    }
    fseek($f, $mvhd[0]);
    $b = fread($f, 32);
    fclose($f);
    $ver = ord($b[0]);
    if ($ver === 1) {
        $scale = unpack('N', substr($b, 20, 4))[1];
        $dur = unpack('J', substr($b, 24, 8))[1];
    } else {
        $scale = unpack('N', substr($b, 12, 4))[1];
        $dur = unpack('N', substr($b, 16, 4))[1];
    }
    return $scale > 0 ? $dur / $scale : null;
}

function is_hidden(string $folder, string $file): bool
{
    static $hidden = null;
    if ($hidden === null) {
        $hidden = [];
        $q = social()->prepare("SELECT ref FROM reports WHERE kind = 'file' GROUP BY ref HAVING COUNT(*) >= " . (int)HIDE_AFTER_REPORTS);
        $q->execute();
        foreach ($q as $r) {
            $hidden[$r['ref']] = true;
        }
    }
    return isset($hidden[$folder . '/' . $file]);
}

/** Создать свой источник: владелец — X-UID, ключ просмотра — чтобы делиться. */
function action_space_create(): void
{
    $uid = uid(true);
    $name = trim(mb_substr((string)($_GET['name'] ?? ''), 0, 60));
    $q = social()->prepare('SELECT COUNT(*) FROM spaces WHERE owner = ?');
    $q->execute([$uid]);
    if ((int)$q->fetchColumn() >= 3) {
        fail(429, 'too many sources');
    }
    $id = bin2hex(random_bytes(6));
    $key = rtrim(strtr(base64_encode(random_bytes(18)), '+/', '-_'), '=');
    social()->prepare('INSERT INTO spaces VALUES (?, ?, ?, ?, ?)')
        ->execute([$id, $uid, $name !== '' ? $name : 'My videos', $key, time()]);
    json_out(['ok' => true, 'folder' => 'u_' . $id, 'key' => $key, 'name' => $name !== '' ? $name : 'My videos',
        'max_files' => SPACE_MAX_FILES, 'max_seconds' => SPACE_MAX_SECONDS, 'max_photos' => SPACE_MAX_PHOTOS]);
}

function own_space(): array
{
    $sp = space_row((string)($_GET['folder'] ?? ''));
    if ($sp === null || !hash_equals($sp['owner'], uid(true))) {
        fail(403, 'not your source');
    }
    return $sp;
}

/** Загрузка в свой источник (multipart: folder, video|photo): до 3 видео ≤ 3 мин и до 10 фото. */
function action_upload(): void
{
    own_space();
    $dir = folder_path();
    $up = $_FILES['video'] ?? ($_FILES['photo'] ?? ($_FILES['file'] ?? null));
    if (!$up || $up['error'] !== UPLOAD_ERR_OK) {
        fail(400, 'no file (error ' . ($up['error'] ?? '-') . ')');
    }
    $ext = strtolower(pathinfo($up['name'], PATHINFO_EXTENSION));
    $isVideo = in_array($ext, ['mp4', 'm4v', 'mov'], true);
    $isPhoto = in_array($ext, ['jpg', 'jpeg', 'png', 'webp'], true);
    if (!$isVideo && !$isPhoto) {
        fail(415, 'only mp4/mov or jpg/png/webp');
    }
    $same = 0;
    foreach (scandir($dir) as $n) {
        if ($n[0] === '.' || str_ends_with($n, '.part') || str_starts_with($n, 'share_')) {
            continue;   // «общие» видео из чата (share_*) в лимит источника не входят
        }
        $e = strtolower(pathinfo($n, PATHINFO_EXTENSION));
        $same += $isVideo ? (int)in_array($e, ['mp4', 'm4v', 'mov'], true) : (int)in_array($e, ['jpg', 'jpeg', 'png', 'webp'], true);
    }
    if ($isVideo && $same >= SPACE_MAX_FILES) {
        fail(409, 'limit: ' . SPACE_MAX_FILES . ' videos');
    }
    if ($isPhoto && $same >= SPACE_MAX_PHOTOS) {
        fail(409, 'limit: ' . SPACE_MAX_PHOTOS . ' photos');
    }
    $dur = null;
    if ($isVideo) {
        if ($up['size'] > SPACE_MAX_BYTES) {
            fail(413, 'file too large');
        }
        $dur = mp4_duration($up['tmp_name']);
        if ($dur === null) {
            fail(415, 'not a video');
        }
        if ($dur > SPACE_MAX_SECONDS + 1) {
            fail(413, 'video longer than ' . SPACE_MAX_SECONDS . ' s');
        }
    } else {
        if ($up['size'] > SPACE_MAX_PHOTO_BYTES) {
            fail(413, 'file too large');
        }
        $info = @getimagesize($up['tmp_name']);
        if (!$info || !in_array($info[2], [IMAGETYPE_JPEG, IMAGETYPE_PNG, IMAGETYPE_WEBP], true)) {
            fail(415, 'not an image');
        }
    }
    $base = preg_replace('/[^A-Za-z0-9._-]+/', '_', pathinfo($up['name'], PATHINFO_FILENAME));
    $name = date('Ymd_His') . '_' . substr($base, 0, 40) . '.' . ($ext === 'jpeg' ? 'jpg' : $ext);
    if (!move_uploaded_file($up['tmp_name'], $dir . '/' . $name)) {
        fail(500, 'save failed');
    }
    json_out(['ok' => true, 'fname' => $name, 'type' => $isVideo ? 'video' : 'image',
        'seconds' => $dur === null ? null : round($dur, 1),
        'left' => ($isVideo ? SPACE_MAX_FILES : SPACE_MAX_PHOTOS) - $same - 1]);
}

/** Удалить видео: владелец источника — своё, мастер-ключ — любое (модерация). */
function action_delete(): void
{
    if (!key_ok()) {
        own_space();
    }
    $dir = folder_path();
    $name = (string)($_GET['file'] ?? '');
    if ($name === '' || $name !== basename($name) || !is_file($dir . '/' . $name)) {
        fail(404, 'not found');
    }
    unlink($dir . '/' . $name);
    json_out(['ok' => true]);
}

// ---------- общий чат ----------

function action_chat(): void
{
    $since = max(0, (int)($_GET['since'] ?? 0));
    $uid = uid(false);
    $q = social()->prepare("SELECT c.* FROM chat c WHERE c.id > ? AND
        (SELECT COUNT(*) FROM reports r WHERE r.kind = 'chat' AND r.ref = CAST(c.id AS TEXT)) < " . (int)HIDE_AFTER_REPORTS . "
        ORDER BY c.id DESC LIMIT 100");
    $q->execute([$since]);
    $items = [];
    foreach (array_reverse($q->fetchAll(PDO::FETCH_ASSOC)) as $r) {
        $m = ['id' => (int)$r['id'], 'name' => $r['name'] !== '' ? $r['name'] : 'Guest', 'text' => $r['text'],
            'ts' => gmdate('c', (int)$r['ts']), 'mine' => $uid !== '' && $r['uid'] === $uid];
        $m['author'] = author_id($r['uid']); // анонимный номер автора — для блокировки на стороне пользователя
        $aid = avatar_id($r['uid']);
        if (is_file(AVATARS . '/' . $aid . '.jpg')) {
            $m['avatar'] = $aid;
        }
        if ($r['share_url']) {
            $m['share'] = ['name' => $r['share_name'], 'url' => $r['share_url'], 'key' => $r['share_key']];
        }
        if (!empty($r['share_file'])) {
            $m['share']['file'] = $r['share_file'];
        }
        $items[] = $m;
    }
    json_out(['ok' => true, 'items' => $items, 'closed' => setting('chat_closed') === '1']);
}

function action_chat_post(): void
{
    $uid = uid(true);
    $text = trim(mb_substr((string)($_GET['text'] ?? ''), 0, 500));
    $name = trim(mb_substr((string)($_GET['name'] ?? ''), 0, 40));
    $surl = trim((string)($_GET['share_url'] ?? ''));
    $sfile = trim((string)($_GET['share_file'] ?? ''));
    if ($text === '' && $surl === '') {
        fail(400, 'empty message');
    }
    if (setting('chat_closed') === '1' && !key_ok()) {   // 3.9: админ закрыл общий чат
        fail(423, 'chat closed');
    }
    if (($surl !== '' && !preg_match('~^https://[^\s]+$~', $surl)) || ($sfile !== '' && !preg_match('~^https://[^\s]+$~', $sfile))) {
        fail(400, 'bad link');
    }
    $q = social()->prepare('SELECT MAX(ts) FROM chat WHERE uid = ?');
    $q->execute([$uid]);
    if (time() - (int)$q->fetchColumn() < 3) {
        fail(429, 'too fast');
    }
    // номер сообщения — всегда больше любого прежнего и не ниже 2000: после очистки чата SQLite начал бы с 1,
    // а приложения помнят «видел до №…» и не заметили бы новые (02.11.2026)
    $id = max((int)social()->query('SELECT COALESCE(MAX(id), 0) FROM chat')->fetchColumn(), (int)setting('chat_last'), 2000) + 1;
    social()->prepare('INSERT INTO chat (id, uid, name, text, ts, share_name, share_url, share_key, share_file) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
        ->execute([$id, $uid, $name, $text, time(), mb_substr((string)($_GET['share_name'] ?? ''), 0, 60), $surl,
            mb_substr((string)($_GET['share_key'] ?? ''), 0, 64), mb_substr($sfile, 0, 500)]);
    set_setting('chat_last', (string)$id);
    $pushText = $text !== '' ? $text : '🎬 ' . (string)($_GET['share_name'] ?? '');
    $author = author_id($uid);
    push_later(fn() => push_topic('chat', ['type' => 'chat', 'name' => $name !== '' ? $name : 'Guest',
        'text' => mb_substr($pushText, 0, 150), 'author' => $author]));
    json_out(['ok' => true, 'id' => $id]);
}

// ---------- личные сообщения ----------

/** Сообщение лички в том же формате, что сообщение общего чата (+ to — номер собеседника). */
function dm_item(array $r, string $me): array
{
    $m = ['id' => (int)$r['id'], 'name' => $r['name'] !== '' ? $r['name'] : 'Guest', 'text' => $r['text'],
        'ts' => gmdate('c', (int)$r['ts']), 'mine' => $r['src'] === $me, 'author' => $r['src'], 'seen' => (bool)$r['seen']];
    $aid = avatar_id($r['uid']);
    if (is_file(AVATARS . '/' . $aid . '.jpg')) {
        $m['avatar'] = $aid;
    }
    if ($r['share_url']) {
        $m['share'] = ['name' => $r['share_name'], 'url' => $r['share_url'], 'key' => $r['share_key']];
        if (!empty($r['share_file'])) {
            $m['share']['file'] = $r['share_file'];
        }
    }
    if (!empty($r['image'])) {   // 3.2+: показывает картинку; старым версиям — подпись вместо пустого сообщения
        $m['image'] = $r['image'];
        if ($m['text'] === '') {
            $m['text'] = '🖼';
        }
    }
    return $m;
}

function peer_arg(string $f): string
{
    $p = (string)($_GET[$f] ?? '');
    if (!preg_match('/^[a-f0-9]{16}$/', $p)) {
        fail(400, 'bad peer');
    }
    return $p;
}

/** Мои диалоги: собеседник, его имя и аватар, последнее сообщение, сколько непрочитанных. */
function action_dm_threads(): void
{
    $me = author_id(uid(true));
    $q = social()->prepare('SELECT * FROM dm WHERE id IN (SELECT MAX(id) FROM dm WHERE src = ? OR dst = ?
        GROUP BY CASE WHEN src = ? THEN dst ELSE src END) ORDER BY id DESC LIMIT 200');
    $q->execute([$me, $me, $me]);
    $unread = social()->prepare('SELECT src, COUNT(*) FROM dm WHERE dst = ? AND seen = 0 GROUP BY src');
    $unread->execute([$me]);
    $un = $unread->fetchAll(PDO::FETCH_KEY_PAIR);
    $names = social()->prepare('SELECT name, uid FROM dm WHERE src = ? ORDER BY id DESC LIMIT 1');
    $dn = social()->prepare("SELECT dst_name FROM dm WHERE src = ? AND dst = ? AND dst_name <> '' ORDER BY id DESC LIMIT 1");
    $items = [];
    $total = 0;
    foreach ($q->fetchAll(PDO::FETCH_ASSOC) as $r) {
        $peer = $r['src'] === $me ? $r['dst'] : $r['src'];
        $names->execute([$peer]);   // имя и аватар — из последнего сообщения собеседника, если он писал
        $pr = $names->fetch(PDO::FETCH_ASSOC);
        if (!$pr) {   // собеседник ещё не отвечал — имя, под которым я ему писал
            $dn->execute([$me, $peer]);
        }
        $t = ['peer' => $peer, 'name' => $pr ? $pr['name'] : ($dn->fetchColumn() ?: 'Guest'),
            'text' => $r['text'] !== '' ? $r['text'] : ($r['share_name'] ? '📁 ' . $r['share_name'] : ''),
            'ts' => gmdate('c', (int)$r['ts']), 'mine' => $r['src'] === $me, 'unread' => (int)($un[$peer] ?? 0)];
        if ($pr && is_file(AVATARS . '/' . avatar_id($pr['uid']) . '.jpg')) {
            $t['avatar'] = avatar_id($pr['uid']);
        }
        $total += $t['unread'];
        $items[] = $t;
    }
    user_seen(uid(true));
    json_out(['ok' => true, 'me' => $me, 'unread' => $total, 'items' => $items, 'now' => gmdate('c')]);
}

/** Переписка с собеседником (with=номер) после since; входящие отмечаются прочитанными. */
function action_dm(): void
{
    $me = author_id(uid(true));
    $peer = peer_arg('with');
    $since = max(0, (int)($_GET['since'] ?? 0));
    $q = social()->prepare('SELECT * FROM dm WHERE id > ? AND ((src = ? AND dst = ?) OR (src = ? AND dst = ?)) ORDER BY id DESC LIMIT 200');
    $q->execute([$since, $me, $peer, $peer, $me]);
    $items = [];
    foreach (array_reverse($q->fetchAll(PDO::FETCH_ASSOC)) as $r) {
        $items[] = dm_item($r, $me);
    }
    social()->prepare('UPDATE dm SET seen = 1 WHERE src = ? AND dst = ? AND seen = 0')->execute([$peer, $me]);
    json_out(['ok' => true, 'items' => $items]);
}

function action_dm_post(): void
{
    $uid = uid(true);
    $me = author_id($uid);
    $to = peer_arg('to');
    $text = trim(mb_substr((string)($_GET['text'] ?? ''), 0, 2000));
    $surl = trim((string)($_GET['share_url'] ?? ''));
    $sfile = trim((string)($_GET['share_file'] ?? ''));
    if ($to === $me) {
        fail(400, 'self');
    }
    if ($text === '' && $surl === '') {
        fail(400, 'empty message');
    }
    if (($surl !== '' && !preg_match('~^https://[^\s]+$~', $surl)) || ($sfile !== '' && !preg_match('~^https://[^\s]+$~', $sfile))) {
        fail(400, 'bad link');
    }
    $b = social()->prepare('SELECT 1 FROM dm_blocks WHERE who = ? AND blocked = ?');
    $b->execute([$to, $me]);
    if ($b->fetchColumn()) {
        fail(403, 'blocked');
    }
    $q = social()->prepare('SELECT MAX(ts) FROM dm WHERE uid = ?');
    $q->execute([$uid]);
    if (time() - (int)$q->fetchColumn() < 1) {
        fail(429, 'too fast');
    }
    social()->prepare('INSERT INTO dm (uid, src, dst, name, dst_name, text, ts, share_name, share_url, share_key, share_file)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
        ->execute([$uid, $me, $to, trim(mb_substr((string)($_GET['name'] ?? ''), 0, 40)),
            trim(mb_substr((string)($_GET['to_name'] ?? ''), 0, 40)), $text, time(),
            mb_substr((string)($_GET['share_name'] ?? ''), 0, 60), $surl, mb_substr((string)($_GET['share_key'] ?? ''), 0, 64),
            mb_substr($sfile, 0, 500)]);
    $id = (int)social()->lastInsertId();
    $isCall = str_starts_with($text, '📞') && str_contains($text, '/rooms/');
    push_later(fn() => push_author($to, ['type' => $isCall ? 'call' : 'dm'], true));
    if ($to === support_id()) {   // пишут поддержке — в Telegram, в ту же ветку, что и заявки этого человека
        $name = trim(mb_substr((string)($_GET['name'] ?? ''), 0, 40));
        $body = $text !== '' ? $text : '📁 ' . (string)($_GET['share_name'] ?? '');
        tg_to_author($me, '✉ <b>' . tg_h($name !== '' ? $name : 'Guest') . '</b> пишет:' . "\n" . tg_h($body), tg_last_idea($me));
    }
    json_out(['ok' => true, 'id' => $id]);
}

/** Удалить своё личное сообщение (у обоих). */
function action_dm_delete(): void
{
    $q = social()->prepare('DELETE FROM dm WHERE id = ? AND uid = ?');
    $q->execute([(int)($_GET['ref'] ?? 0), uid(true)]);
    if ($q->rowCount() === 0) {
        fail(403, 'not your message');
    }
    json_out(['ok' => true]);
}

/** Запретить (on=1) или разрешить (on=0) собеседнику писать мне. */
function action_dm_block(): void
{
    $me = author_id(uid(true));
    $to = peer_arg('to');
    if ((string)($_GET['on'] ?? '1') === '0') {
        social()->prepare('DELETE FROM dm_blocks WHERE who = ? AND blocked = ?')->execute([$me, $to]);
    } else {
        social()->prepare('INSERT OR IGNORE INTO dm_blocks VALUES (?, ?)')->execute([$me, $to]);
    }
    json_out(['ok' => true]);
}

// ---------- каталог пользователей (3.3) ----------

/** Отметка «был в сети» — не чаще раза в минуту; только для тех, кто уже есть в каталоге. */
function user_seen(string $uid): void
{
    $t = time();
    social()->prepare('UPDATE users SET seen = ? WHERE uid = ? AND seen < ?')->execute([$t, $uid, $t - 60]);
}

/** Однократно: в каталог — все, кто уже писал в общий чат или в личку (старые версии приложения hello не шлют). */
function users_backfill(): void
{
    $db = social();
    $done = $db->query("SELECT v FROM settings WHERE k = 'users_backfill'")->fetchColumn();
    if ($done) {
        return;
    }
    foreach (['chat', 'dm'] as $t) {
        $db->exec("INSERT OR IGNORE INTO users (uid, name, ts, seen)
            SELECT uid, name, MIN(ts), MAX(ts) FROM $t WHERE uid <> '' GROUP BY uid");
        $db->exec("UPDATE users SET name = (SELECT name FROM $t WHERE $t.uid = users.uid AND name <> '' ORDER BY id DESC LIMIT 1)
            WHERE (name IS NULL OR name = '') AND uid IN (SELECT uid FROM $t)");
    }
    $q = $db->query("SELECT uid FROM users WHERE author IS NULL");
    $u = $db->prepare('UPDATE users SET author = ? WHERE uid = ?');
    foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $id) {
        $u->execute([author_id($id), $id]);
    }
    $db->exec("INSERT OR REPLACE INTO settings VALUES ('users_backfill', '1')");
}

/** Приложение при запуске: имя и видимость в каталоге (hidden=1 — не показывать меня в «Все пользователи»). */
function action_hello(): void
{
    $uid = uid(true);
    $name = trim(mb_substr((string)($_GET['name'] ?? ''), 0, 40));
    $hidden = (string)($_GET['hidden'] ?? '0') === '1' ? 1 : 0;
    social()->prepare('INSERT INTO users (uid, author, name, ts, seen, hidden) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(uid) DO UPDATE SET name = excluded.name, seen = excluded.seen, hidden = excluded.hidden')
        ->execute([$uid, author_id($uid), $name, time(), time(), $hidden]);
    json_out(['ok' => true, 'me' => author_id($uid)]);
}

/** Все пользователи: имя, аватар, когда был в сети. q — поиск по имени; без безымянных и скрывшихся. */
function action_users(): void
{
    $uid = uid(true);
    users_backfill();
    user_seen($uid);
    $me = author_id($uid);
    $q = trim(mb_substr((string)($_GET['q'] ?? ''), 0, 40));
    $off = max(0, (int)($_GET['offset'] ?? 0));
    $sql = "SELECT * FROM users WHERE hidden = 0 AND name <> '' AND name <> 'Guest' AND uid <> ?
        AND author NOT IN (SELECT who FROM dm_blocks WHERE blocked = ?)";
    $args = [$uid, $me];
    if ($q !== '') {
        $sql .= ' AND name LIKE ?';
        $args[] = '%' . str_replace(['%', '_'], ['\\%', '\\_'], $q) . '%';
        $sql .= " ESCAPE '\\'";
    }
    $st = social()->prepare($sql . ' ORDER BY seen DESC LIMIT 101 OFFSET ' . $off);
    $st->execute($args);
    $rows = $st->fetchAll(PDO::FETCH_ASSOC);
    $items = [];
    foreach (array_slice($rows, 0, 100) as $r) {
        $u = ['author' => $r['author'], 'name' => $r['name'], 'seen' => gmdate('c', (int)$r['seen']),
            'online' => time() - (int)$r['seen'] < 300];
        if (is_file(AVATARS . '/' . avatar_id($r['uid']) . '.jpg')) {
            $u['avatar'] = avatar_id($r['uid']);
        }
        $items[] = $u;
    }
    $total = social()->query("SELECT COUNT(*) FROM users WHERE hidden = 0 AND name <> '' AND name <> 'Guest'")->fetchColumn();
    json_out(['ok' => true, 'items' => $items, 'more' => count($rows) > 100, 'total' => (int)$total, 'now' => gmdate('c')]);
}

// ---------- заявки «💡 Идея» и админка ----------

function action_idea(): void
{
    $uid = uid(true);
    $text = trim(mb_substr((string)($_GET['text'] ?? ''), 0, 3000));
    if (mb_strlen($text) < 5) {
        fail(400, 'empty idea');
    }
    $q = social()->prepare('SELECT COUNT(*) FROM ideas WHERE uid = ? AND ts > ?');
    $q->execute([$uid, time() - 86400]);
    if ((int)$q->fetchColumn() >= 20) {   // не больше 20 заявок в сутки с устройства
        fail(429, 'too many');
    }
    $name = trim(mb_substr((string)($_GET['name'] ?? ''), 0, 60));
    social()->prepare('INSERT INTO ideas (uid, name, contact, text, lang, app, ts) VALUES (?, ?, ?, ?, ?, ?, ?)')
        ->execute([$uid, $name, trim(mb_substr((string)($_GET['contact'] ?? ''), 0, 120)),
            $text, mb_substr((string)($_GET['lang'] ?? ''), 0, 10), mb_substr((string)($_GET['app'] ?? ''), 0, 20), time()]);
    $id = (int)social()->lastInsertId();
    $out = ['ok' => true, 'id' => $id];
    if (tg_on()) {
        // заявка — первым сообщением в личке с поддержкой: ответы из Telegram приходят в эту же переписку
        social()->prepare('INSERT INTO dm (uid, src, dst, name, dst_name, text, ts) VALUES (?, ?, ?, ?, ?, ?, ?)')
            ->execute([$uid, author_id($uid), support_id(), $name, support_name(), '💡 #' . $id . "\n" . $text, time()]);
        tg_notify_idea($id);
        $out['reply_to'] = support_id();
        $out['reply_name'] = support_name();
    }
    json_out($out);
}

/** Сменить статус заявки (new/work/done) и заметку или удалить (status=delete). Только мастер-ключ. */
function action_admin_idea(): void
{
    if (!key_ok()) {
        fail(401, 'Unauthorized');
    }
    $id = (int)($_GET['id'] ?? 0);
    $st = (string)($_GET['status'] ?? '');
    if ($st === 'delete') {
        social()->prepare('DELETE FROM ideas WHERE id = ?')->execute([$id]);
    } elseif (in_array($st, ['new', 'work', 'done'], true)) {
        social()->prepare('UPDATE ideas SET status = ?, note = ? WHERE id = ?')
            ->execute([$st, mb_substr((string)($_GET['note'] ?? ''), 0, 1000), $id]);
    } else {
        fail(400, 'bad status');
    }
    json_out(['ok' => true]);
}

// ---------- Telegram: заявки уходят в чат, ответы (текст, фото) — пользователю в личку ----------
//
// Бот и чат задаются в админке. Если чат — группа с темами, у каждого пользователя своя тема: всё, что
// написано в ней, уходит ему. Иначе отвечают реплаем на сообщение заявки или «#12 текст».
// Ответы принимает вебхук action=tg_hook (проверка — секрет в заголовке X-Telegram-Bot-Api-Secret-Token).

const TG_MEDIA = __DIR__ . '/../../vapi_data/tg_media';   // картинки из ответов — вне www, отдаёт action=dm_image
const SUPPORT_UID = 'support-telegram';                    // «отправитель» ответов поддержки в личке
const TG_API = 'https://api.telegram.org';

function setting(string $k): string
{
    $q = social()->prepare('SELECT v FROM settings WHERE k = ?');
    $q->execute([$k]);
    return (string)($q->fetchColumn() ?: '');
}

function set_setting(string $k, string $v): void
{
    social()->prepare('INSERT OR REPLACE INTO settings VALUES (?, ?)')->execute([$k, $v]);
}

function support_id(): string
{
    return author_id(SUPPORT_UID);
}

function support_name(): string
{
    return setting('tg_name') ?: 'Smart Player';
}

function tg_on(): bool
{
    return setting('tg_token') !== '' && setting('tg_chat') !== '';
}

function tg_h(string $s): string
{
    return htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE);
}

/** HTTP-запрос (POST JSON, если передан $json); null — нет ответа. */
function tg_http(string $url, ?string $json, int $timeout): ?string
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => $timeout, CURLOPT_CONNECTTIMEOUT => 4]);
    if ($json !== null) {
        curl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => $json, CURLOPT_HTTPHEADER => ['Content-Type: application/json']]);
    }
    $r = curl_exec($ch);
    curl_close($ch);
    return is_string($r) ? $r : null;
}

/** Метод Bot API; ошибка запоминается для админки. */
function tg_api(string $method, array $p = [], int $timeout = 8, ?string $token = null): array
{
    $token ??= setting('tg_token');
    $r = tg_http(TG_API . '/bot' . $token . '/' . $method, json_encode((object)$p, JSON_UNESCAPED_UNICODE), $timeout);
    $j = $r === null ? null : json_decode($r, true);
    if (!is_array($j)) {
        $j = ['ok' => false, 'description' => 'нет ответа от Telegram'];
    }
    if (empty($j['ok']) && $method !== 'setMessageReaction') {
        set_setting('tg_last_error', gmdate('d.m H:i') . ' UTC · ' . $method . ': ' . ($j['description'] ?? '?'));
    }
    return $j;
}

/** Кнопки статуса под заявкой; текущий отмечен точкой. */
function tg_buttons(int $id, string $st): array
{
    $row = [];
    foreach (['work' => '🔧 В работу', 'done' => '✅ Готово', 'new' => '🆕 Новая'] as $k => $l) {
        $row[] = ['text' => ($k === $st ? '● ' : '') . $l, 'callback_data' => "st:$k:$id"];
    }
    return ['inline_keyboard' => [$row]];
}

/** Тема пользователя в группе с темами (создаётся при первой заявке); 0 — чат без тем. */
function tg_thread(string $author, string $title, bool $fresh = false): int
{
    if (setting('tg_forum') !== '1') {
        return 0;
    }
    $chat = setting('tg_chat');
    if (!$fresh) {
        $q = social()->prepare('SELECT thread FROM tg_topics WHERE chat = ? AND author = ?');
        $q->execute([$chat, $author]);
        if ($t = (int)$q->fetchColumn()) {
            return $t;
        }
    }
    $r = tg_api('createForumTopic', ['chat_id' => $chat, 'name' => mb_substr('💡 ' . ($title !== '' ? $title : 'Guest') . ' · ' . substr($author, 0, 4), 0, 120)]);
    $t = (int)($r['result']['message_thread_id'] ?? 0);
    if ($t) {
        social()->prepare('INSERT OR REPLACE INTO tg_topics VALUES (?, ?, ?)')->execute([$chat, $author, $t]);
    }
    return $t;
}

/** Сообщение в чат про пользователя (в его тему, если темы включены) + связь «это сообщение → этот пользователь». */
function tg_to_author(string $author, string $html, int $idea, array $extra = [], string $title = ''): bool
{
    if (!tg_on()) {
        return false;
    }
    $chat = setting('tg_chat');
    $p = ['chat_id' => $chat, 'text' => mb_substr($html, 0, 4000), 'parse_mode' => 'HTML', 'link_preview_options' => ['is_disabled' => true]] + $extra;
    if ($title === '') {
        $title = tg_author_name($author);
    }
    if ($t = tg_thread($author, $title)) {
        $p['message_thread_id'] = $t;
    }
    $r = tg_api('sendMessage', $p);
    if (empty($r['ok']) && isset($p['message_thread_id']) && stripos($r['description'] ?? '', 'thread') !== false) {
        $p['message_thread_id'] = tg_thread($author, $title, true);   // тему удалили в Telegram — заводим заново
        $r = tg_api('sendMessage', $p);
    }
    if (empty($r['ok'])) {
        return false;
    }
    social()->prepare('INSERT OR REPLACE INTO tg_msgs VALUES (?, ?, ?, ?, ?)')
        ->execute([$chat, (int)$r['result']['message_id'], $author, $idea, time()]);
    return true;
}

function tg_author_name(string $author): string
{
    $q = social()->prepare('SELECT name FROM dm WHERE src = ? AND name <> \'\' ORDER BY id DESC LIMIT 1');
    $q->execute([$author]);
    return (string)($q->fetchColumn() ?: '');
}

/** Последняя заявка пользователя, о которой знает чат (для подписи ответа). */
function tg_last_idea(string $author): int
{
    $q = social()->prepare('SELECT idea FROM tg_msgs WHERE author = ? AND idea > 0 ORDER BY ts DESC LIMIT 1');
    $q->execute([$author]);
    return (int)$q->fetchColumn();
}

function tg_notify_idea(int $id): void
{
    $q = social()->prepare('SELECT * FROM ideas WHERE id = ?');
    $q->execute([$id]);
    $i = $q->fetch(PDO::FETCH_ASSOC);
    if (!$i) {
        return;
    }
    $n = social()->prepare('SELECT COUNT(*) FROM ideas WHERE uid = ?');
    $n->execute([$i['uid']]);
    $n = (int)$n->fetchColumn();
    $how = setting('tg_forum') === '1' ? 'Ответ — просто сообщением в этой теме' : 'Ответ — реплаем на это сообщение';
    $html = '💡 <b>Заявка #' . $id . '</b>' . ($n > 1 ? ' · ' . $n . '-я от этого человека' : '') . "\n\n" . tg_h($i['text']) . "\n\n"
        . '👤 ' . tg_h($i['name'] ?: 'Guest') . ($i['contact'] !== '' ? ' · ' . tg_h($i['contact']) : '')
        . ' · ' . tg_h(trim($i['lang'] . ' ' . $i['app'])) . "\n<i>" . $how . ': текст и фото уйдут в приложение.</i>';
    tg_to_author(author_id($i['uid']), $html, $id, ['reply_markup' => tg_buttons($id, $i['status'])], (string)$i['name']);
}

/** Картинку из Telegram — к себе (вне www); имя случайное, по нему её и отдаём. */
function tg_download(string $fileId): ?string
{
    $f = tg_api('getFile', ['file_id' => $fileId]);
    $path = (string)($f['result']['file_path'] ?? '');
    if ($path === '' || (int)($f['result']['file_size'] ?? 0) > 20 * 1024 * 1024) {
        return null;
    }
    $data = tg_http(TG_API . '/file/bot' . setting('tg_token') . '/' . $path, null, 30);
    $info = $data ? @getimagesizefromstring($data) : false;
    $ext = [IMAGETYPE_JPEG => 'jpg', IMAGETYPE_PNG => 'png', IMAGETYPE_WEBP => 'webp', IMAGETYPE_GIF => 'gif'][$info[2] ?? 0] ?? null;
    if ($ext === null) {
        return null;
    }
    if (!is_dir(TG_MEDIA)) {
        mkdir(TG_MEDIA, 0700, true);
    }
    $name = bin2hex(random_bytes(16)) . '.' . $ext;
    file_put_contents(TG_MEDIA . '/' . $name, $data);
    return $name;
}

/** Ответ бота в том же месте чата (та же тема), реплаем на сообщение. */
function tg_reply(array $m, string $text): void
{
    $p = ['chat_id' => $m['chat']['id'], 'text' => $text, 'parse_mode' => 'HTML',
        'reply_parameters' => ['message_id' => $m['message_id'], 'allow_sending_without_reply' => true]];
    if (!empty($m['is_topic_message'])) {
        $p['message_thread_id'] = $m['message_thread_id'];
    }
    tg_api('sendMessage', $p);
}

/** Вебхук Telegram: отвечаем 200 сразу, разбираем после — чтобы очередь апдейтов не копилась. */
function action_tg_hook(): void
{
    $secret = setting('tg_secret');
    if ($secret === '' || !hash_equals($secret, (string)($_SERVER['HTTP_X_TELEGRAM_BOT_API_SECRET_TOKEN'] ?? ''))) {
        fail(403, 'forbidden');
    }
    $u = json_decode(file_get_contents('php://input') ?: '', true);
    json_out(['ok' => true]);
    if (function_exists('fastcgi_finish_request')) {
        fastcgi_finish_request();
    }
    try {
        if (is_array($u)) {
            tg_update($u);
        }
    } catch (Throwable $e) {
        set_setting('tg_last_error', gmdate('d.m H:i') . ' UTC · webhook: ' . $e->getMessage());
    }
}

function tg_update(array $u): void
{
    if (isset($u['callback_query'])) {
        tg_callback($u['callback_query']);
        return;
    }
    $m = $u['message'] ?? null;
    if (!is_array($m) || !isset($m['chat']['id']) || !empty($m['from']['is_bot'])) {
        return;
    }
    $chat = (string)$m['chat']['id'];
    $text = trim((string)($m['text'] ?? ($m['caption'] ?? '')));
    if ($chat !== setting('tg_chat')) {
        // чужой чат: только подсказать его id — чтобы вписать в админку; запоминаем, админка их покажет
        $seen = json_decode(setting('tg_seen') ?: '{}', true) ?: [];
        $seen[$chat] = (string)($m['chat']['title'] ?? trim(($m['chat']['first_name'] ?? '') . ' ' . ($m['chat']['username'] ?? '')));
        set_setting('tg_seen', json_encode(array_slice($seen, -10, null, true), JSON_UNESCAPED_UNICODE));
        if ($text !== '' && $text[0] === '/') {
            tg_reply($m, 'ID этого чата: <code>' . tg_h($chat) . "</code>\nВпишите его в админке «Умного плеера» — заявки будут приходить сюда.");
        }
        return;
    }
    if (preg_match('~^/(new|list)\b~', $text)) {
        tg_list($m);
        return;
    }
    if (preg_match('~^/(start|help|id)\b~', $text)) {
        tg_reply($m, "Сюда приходят заявки из «💡 Идея».\n"
            . (setting('tg_forum') === '1' ? 'У каждого пользователя своя тема — всё, что вы в ней пишете, уходит ему в приложение.'
                : 'Ответить: реплай на сообщение заявки или «#номер текст».')
            . "\nТекст и фото (с подписью) доставляются в личку приложения. /new — открытые заявки.");
        return;
    }
    $author = null;
    $idea = 0;
    if (isset($m['reply_to_message']['message_id'])) {   // реплай на заявку или на прошлое сообщение переписки
        $q = social()->prepare('SELECT author, idea FROM tg_msgs WHERE chat = ? AND msg = ?');
        $q->execute([$chat, (int)$m['reply_to_message']['message_id']]);
        if ($r = $q->fetch(PDO::FETCH_ASSOC)) {
            [$author, $idea] = [$r['author'], (int)$r['idea']];
        }
    }
    if ($author === null && !empty($m['is_topic_message'])) {   // сообщение в теме пользователя
        $q = social()->prepare('SELECT author FROM tg_topics WHERE chat = ? AND thread = ?');
        $q->execute([$chat, (int)$m['message_thread_id']]);
        if ($a = $q->fetchColumn()) {
            [$author, $idea] = [$a, tg_last_idea($a)];
        }
    }
    if ($author === null && preg_match('/^#(\d+)\s*/u', $text, $mm)) {   // «#12 текст»
        $q = social()->prepare('SELECT uid FROM ideas WHERE id = ?');
        $q->execute([(int)$mm[1]]);
        if ($uid = $q->fetchColumn()) {
            [$author, $idea, $text] = [author_id($uid), (int)$mm[1], trim(substr($text, strlen($mm[0])))];
        }
    }
    if ($author === null) {
        if (isset($m['reply_to_message']) || !empty($m['is_topic_message']) || $text === '' || $text[0] === '#' || $m['chat']['type'] === 'private') {
            tg_reply($m, 'Не понял, кому это. Ответьте реплаем на сообщение заявки или начните с «#номер».');
        }
        return;
    }
    $img = null;
    if (!empty($m['photo'])) {
        $img = tg_download((string)end($m['photo'])['file_id']);   // последний размер — самый крупный
    } elseif (str_starts_with((string)($m['document']['mime_type'] ?? ''), 'image/')) {
        $img = tg_download((string)$m['document']['file_id']);
    }
    if ($text === '' && $img === null) {
        tg_reply($m, '⚠️ Не отправлено: в приложение уходят только текст и фото.');
        return;
    }
    $b = social()->prepare('SELECT 1 FROM dm_blocks WHERE who = ? AND blocked = ?');
    $b->execute([$author, support_id()]);
    if ($b->fetchColumn()) {
        tg_reply($m, '⛔ Пользователь запретил писать ему — не доставлено.');
        return;
    }
    social()->prepare('INSERT INTO dm (uid, src, dst, name, dst_name, text, ts, image) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
        ->execute([SUPPORT_UID, support_id(), $author, support_name(), '', mb_substr($text, 0, 2000), time(), $img]);
    social()->prepare('INSERT OR REPLACE INTO tg_msgs VALUES (?, ?, ?, ?, ?)')->execute([$chat, (int)$m['message_id'], $author, $idea, time()]);
    tg_api('setMessageReaction', ['chat_id' => $chat, 'message_id' => $m['message_id'],
        'reaction' => [['type' => 'emoji', 'emoji' => '👍']]]);   // 👍 = доставлено в приложение
}

/** Кнопки статуса под заявкой. */
function tg_callback(array $q): void
{
    $chat = (string)($q['message']['chat']['id'] ?? '');
    if ($chat !== setting('tg_chat') || !preg_match('/^st:(new|work|done):(\d+)$/', (string)($q['data'] ?? ''), $m)) {
        tg_api('answerCallbackQuery', ['callback_query_id' => $q['id']]);
        return;
    }
    social()->prepare('UPDATE ideas SET status = ? WHERE id = ?')->execute([$m[1], (int)$m[2]]);
    tg_api('editMessageReplyMarkup', ['chat_id' => $chat, 'message_id' => $q['message']['message_id'],
        'reply_markup' => tg_buttons((int)$m[2], $m[1])]);
    tg_api('answerCallbackQuery', ['callback_query_id' => $q['id'],
        'text' => '#' . $m[2] . ': ' . ['new' => 'новая', 'work' => 'в работе', 'done' => 'готово'][$m[1]]]);
}

/** /new — открытые заявки списком (новые и в работе). */
function tg_list(array $m): void
{
    $rows = social()->query("SELECT id, name, text, status FROM ideas WHERE status IN ('new', 'work') ORDER BY id DESC LIMIT 40")
        ->fetchAll(PDO::FETCH_ASSOC);
    $out = [];
    foreach ($rows as $r) {
        $out[] = ($r['status'] === 'new' ? '🆕' : '🔧') . ' <b>#' . $r['id'] . '</b> ' . tg_h($r['name'] ?: 'Guest') . ': '
            . tg_h(mb_substr(preg_replace('/\s+/u', ' ', $r['text']), 0, 70));
    }
    tg_reply($m, $out ? "Открытые заявки (" . count($out) . "):\n" . implode("\n", $out) : 'Открытых заявок нет 🎉');
}

/** Картинка из ответа поддержки (имя случайное — знает только получатель). */
function action_dm_image(): void
{
    $f = (string)($_GET['f'] ?? '');
    if (!preg_match('/^[a-f0-9]{32}\.(jpg|png|webp|gif)$/', $f, $m) || !is_file(TG_MEDIA . '/' . $f)) {
        fail(404, 'not found');
    }
    header('Content-Type: ' . MIME[$m[1]]);
    header('Cache-Control: private, max-age=86400');
    header('Content-Length: ' . filesize(TG_MEDIA . '/' . $f));
    readfile(TG_MEDIA . '/' . $f);
}

/** Настройка бота из админки: op=save (токен, чат, имя) | test | off. */
function action_admin_tg(): void
{
    if (!key_ok()) {
        fail(401, 'Unauthorized');
    }
    $op = (string)($_GET['op'] ?? 'save');
    if ($op === 'off') {
        if (setting('tg_token') !== '') {
            tg_api('deleteWebhook');
        }
        foreach (['tg_token', 'tg_chat', 'tg_bot', 'tg_forum', 'tg_chat_title', 'tg_last_error'] as $k) {
            set_setting($k, '');
        }
        json_out(['ok' => true, 'msg' => 'Telegram отключён']);
        return;
    }
    if ($op === 'test') {
        if (!tg_on()) {
            fail(400, 'Сначала сохраните токен и ID чата');
        }
        $r = tg_api('sendMessage', ['chat_id' => setting('tg_chat'), 'text' => '✅ Проверка связи: бот «Умного плеера» на месте.']);
        json_out(['ok' => !empty($r['ok']), 'msg' => !empty($r['ok']) ? 'Сообщение отправлено' : 'Ошибка: ' . ($r['description'] ?? '?')]);
        return;
    }
    $token = trim((string)($_GET['token'] ?? '')) ?: setting('tg_token');   // пустое поле — токен прежний
    $chat = trim((string)($_GET['chat'] ?? ''));
    if (!preg_match('/^\d+:[A-Za-z0-9_-]{30,}$/', $token)) {
        fail(400, 'Токен не похож на токен бота (вида 123456:ABC…) — его выдаёт @BotFather');
    }
    if ($chat !== '' && !preg_match('/^-?\d+$/', $chat)) {
        fail(400, 'ID чата — число, у групп с минусом: -100…');
    }
    $me = tg_api('getMe', [], 8, $token);
    if (empty($me['ok'])) {
        fail(400, 'Telegram не принял токен: ' . ($me['description'] ?? '?'));
    }
    $secret = setting('tg_secret') ?: bin2hex(random_bytes(16));
    $hook = 'https://' . $_SERVER['HTTP_HOST'] . strtok($_SERVER['REQUEST_URI'], '?') . '?action=tg_hook';
    $wh = tg_api('setWebhook', ['url' => $hook, 'secret_token' => $secret, 'allowed_updates' => ['message', 'callback_query'],
        'max_connections' => 10], 8, $token);
    if (empty($wh['ok'])) {
        fail(400, 'Не удалось подключить вебхук: ' . ($wh['description'] ?? '?'));
    }
    set_setting('tg_token', $token);
    set_setting('tg_secret', $secret);
    set_setting('tg_bot', (string)$me['result']['username']);
    set_setting('tg_name', trim(mb_substr((string)($_GET['name'] ?? ''), 0, 40)) ?: 'Smart Player');
    set_setting('tg_last_error', '');
    $msg = ['Бот @' . $me['result']['username'] . ' подключён, вебхук включён.'];
    set_setting('tg_chat', $chat);
    if ($chat === '') {
        $msg[] = 'Теперь добавьте бота в группу, напишите там /id — бот ответит ID чата, впишите его и сохраните ещё раз.';
        json_out(['ok' => true, 'msg' => implode("\n", $msg)]);
        return;
    }
    $c = tg_api('getChat', ['chat_id' => $chat]);
    if (empty($c['ok'])) {
        set_setting('tg_chat', '');
        fail(400, 'Бот не видит чат ' . $chat . ': добавьте его в группу (' . ($c['description'] ?? '?') . ')');
    }
    $forum = !empty($c['result']['is_forum']);
    set_setting('tg_forum', $forum ? '1' : '');
    set_setting('tg_chat_title', (string)($c['result']['title'] ?? ($c['result']['first_name'] ?? $chat)));
    if ($forum) {
        $cm = tg_api('getChatMember', ['chat_id' => $chat, 'user_id' => $me['result']['id']]);
        if (($cm['result']['status'] ?? '') !== 'administrator' || empty($cm['result']['can_manage_topics'])) {
            set_setting('tg_forum', '');
            $msg[] = '⚠️ В группе включены темы, но бот не админ с правом «Управление темами» — пока работаем реплаями. Дайте право и сохраните ещё раз.';
        } else {
            $msg[] = 'Группа с темами: у каждого пользователя будет своя тема.';
        }
    } elseif (($c['result']['type'] ?? '') !== 'private') {
        $msg[] = 'Темы в группе выключены — отвечайте реплаем на заявку. Совет: включите темы и сделайте бота админом с правом «Управление темами».';
    }
    $t = tg_api('sendMessage', ['chat_id' => $chat, 'text' => "✅ Бот подключён к «Умному плееру».\nСюда будут приходить заявки из «💡 Идея». /help — как отвечать."]);
    $msg[] = !empty($t['ok']) ? 'Тестовое сообщение отправлено в «' . setting('tg_chat_title') . '».' : '⚠️ Сообщение в чат не ушло: ' . ($t['description'] ?? '?');
    json_out(['ok' => true, 'msg' => implode("\n", $msg)]);
}

/** Блок «Telegram» в админке. */
function admin_tg_html(callable $h): string
{
    $token = setting('tg_token');
    $state = '⚪ не настроен';
    $extra = '';
    if ($token !== '') {
        $wi = tg_api('getWebhookInfo', [], 4);
        $state = '🤖 @' . $h(setting('tg_bot')) . (setting('tg_chat') !== ''
                ? ' → «' . $h(setting('tg_chat_title')) . '» · ' . (setting('tg_forum') === '1' ? 'темы на каждого пользователя' : 'ответы реплаем')
                : ' · ⚠️ чат не задан');
        if (!empty($wi['ok'])) {
            $extra .= '<div class="meta">Вебхук: ' . ($wi['result']['url'] !== '' ? '✅' : '❌ не установлен')
                . ' · в очереди ' . (int)($wi['result']['pending_update_count'] ?? 0)
                . (!empty($wi['result']['last_error_message']) ? ' · последняя ошибка Telegram: ' . $h($wi['result']['last_error_message'])
                    . ' (' . gmdate('d.m H:i', (int)$wi['result']['last_error_date']) . ' UTC)' : '') . '</div>';
        }
    }
    if (($err = setting('tg_last_error')) !== '') {
        $extra .= '<div class="meta">Последняя ошибка: ' . $h($err) . '</div>';
    }
    $seen = json_decode(setting('tg_seen') ?: '{}', true) ?: [];
    if ($seen) {
        $extra .= '<div class="meta">Бот видел чаты (нажмите, чтобы подставить): ';
        foreach ($seen as $id => $title) {
            $extra .= '<a href="#" class="seen" data-chat="' . $h($id) . '">' . $h($title ?: $id) . ' (' . $h($id) . ')</a> ';
        }
        $extra .= '</div>';
    }
    return '<h2>✈️ Telegram — заявки в чат, ответы в приложение</h2><div class="card" id="tg"><div><b>' . $state . '</b></div>' . $extra
        . '<label class="meta">Токен бота (от @BotFather)</label><input class="note" id="tg_token" autocomplete="off" placeholder="'
        . ($token !== '' ? 'сохранён (…' . $h(substr($token, -4)) . ') — оставьте пустым, чтобы не менять' : '123456789:AA…') . '">'
        . '<label class="meta">ID чата (группа: -100…)</label><input class="note" id="tg_chat" value="' . $h(setting('tg_chat')) . '">'
        . '<label class="meta">Имя отправителя ответов в приложении</label><input class="note" id="tg_name" value="' . $h(support_name()) . '">'
        . '<div style="margin-top:8px"><button class="y" data-op="save">💾 Сохранить и проверить</button><button data-op="test">✉ Тестовое сообщение</button>'
        . '<button data-op="off">⏻ Отключить</button></div>'
        . '<details class="meta" style="margin-top:8px"><summary>Как настроить</summary><ol>'
        . '<li>В Telegram у @BotFather: /newbot → получить токен, вписать сюда, сохранить.</li>'
        . '<li>Создать группу, в настройках включить «Темы». Добавить бота админом с правом «Управление темами».</li>'
        . '<li>Написать в группе /id — бот ответит ID чата (он же появится выше). Вписать, сохранить ещё раз.</li>'
        . '<li>Каждая заявка приходит в тему своего пользователя; всё, что вы пишете в этой теме (текст, фото с подписью), уходит ему в личку приложения. 👍 на вашем сообщении — доставлено. '
        . 'Если пользователь отвечает в приложении — его сообщение придёт в ту же тему.</li>'
        . '<li>Без тем: отвечать реплаем на сообщение заявки или «#12 текст». Кнопки под заявкой меняют статус, /new — список открытых.</li></ol></details></div>';
}

/** Админка: заявки (идеи), жалобы, отчёты о сбоях. Вход — мастер-ключ (?key=…, дальше cookie). */
function action_admin(): void
{
    if (!key_ok()) {
        header('Content-Type: text/html; charset=utf-8');
        http_response_code(401);
        echo '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Админка</title>'
            . '<form style="font:17px system-ui;max-width:360px;margin:60px auto"><input type="hidden" name="action" value="admin">'
            . '<p><b>Умный плеер — админка</b></p><input name="key" type="password" placeholder="Ключ" style="width:100%;padding:10px;font-size:17px">'
            . '<button style="margin-top:10px;padding:10px 20px;font-size:17px">Войти</button></form>';
        return;
    }
    if (isset($_GET['key'])) {
        setcookie('vapi_key', request_key(), ['expires' => time() + 30 * 86400, 'path' => '/',
            'secure' => true, 'httponly' => true, 'samesite' => 'Strict']);
    }
    $db = social();
    $ideas = $db->query('SELECT * FROM ideas ORDER BY CASE status WHEN \'new\' THEN 0 WHEN \'work\' THEN 1 ELSE 2 END, id DESC LIMIT 500')
        ->fetchAll(PDO::FETCH_ASSOC);
    $cnt = $db->query('SELECT status, COUNT(*) FROM ideas GROUP BY status')->fetchAll(PDO::FETCH_KEY_PAIR);
    $reports = $db->query('SELECT kind, ref, COUNT(*) n, GROUP_CONCAT(reason, \' · \') reasons, MAX(ts) ts FROM reports
        GROUP BY kind, ref ORDER BY ts DESC LIMIT 100')->fetchAll(PDO::FETCH_ASSOC);
    $crashes = array_slice(array_reverse(glob(CRASHES . '/*.txt') ?: []), 0, 30);
    $st = ['users' => 'SELECT COUNT(DISTINCT uid) FROM (SELECT uid FROM chat UNION SELECT uid FROM comments UNION SELECT uid FROM likes UNION SELECT uid FROM dm UNION SELECT uid FROM ideas)',
        'chat' => 'SELECT COUNT(*) FROM chat', 'dm' => 'SELECT COUNT(*) FROM dm', 'spaces' => 'SELECT COUNT(*) FROM spaces'];
    foreach ($st as $k => $sql) {
        $st[$k] = (int)$db->query($sql)->fetchColumn();
    }
    $h = fn($s) => htmlspecialchars((string)$s, ENT_QUOTES);
    $answers = $db->prepare('SELECT dst, COUNT(*) FROM dm WHERE src = ? GROUP BY dst');
    $answers->execute([support_id()]);
    $answers = $answers->fetchAll(PDO::FETCH_KEY_PAIR);
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Robots-Tag: noindex');
    echo '<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        . '<title>Умный плеер — админка</title><style>
:root{--y:#FFE500;--k:#000;--bg:#FAFAF5;--g:#666}*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--k);font:15px/1.45 system-ui,sans-serif}
.w{max-width:980px;margin:0 auto;padding:20px 16px}h1{font-size:24px;margin:0 0 4px}h2{margin:28px 0 10px;font-size:19px}
.stats{color:var(--g)}.tabs a{display:inline-block;margin:10px 6px 0 0;padding:6px 12px;border:2px solid var(--k);color:var(--k);text-decoration:none;font-weight:700;background:#fff}
.tabs a.on{background:var(--y)}.card{background:#fff;border:2px solid var(--k);box-shadow:4px 4px 0 var(--k);padding:12px 14px;margin:0 0 14px}
.card.new{border-left:10px solid var(--y)}.card.done{opacity:.6}.meta{color:var(--g);font-size:13px}.txt{white-space:pre-wrap;margin:6px 0 10px;font-size:16px}
button{font:inherit;padding:5px 10px;border:2px solid var(--k);background:#fff;cursor:pointer;margin:2px 4px 2px 0}button.y{background:var(--y)}
#tg label{display:block;margin-top:8px}input.note{width:100%;padding:6px;font:inherit;border:1px solid #aaa;margin-top:6px}table{border-collapse:collapse;width:100%;background:#fff}
td,th{border:1px solid #ccc;padding:6px;text-align:left;vertical-align:top;font-size:13px}pre{white-space:pre-wrap;font-size:12px;max-height:200px;overflow:auto}
</style></head><body><div class="w"><h1>💡 Заявки и идеи</h1>'
        . '<div class="stats">Пользователей: ' . $st['users'] . ' · сообщений в чате: ' . $st['chat'] . ' · личных: ' . $st['dm']
        . ' · своих источников: ' . $st['spaces'] . '</div>' . admin_tg_html($h)
        . '<div class="tabs"><a href="#" data-f="all" class="on">Все (' . array_sum($cnt) . ')</a><a href="#" data-f="new">Новые (' . ($cnt['new'] ?? 0)
        . ')</a><a href="#" data-f="work">В работе (' . ($cnt['work'] ?? 0) . ')</a><a href="#" data-f="done">Готово (' . ($cnt['done'] ?? 0) . ')</a></div><h2></h2>';
    if (!$ideas) {
        echo '<p class="meta">Заявок пока нет.</p>';
    }
    $lbl = ['new' => '🆕 новая', 'work' => '🔧 в работе', 'done' => '✅ готово'];
    foreach ($ideas as $i) {
        echo '<div class="card ' . $h($i['status']) . '" data-id="' . (int)$i['id'] . '" data-s="' . $h($i['status']) . '">'
            . '<div class="meta">#' . (int)$i['id'] . ' · ' . gmdate('d.m.Y H:i', (int)$i['ts']) . ' UTC · ' . $h($lbl[$i['status']] ?? $i['status'])
            . ' · ' . $h($i['name'] ?: 'Guest') . ($i['contact'] !== '' ? ' · <b>' . $h($i['contact']) . '</b>' : '')
            . ' · ' . $h($i['lang']) . ' ' . $h($i['app']) . ' · автор ' . $h(author_id($i['uid']))
            . (!empty($answers[author_id($i['uid'])]) ? ' · 💬 ответов: ' . (int)$answers[author_id($i['uid'])] : '') . '</div>'
            . '<div class="txt">' . $h($i['text']) . '</div>'
            . '<button class="y" data-st="work">🔧 В работу</button><button data-st="done">✅ Готово</button><button data-st="new">🆕 Новая</button>'
            . '<button data-st="delete">🗑 Удалить</button><input class="note" placeholder="Заметка (сохраняется вместе со статусом)" value="' . $h($i['note']) . '"></div>';
    }
    echo '<h2>🚩 Жалобы</h2>';
    if ($reports) {
        echo '<table><tr><th>Что</th><th>Ссылка</th><th>Жалоб</th><th>Причины</th><th>Когда</th></tr>';
        foreach ($reports as $r) {
            echo '<tr><td>' . $h($r['kind']) . '</td><td>' . $h($r['ref']) . '</td><td>' . (int)$r['n'] . ($r['n'] >= HIDE_AFTER_REPORTS ? ' (скрыто)' : '')
                . '</td><td>' . $h($r['reasons']) . '</td><td>' . gmdate('d.m H:i', (int)$r['ts']) . '</td></tr>';
        }
        echo '</table>';
    } else {
        echo '<p class="meta">Жалоб нет.</p>';
    }
    echo '<h2>💥 Сбои приложения (последние ' . count($crashes) . ')</h2>';
    foreach ($crashes as $f) {
        echo '<details class="card"><summary>' . $h(basename($f)) . '</summary><pre>' . $h(mb_substr(file_get_contents($f), 0, 6000)) . '</pre></details>';
    }
    echo '</div><script>
document.querySelectorAll(".tabs a").forEach(a=>a.onclick=e=>{e.preventDefault();document.querySelectorAll(".tabs a").forEach(x=>x.classList.toggle("on",x===a));
 document.querySelectorAll(".card[data-id]").forEach(c=>c.style.display=a.dataset.f==="all"||c.dataset.s===a.dataset.f?"":"none");});
document.querySelectorAll(".card[data-id] button").forEach(b=>b.onclick=async()=>{const c=b.closest(".card"),st=b.dataset.st;
 if(st==="delete"&&!confirm("Удалить заявку #"+c.dataset.id+"?"))return;
 const r=await fetch("?action=admin_idea",{method:"POST",headers:{"Content-Type":"application/json"},credentials:"same-origin",
  body:JSON.stringify({id:c.dataset.id,status:st,note:c.querySelector(".note").value})});
 if((await r.json()).ok)location.reload();else alert("Не сохранилось");});
document.querySelectorAll("#tg button").forEach(b=>b.onclick=async()=>{const v=id=>document.getElementById(id).value;
 if(b.dataset.op==="off"&&!confirm("Отключить Telegram?"))return;b.disabled=true;
 try{const r=await fetch("?action=admin_tg",{method:"POST",headers:{"Content-Type":"application/json"},credentials:"same-origin",
  body:JSON.stringify({op:b.dataset.op,token:v("tg_token"),chat:v("tg_chat"),name:v("tg_name")})});const d=await r.json();
  alert(d.msg||d.error||"?");if(d.ok&&b.dataset.op!=="test")location.reload();}catch(e){alert("Ошибка сети")}b.disabled=false;});
document.querySelectorAll("#tg a.seen").forEach(a=>a.onclick=e=>{e.preventDefault();document.getElementById("tg_chat").value=a.dataset.chat;});
</script></body></html>';
}

// ---------- аватарки ----------

function author_id(string $uid): string
{
    return substr(sha1('author:' . $uid), 0, 16);
}

function avatar_id(string $uid): string
{
    return substr(sha1('ava:' . $uid), 0, 16);
}

/** Загрузить свою аватарку (multipart photo, до 1 МБ, JPEG/PNG/WebP — приложение шлёт JPEG 256×256). */
function action_avatar_set(): void
{
    $uid = uid(true);
    $up = $_FILES['photo'] ?? null;
    if (!$up || $up['error'] !== UPLOAD_ERR_OK || $up['size'] > 1024 * 1024) {
        fail(400, 'bad file');
    }
    $info = @getimagesize($up['tmp_name']);
    if (!$info || !in_array($info[2], [IMAGETYPE_JPEG, IMAGETYPE_PNG, IMAGETYPE_WEBP], true)) {
        fail(415, 'not an image');
    }
    if (!is_dir(AVATARS)) {
        mkdir(AVATARS, 0700, true);
    }
    move_uploaded_file($up['tmp_name'], AVATARS . '/' . avatar_id($uid) . '.jpg');
    json_out(['ok' => true, 'avatar' => avatar_id($uid)]);
}

function action_avatar(): void
{
    $id = (string)($_GET['id'] ?? '');
    $f = AVATARS . '/' . $id . '.jpg';
    if (!preg_match('/^[a-f0-9]{16}$/', $id) || !is_file($f)) {
        fail(404, 'not found');
    }
    $info = getimagesize($f);
    header('Content-Type: ' . ($info['mime'] ?? 'image/jpeg'));
    header('Cache-Control: public, max-age=3600');
    header('Content-Length: ' . filesize($f));
    readfile($f);
}

/** Отчёт о падении приложения (текст до 20 КБ; не больше 50 файлов в день — защита от спама). */
function action_crash(): void
{
    $text = (string)($_GET['text'] ?? '');
    if ($text === '' || strlen($text) > 20000) {
        fail(400, 'bad report');
    }
    if (!is_dir(CRASHES)) {
        mkdir(CRASHES, 0700, true);
    }
    foreach (glob(CRASHES . '/*.txt') as $old) { // храним 90 дней
        if (filemtime($old) < time() - 90 * 86400) {
            unlink($old);
        }
    }
    $today = date('Ymd');
    if (count(glob(CRASHES . '/' . $today . '_*.txt')) >= 50) {
        fail(429, 'too many');
    }
    file_put_contents(CRASHES . '/' . $today . '_' . date('His') . '_' . bin2hex(random_bytes(3)) . '.txt', $text);
    json_out(['ok' => true]);
}

/**
 * «Удалить мои данные»: всё, что связано с X-UID этого устройства — сообщения чата, комментарии, лайки,
 * жалобы, аватар и свои источники вместе с файлами. Ссылки на источники в чужих сообщениях очищаются.
 */
function action_forget(): void
{
    $uid = uid(true);
    $db = social();
    $q = $db->prepare('SELECT * FROM spaces WHERE owner = ?');
    $q->execute([$uid]);
    $spaces = $q->fetchAll(PDO::FETCH_ASSOC);
    foreach ($spaces as $sp) {
        $dir = UPLOADS . '/' . $sp['id'];
        foreach (is_dir($dir) ? scandir($dir) : [] as $n) {
            if ($n !== '.' && $n !== '..') {
                unlink($dir . '/' . $n);
            }
        }
        if (is_dir($dir)) {
            rmdir($dir);
        }
        $db->prepare("UPDATE chat SET share_name = '', share_url = '', share_key = '', share_file = '' WHERE share_key = ?")
            ->execute([$sp['view_key']]);
        $db->prepare("UPDATE dm SET share_name = '', share_url = '', share_key = '', share_file = '' WHERE share_key = ?")
            ->execute([$sp['view_key']]);
    }
    $n = ['spaces' => count($spaces)];
    foreach (['spaces' => 'owner', 'chat' => 'uid', 'comments' => 'uid', 'likes' => 'uid', 'reports' => 'uid'] as $t => $col) {
        $d = $db->prepare("DELETE FROM $t WHERE $col = ?");
        $d->execute([$uid]);
        if ($t !== 'spaces') {
            $n[$t] = $d->rowCount();
        }
    }
    $img = $db->prepare("SELECT image FROM dm WHERE dst = ? AND image <> ''");
    $img->execute([author_id($uid)]);
    foreach ($img->fetchAll(PDO::FETCH_COLUMN) as $f) {
        @unlink(TG_MEDIA . '/' . basename($f));
    }
    $db->prepare('DELETE FROM tg_msgs WHERE author = ?')->execute([author_id($uid)]);
    $db->prepare('DELETE FROM tg_topics WHERE author = ?')->execute([author_id($uid)]);
    $d = $db->prepare('DELETE FROM dm WHERE uid = ? OR dst = ?');
    $d->execute([$uid, author_id($uid)]);
    $n['dm'] = $d->rowCount();
    $db->prepare('DELETE FROM dm_blocks WHERE who = ?')->execute([author_id($uid)]);
    $db->prepare('DELETE FROM users WHERE uid = ?')->execute([$uid]);
    $db->prepare('DELETE FROM push_tokens WHERE uid = ?')->execute([$uid]);
    $d = $db->prepare('DELETE FROM ideas WHERE uid = ?');
    $d->execute([$uid]);
    $n['ideas'] = $d->rowCount();
    $ava = AVATARS . '/' . avatar_id($uid) . '.jpg';
    $n['avatar'] = is_file($ava) && unlink($ava) ? 1 : 0;
    json_out(['ok' => true, 'deleted' => $n]);
}

/** Удалить сообщение чата для всех: автор (X-UID) — своё, мастер-ключ — любое (модерация). */
function action_chat_delete(): void
{
    $id = (int)($_GET['ref'] ?? 0);
    if (key_ok()) {
        social()->prepare('DELETE FROM chat WHERE id = ?')->execute([$id]);
        json_out(['ok' => true]);
        return;
    }
    $q = social()->prepare('DELETE FROM chat WHERE id = ? AND uid = ?');
    $q->execute([$id, uid(true)]);
    if ($q->rowCount() === 0) {
        fail(403, 'not your message');
    }
    json_out(['ok' => true]);
}

/** 3.9 Админ из приложения: проверить мастер-ключ. */
function action_admin_check(): void
{
    if (!key_ok()) {
        fail(401, 'Unauthorized');
    }
    json_out(['ok' => true, 'chat_closed' => setting('chat_closed') === '1']);
}

/** 3.9 Админ: закрыть ({"on":"1"}) или открыть ({"on":"0"}) общий чат. Закрытый — читать можно, писать может только админ. */
function action_admin_chat_close(): void
{
    if (!key_ok()) {
        fail(401, 'Unauthorized');
    }
    $closed = ($_GET['on'] ?? '0') === '1';
    set_setting('chat_closed', $closed ? '1' : '0');
    json_out(['ok' => true, 'chat_closed' => $closed]);
}

/** Админ (мастер-ключ): снять все блокировки в личке у всех пользователей. Ответ — сколько снято. */
function action_admin_unblock_all(): void
{
    if (!key_ok()) {
        fail(401, 'Unauthorized');
    }
    $n = social()->exec('DELETE FROM dm_blocks');
    json_out(['ok' => true, 'unblocked' => (int)$n]);
}

/** Удалить свой источник целиком: файлы, ключ доступа и ссылки на него в чате. */
function action_space_delete(): void
{
    $sp = key_ok() ? space_row((string)($_GET['folder'] ?? '')) : own_space();
    if ($sp === null) {
        fail(404, 'not found');
    }
    $dir = UPLOADS . '/' . $sp['id'];
    foreach (is_dir($dir) ? scandir($dir) : [] as $n) {
        if ($n !== '.' && $n !== '..') {
            unlink($dir . '/' . $n);
        }
    }
    if (is_dir($dir)) {
        rmdir($dir);
    }
    $db = social();
    $db->prepare('DELETE FROM spaces WHERE id = ?')->execute([$sp['id']]);
    $db->prepare("UPDATE chat SET share_name = '', share_url = '', share_key = '', share_file = '' WHERE share_key = ?")
        ->execute([$sp['view_key']]);
    $db->prepare("UPDATE dm SET share_name = '', share_url = '', share_key = '', share_file = '' WHERE share_key = ?")
        ->execute([$sp['view_key']]);
    json_out(['ok' => true]);
}

/** Жалоба: kind=file (ref «папка/файл») или kind=chat (ref — id сообщения). От 3 разных людей — скрывается. */
function action_report(): void
{
    $uid = uid(true);
    $kind = (string)($_GET['kind'] ?? '');
    $ref = mb_substr((string)($_GET['ref'] ?? ''), 0, 200);
    if (!in_array($kind, ['file', 'chat', 'comment', 'dm'], true) || $ref === '') {
        fail(400, 'bad report');
    }
    social()->prepare('INSERT OR IGNORE INTO reports VALUES (?, ?, ?, ?, ?)')
        ->execute([$kind, $ref, $uid, mb_substr((string)($_GET['reason'] ?? ''), 0, 300), time()]);
    json_out(['ok' => true]);
}

function action_folders(): void
{
    $out = [];
    $all = key_ok();
    $key = request_key();
    if ($key !== '' && !$all) {   // ключ источника пользователя — только его папка
        $q = social()->prepare('SELECT id FROM spaces WHERE view_key = ?');
        $q->execute([$key]);
        foreach ($q as $r) {
            $out[] = 'u_' . $r['id'];
        }
        if ($out) {
            json_out(['ok' => true, 'folders' => $out]);
            return;
        }
    }
    foreach (FOLDERS as $name => $path) {
        if (($all || in_array($name, PUBLIC_FOLDERS, true)) && is_dir(MEDIA_DIR . '/' . $path)) {
            $out[] = $name;
        }
    }
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['ok' => true, 'folders' => $out], JSON_UNESCAPED_UNICODE);
}

function action_app(): void
{
    folder_path();
    $folder = $_GET['folder'];
    $tabs = '';
    foreach (array_keys(FOLDERS) as $f) {
        $tabs .= '<a class="tab' . ($f === $folder ? ' on' : '') . '" href="?action=app&folder=' . rawurlencode($f) . '">'
            . htmlspecialchars($f) . '</a>';
    }
    $folderJs = json_encode($folder);
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-store');
    echo <<<HTML
<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Медиа · {$folder}</title>
<style>
:root{--y:#FFE500;--k:#000;--bg:#FAFAF5}
*{box-sizing:border-box}body{margin:0;background:var(--bg);font:15px/1.3 system-ui,sans-serif;color:var(--k)}
header{display:flex;gap:8px;align-items:center;padding:12px;flex-wrap:wrap}
.tab,.btn{border:2px solid var(--k);box-shadow:3px 3px 0 var(--k);background:#fff;padding:8px 14px;font-weight:700;color:var(--k);text-decoration:none;cursor:pointer}
.tab.on,.btn{background:var(--y)}#info{margin-left:auto;font-weight:600}
#grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;padding:0 12px 24px}
.it{position:relative;aspect-ratio:1;border:2px solid var(--k);background:#ddd;overflow:hidden;cursor:pointer}
.it img,.it video{width:100%;height:100%;object-fit:cover;display:block;pointer-events:none}
.it .v{position:absolute;left:6px;bottom:6px;background:var(--y);border:2px solid var(--k);font-weight:700;padding:0 6px}
#ss-overlay{position:fixed;inset:0;background:#000;display:none;z-index:9}#ss-overlay.open{display:block}
#ss-wrap{position:absolute;inset:0;display:flex;align-items:center;justify-content:center}
#ss-wrap img,#ss-wrap video{max-width:100%;max-height:100%}
.nav{position:absolute;top:50%;transform:translateY(-50%);background:rgba(255,255,255,.25);color:#fff;border:0;font-size:28px;width:48px;height:64px;cursor:pointer}
#prev{left:6px}#next{right:6px}#close{position:absolute;right:10px;top:10px;z-index:2}
#cnt{position:absolute;left:12px;top:14px;color:#fff;font-weight:700;z-index:2}
</style></head><body>
<header>{$tabs}<button class="btn" onclick="ssOpen(0)">▶ Слайдшоу</button><span id="info">Загрузка…</span></header>
<div id="grid"></div>
<div id="ss-overlay"><div id="ss-wrap"></div><span id="cnt"></span>
<button class="nav" id="prev" onclick="ssGo(-1)">‹</button><button class="nav" id="next" onclick="ssGo(1)">›</button>
<button class="btn" id="close" onclick="ssClose()">✕</button></div>
<script>
const FOLDER={$folderJs};let items=[],idx=0,timer=null;
fetch('?action=list&folder='+encodeURIComponent(FOLDER)).then(r=>r.json()).then(d=>{
  if(!d.ok){document.getElementById('info').textContent='Нет доступа: '+(d.error||'');return;}
  items=d.items;document.getElementById('info').textContent=items.length+' файлов';
  const g=document.getElementById('grid');
  items.forEach((it,i)=>{const el=document.createElement('div');el.className='it';el.onclick=()=>ssOpen(i);
    el.innerHTML=it.type==='video'?'<video preload="metadata" muted src="'+it.url+'#t=0.5"></video><span class="v">▶</span>':'<img loading="lazy" src="'+it.url+'">';
    g.appendChild(el);});
}).catch(()=>document.getElementById('info').textContent='Ошибка сети');
function ssOpen(i){if(!items.length)return;idx=i;document.getElementById('ss-overlay').classList.add('open');ssShow();}
function ssClose(){clearTimeout(timer);document.getElementById('ss-overlay').classList.remove('open');document.getElementById('ss-wrap').innerHTML='';}
function ssGo(d){idx=(idx+d+items.length)%items.length;ssShow();}
function ssShow(){clearTimeout(timer);const it=items[idx],w=document.getElementById('ss-wrap');
  document.getElementById('cnt').textContent=(idx+1)+' / '+items.length;
  if(it.type==='video'){w.innerHTML='<video autoplay playsinline controls src="'+it.url+'"></video>';w.firstChild.onended=()=>ssGo(1);}
  else{w.innerHTML='<img src="'+it.url+'">';timer=setTimeout(()=>ssGo(1),5000);}}
document.addEventListener('keydown',e=>{if(e.key==='ArrowRight')ssGo(1);if(e.key==='ArrowLeft')ssGo(-1);if(e.key==='Escape')ssClose();});
</script></body></html>
HTML;
}

// ---------- 3.4: «общие» видео с телефона (в чат/личку) — загрузка кусками ----------

/** Удалить «общие» видео старше SHARE_KEEP_DAYS и брошенные недогрузки (> 2 суток). Дёшево: только папки источников. */
function share_cleanup(): void
{
    $old = time() - SHARE_KEEP_DAYS * 86400;
    foreach (glob(UPLOADS . '/*', GLOB_ONLYDIR) ?: [] as $dir) {
        foreach (glob($dir . '/share_*') ?: [] as $f) {
            if (filemtime($f) < $old) {
                @unlink($f);
            }
        }
        foreach (glob($dir . '/.share_*.part') ?: [] as $f) {
            if (filemtime($f) < time() - 2 * 86400) {
                @unlink($f);
            }
        }
    }
}

/** POST action=share_chunk&folder&id&offset&total&name, тело — байты куска. Ответ {ok, have[, fname]}. Повтор куска — не ошибка. */
function action_share_chunk(): void
{
    own_space();
    $dir = folder_path();
    $id = (string)($_GET['id'] ?? '');
    $offset = (int)($_GET['offset'] ?? -1);
    $total = (int)($_GET['total'] ?? 0);
    if (!preg_match('/^[a-f0-9]{8,32}$/', $id) || $offset < 0 || $total <= 0) {
        fail(400, 'bad chunk');
    }
    if ($total > SHARE_MAX_BYTES) {
        fail(413, 'file too large');
    }
    $part = $dir . '/.share_' . $id . '.part';
    $have = is_file($part) ? filesize($part) : 0;
    if ($offset === 0 && $have === 0) {   // новая загрузка: уберём старьё и проверим общий объём источника
        share_cleanup();
        $used = 0;
        foreach (glob($dir . '/share_*') ?: [] as $f) {
            $used += filesize($f);
        }
        if ($used + $total > SHARE_MAX_TOTAL) {
            fail(507, 'storage full: delete old shared videos');
        }
    }
    $data = file_get_contents('php://input');
    $len = strlen($data);
    if ($len === 0) {
        fail(400, 'empty chunk');
    }
    if ($offset < $have && $offset + $len <= $have) {   // кусок уже был (ответ потерялся) — подтверждаем
        json_out(['ok' => true, 'have' => $have]);
        return;
    }
    if ($offset !== $have) {
        fail(409, 'offset mismatch: have ' . $have);
    }
    if ($have + $len > $total || file_put_contents($part, $data, FILE_APPEND | LOCK_EX) !== $len) {
        @unlink($part);
        fail(400, 'chunk overflow');
    }
    $have += $len;
    if ($have < $total) {
        json_out(['ok' => true, 'have' => $have]);
        return;
    }
    if (mp4_duration($part) === null) {
        @unlink($part);
        fail(415, 'not a video (mp4/mov)');
    }
    $raw = (string)($_GET['name'] ?? 'video.mp4');
    $ext = strtolower(pathinfo($raw, PATHINFO_EXTENSION));
    $ext = in_array($ext, ['mp4', 'm4v', 'mov'], true) ? $ext : 'mp4';
    $base = substr(preg_replace('/[^A-Za-z0-9._-]+/', '_', pathinfo($raw, PATHINFO_FILENAME)), 0, 40);
    $name = 'share_' . date('Ymd_His') . '_' . ($base !== '' ? $base : 'video') . '.' . $ext;
    if (!rename($part, $dir . '/' . $name)) {
        fail(500, 'save failed');
    }
    json_out(['ok' => true, 'have' => $have, 'fname' => $name, 'keep_days' => SHARE_KEEP_DAYS]);
}

// ---------- 3.4: пуши Firebase Cloud Messaging ----------

/** Зарегистрировать токен устройства (POST {token, app}). */
function action_push_register(): void
{
    $uid = uid(true);
    $token = (string)($_GET['token'] ?? '');
    if (!preg_match('/^[A-Za-z0-9:_\-.]{20,4096}$/', $token)) {
        fail(400, 'bad token');
    }
    $app = mb_substr((string)($_GET['app'] ?? ''), 0, 60);
    social()->prepare('INSERT OR REPLACE INTO push_tokens (token, uid, author, app, ts) VALUES (?, ?, ?, ?, ?)')
        ->execute([$token, $uid, author_id($uid), $app, time()]);
    json_out(['ok' => true, 'push' => is_file(FCM_SA)]);
}

/** Отправить после ответа клиенту — пользователь не ждёт Google. */
function push_later(callable $f): void
{
    static $queue = null;
    if ($queue === null) {
        $queue = new ArrayObject();
        register_shutdown_function(function () use ($queue) {
            if (function_exists('fastcgi_finish_request')) {
                fastcgi_finish_request();
            }
            foreach ($queue as $job) {
                try {
                    $job();
                } catch (Throwable $e) {
                }
            }
        });
    }
    $queue[] = $f;
}

function b64url(string $s): string
{
    return rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
}

/** OAuth-токен Google по ключу сервисного аккаунта (JWT RS256), кэш в settings на ~55 мин. */
function fcm_access(): ?array
{
    if (!is_file(FCM_SA)) {
        return null;
    }
    $sa = json_decode((string)file_get_contents(FCM_SA), true);
    if (!is_array($sa) || empty($sa['private_key']) || empty($sa['client_email']) || empty($sa['project_id'])) {
        return null;
    }
    if (setting('fcm_exp') !== '' && (int)setting('fcm_exp') > time() + 60) {
        return [setting('fcm_at'), $sa['project_id']];
    }
    $now = time();
    $head = b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $claims = b64url(json_encode(['iss' => $sa['client_email'], 'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => $sa['token_uri'] ?? 'https://oauth2.googleapis.com/token', 'iat' => $now, 'exp' => $now + 3600]));
    if (!openssl_sign("$head.$claims", $sig, $sa['private_key'], 'sha256WithRSAEncryption')) {
        return null;
    }
    $ch = curl_init($sa['token_uri'] ?? 'https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 8, CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => http_build_query(['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion' => "$head.$claims." . b64url($sig)])]);
    $r = json_decode((string)curl_exec($ch), true);
    curl_close($ch);
    if (empty($r['access_token'])) {
        return null;
    }
    set_setting('fcm_at', $r['access_token']);
    set_setting('fcm_exp', (string)($now + (int)($r['expires_in'] ?? 3600) - 300));
    return [$r['access_token'], $sa['project_id']];
}

/** Одно сообщение FCM: target — ['token' => …] или ['topic' => …]. Возвращает HTTP-код. */
function fcm_send(array $target, array $data, bool $high): int
{
    $acc = fcm_access();
    if ($acc === null) {
        return 0;
    }
    $msg = ['message' => $target + ['data' => array_map('strval', $data),
            'android' => ['priority' => $high ? 'HIGH' : 'NORMAL', 'ttl' => $high ? '60s' : '86400s']]];
    $ch = curl_init('https://fcm.googleapis.com/v1/projects/' . $acc[1] . '/messages:send');
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 8, CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($msg, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'Authorization: Bearer ' . $acc[0]]]);
    curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($code === 401) {   // токен Google протух раньше срока — в следующий раз возьмём новый
        set_setting('fcm_exp', '0');
    }
    return $code;
}

/** Пуш всем устройствам автора (личка, звонок, комментарий). Мёртвые токены (404) удаляем. */
function push_author(string $author, array $data, bool $high = false): void
{
    if (function_exists('rt_publish')) {   // Production IT: то же событие — сразу в открытое приложение (Centrifugo)
        rt_publish($author, $data);
    }
    $q = social()->prepare('SELECT token FROM push_tokens WHERE author = ?');
    $q->execute([$author]);
    foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $t) {
        $code = fcm_send(['token' => $t], $data, $high);
        if ($code === 404 || $code === 400) {
            social()->prepare('DELETE FROM push_tokens WHERE token = ?')->execute([$t]);
        }
    }
}

/** Пуш по теме (общий чат — тема «chat», на неё подписываются приложения). */
function push_topic(string $topic, array $data): void
{
    fcm_send(['topic' => $topic], $data, false);
}


// Production IT: надстройка (вход по email, настройки LiveKit, админка, Centrifugo) — только там, где лежит prodit_ext.php
if (is_file(__DIR__ . '/prodit_ext.php')) {
    require __DIR__ . '/prodit_ext.php';
}

$isPost = in_array($_GET['action'] ?? '', ['like', 'comment', 'chat_post', 'report', 'space_create', 'upload', 'delete', 'chat_delete', 'space_delete', 'avatar_set', 'crash', 'forget',
    'dm_post', 'dm_delete', 'dm_block', 'idea', 'admin_idea', 'admin_tg', 'tg_hook', 'hello', 'share_chunk', 'push_register', 'admin_unblock_all', 'admin_chat_close'], true);
if (!in_array($_SERVER['REQUEST_METHOD'], $isPost ? ['POST'] : ['GET', 'HEAD'], true)) {
    fail(405, 'method not allowed');
}
if ($isPost) {   // тело JSON → в $_GET, чтобы папка проверялась тем же кодом
    $act = $_GET['action'] ?? '';
    $body = $act === 'upload' ? $_POST : ($act === 'share_chunk' ? [] : (json_decode(file_get_contents('php://input') ?: '{}', true) ?: []));
    foreach (['folder', 'file', 'text', 'name', 'kind', 'ref', 'reason', 'share_name', 'share_url', 'share_key', 'share_file',
                 'to', 'to_name', 'contact', 'lang', 'app', 'id', 'status', 'note', 'on', 'op', 'token', 'chat', 'hidden'] as $f) {
        if (isset($body[$f]) && is_string($body[$f])) {
            $_GET[$f] = $body[$f];
        }
    }
}
check_key();
match ($_GET['action'] ?? 'list') {
    'folders' => action_folders(),
    'stats'    => action_stats(),
    'like'     => action_like(),
    'comments' => action_comments(),
    'comment'  => action_comment(),
    'space_create' => action_space_create(),
    'upload'   => action_upload(),
    'delete'   => action_delete(),
    'chat'     => action_chat(),
    'chat_post' => action_chat_post(),
    'report'   => action_report(),
    'chat_delete' => action_chat_delete(),
    'avatar_set' => action_avatar_set(),
    'avatar'   => action_avatar(),
    'crash'    => action_crash(),
    'forget'   => action_forget(),
    'space_delete' => action_space_delete(),
    'dm_threads' => action_dm_threads(),
    'dm'       => action_dm(),
    'dm_post'  => action_dm_post(),
    'dm_delete' => action_dm_delete(),
    'dm_block' => action_dm_block(),
    'idea'     => action_idea(),
    'admin'    => action_admin(),
    'admin_idea' => action_admin_idea(),
    'admin_tg' => action_admin_tg(),
    'tg_hook'  => action_tg_hook(),
    'dm_image' => action_dm_image(),
    'hello'    => action_hello(),
    'share_chunk' => action_share_chunk(),
    'push_register' => action_push_register(),
    'admin_unblock_all' => action_admin_unblock_all(),
    'admin_check' => action_admin_check(),
    'admin_chat_close' => action_admin_chat_close(),
    'users'    => action_users(),
    'app'    => action_app(),
    'list'   => action_list(),
    'stream' => action_stream(),
    default  => fail(400, 'unknown action'),
};
