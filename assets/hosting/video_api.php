<?php
/**
 * Smart Player — источник видео на любом PHP-хостинге (PHP 7.4+, без базы данных).
 * Положите этот файл в корень сайта (или в любую папку): все видео и фото из этой папки
 * и всех вложенных папок появятся в приложении «Умный плеер», каждая папка — отдельным разделом.
 * В приложении: ☰ → «＋ Подключить источник» → адрес сайта (ключ подставится сам).
 *
 * Действия: folders, list&folder=…, stream&folder=…&file=… (с перемоткой, Range).
 * Доступ — по ключу (заголовок X-API-Key или ?key=…); пустой ключ — открыто для всех.
 */
const API_KEY   = '__KEY__';     // ключ этого источника; '' — без пароля
const MAX_DEPTH = 6;             // сколько уровней вложенных папок обходить
const SKIP_DIRS = ['cgi-bin', 'wp-admin', 'wp-includes', 'node_modules', 'vendor', 'tmp', 'cache', 'logs', 'posters'];
const MIME = ['mp4' => 'video/mp4', 'm4v' => 'video/mp4', 'mov' => 'video/quicktime', 'webm' => 'video/webm',
    'mkv' => 'video/x-matroska', '3gp' => 'video/3gpp', 'jpg' => 'image/jpeg', 'jpeg' => 'image/jpeg',
    'png' => 'image/png', 'webp' => 'image/webp', 'gif' => 'image/gif'];

$ROOT = realpath(__DIR__);

function out(array $d, int $code = 200): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($d, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function key_given(): string
{
    $k = $_SERVER['HTTP_X_API_KEY'] ?? ($_GET['key'] ?? ($_COOKIE['vapi_key'] ?? ''));
    return is_string($k) ? $k : '';
}

function mime_of(string $name): ?string
{
    return MIME[strtolower(pathinfo($name, PATHINFO_EXTENSION))] ?? null;
}

/** Папка из запроса: «.» — корень, иначе путь вида «a/b» строго внутри корня. */
function folder_dir(string $root, string $folder): string
{
    if ($folder === '' || $folder === '.') {
        return $root;
    }
    if (str_contains($folder, '..') || $folder[0] === '/' || preg_match('~(^|/)\.~', $folder)) {
        out(['ok' => false, 'error' => 'bad folder'], 400);
    }
    $dir = realpath($root . '/' . $folder);
    if ($dir === false || !is_dir($dir) || strpos($dir . '/', $root . '/') !== 0) {
        out(['ok' => false, 'error' => 'folder not found'], 404);
    }
    return $dir;
}

/** Есть ли в папке медиа (без захода во вложенные). */
function has_media(string $dir): bool
{
    foreach (scandir($dir) ?: [] as $n) {
        if ($n[0] !== '.' && is_file("$dir/$n") && mime_of($n) !== null) {
            return true;
        }
    }
    return false;
}

/** Все папки с медиа, рекурсивно: «.» (корень) и относительные пути. */
function folders(string $root, string $rel = '', int $depth = 0, array &$acc = []): array
{
    $dir = $rel === '' ? $root : "$root/$rel";
    if (has_media($dir)) {
        $acc[] = $rel === '' ? '.' : $rel;
    }
    if ($depth >= MAX_DEPTH) {
        return $acc;
    }
    $subs = [];
    foreach (scandir($dir) ?: [] as $n) {
        if ($n[0] === '.' || in_array(strtolower($n), SKIP_DIRS, true) || !is_dir("$dir/$n") || is_link("$dir/$n")) {
            continue;
        }
        $subs[] = $n;
    }
    natcasesort($subs);
    foreach ($subs as $n) {
        folders($root, $rel === '' ? $n : "$rel/$n", $depth + 1, $acc);
    }
    return $acc;
}

if (!function_exists('str_contains')) {
    function str_contains(string $h, string $n): bool { return $n === '' || strpos($h, $n) !== false; }
}
if (!function_exists('str_starts_with')) {
    function str_starts_with(string $h, string $n): bool { return strncmp($h, $n, strlen($n)) === 0; }
}

if (API_KEY !== '' && !hash_equals(API_KEY, key_given())) {
    out(['ok' => false, 'error' => 'Unauthorized'], 401);
}
if (isset($_GET['key']) && ($_COOKIE['vapi_key'] ?? '') !== $_GET['key']) {   // открыли в браузере с ?key= — запомним
    setcookie('vapi_key', (string)$_GET['key'], ['expires' => time() + 30 * 86400, 'path' => '/', 'httponly' => true, 'samesite' => 'Lax']);
}

$action = $_GET['action'] ?? 'folders';
$folder = (string)($_GET['folder'] ?? '.');

if ($action === 'folders') {
    out(['ok' => true, 'folders' => folders($ROOT), 'app' => 'smart-player-hosting/1']);
}

if ($action === 'list') {
    $dir = folder_dir($ROOT, $folder);
    $items = [];
    foreach (scandir($dir) ?: [] as $name) {
        $path = "$dir/$name";
        $mime = mime_of($name);
        if ($name[0] === '.' || $mime === null || !is_file($path)) {
            continue;
        }
        $q = '?action=stream&folder=' . rawurlencode($folder) . '&file=';
        $poster = null;   // превью: posters/<имя>.jpg рядом с роликом, если есть
        foreach (['jpg', 'png', 'webp'] as $e) {
            $pp = 'posters/' . pathinfo($name, PATHINFO_FILENAME) . '.' . $e;
            if (is_file("$dir/$pp")) {
                $poster = '?action=stream&folder=' . rawurlencode($folder === '.' ? 'posters' : "$folder/posters") . '&file=' . rawurlencode(basename($pp));
                break;
            }
        }
        $isVideo = str_starts_with($mime, 'video/');
        $items[] = ['fname' => $name, 'url' => $q . rawurlencode($name), 'type' => $isVideo ? 'video' : 'image',
            'size' => filesize($path), 'mtime' => filemtime($path), 'poster' => $poster ?? ($isVideo ? null : $q . rawurlencode($name))];
    }
    usort($items, fn($a, $b) => $b['mtime'] <=> $a['mtime']);   // свежие первыми
    out(['ok' => true, 'items' => $items]);
}

if ($action === 'stream') {
    $dir = folder_dir($ROOT, $folder);
    $name = (string)($_GET['file'] ?? '');
    if ($name === '' || $name !== basename($name) || $name[0] === '.') {
        out(['ok' => false, 'error' => 'bad file name'], 400);
    }
    $path = realpath("$dir/$name");
    $mime = mime_of($name);
    if ($path === false || dirname($path) !== $dir || !is_file($path) || $mime === null) {
        out(['ok' => false, 'error' => 'not found'], 404);
    }
    $size = filesize($path);
    $start = 0;
    $end = $size - 1;
    header('Content-Type: ' . $mime);
    header('Accept-Ranges: bytes');
    header('Cache-Control: private, max-age=86400');
    if (isset($_SERVER['HTTP_RANGE'])) {
        if (!preg_match('/^bytes=(\d*)-(\d*)$/', trim($_SERVER['HTTP_RANGE']), $m) || ($m[1] === '' && $m[2] === '')) {
            header("Content-Range: bytes */$size");
            out(['ok' => false, 'error' => 'bad range'], 416);
        }
        if ($m[1] === '') {
            $start = max(0, $size - (int)$m[2]);
        } else {
            $start = (int)$m[1];
            if ($m[2] !== '') {
                $end = min((int)$m[2], $size - 1);
            }
        }
        if ($start > $end || $start >= $size) {
            header("Content-Range: bytes */$size");
            out(['ok' => false, 'error' => 'range not satisfiable'], 416);
        }
        http_response_code(206);
        header("Content-Range: bytes $start-$end/$size");
    }
    header('Content-Length: ' . ($end - $start + 1));
    if ($_SERVER['REQUEST_METHOD'] === 'HEAD') {
        exit;
    }
    @set_time_limit(0);
    while (ob_get_level()) {
        ob_end_clean();
    }
    $fp = fopen($path, 'rb');
    fseek($fp, $start);
    $left = $end - $start + 1;
    while ($left > 0 && !feof($fp) && !connection_aborted()) {
        $chunk = fread($fp, (int)min(1 << 16, $left));
        echo $chunk;
        flush();
        $left -= strlen($chunk);
    }
    fclose($fp);
    exit;
}

out(['ok' => false, 'error' => 'unknown action'], 400);
