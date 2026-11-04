// Production IT для Windows: окно Electron с веб-клиентом https://spacecode.business/production-it/app/.
// Свой адрес (локальный стенд, тест): «Production IT.exe» --url=http://127.0.0.1:18150/production-it/app/  или переменная PRODIT_URL.
// Камера/микрофон/экран — только для своих сайтов (spacecode.business, efir.umno.cloud и адрес из --url); остальные ссылки — в браузере.
const { app, BrowserWindow, shell, session, desktopCapturer, Menu, dialog } = require('electron');
const path = require('path');
const fs = require('fs');

const DEFAULT_URL = 'https://spacecode.business/production-it/app/';
const arg = (name) => { const a = process.argv.find((x) => x.startsWith(`--${name}=`)); return a ? a.slice(name.length + 3) : ''; };
const START_URL = arg('url') || process.env.PRODIT_URL || DEFAULT_URL;
const SMOKE = arg('smoke') || process.env.PRODIT_SMOKE || '';   // проверка сборки: открыть скрыто, снять кадр в файл и выйти

let appOrigin;
try { appOrigin = new URL(START_URL).origin; } catch { appOrigin = new URL(DEFAULT_URL).origin; }
const TRUSTED = new Set(['https://spacecode.business', 'https://efir.umno.cloud', appOrigin]);
const MEDIA_PERMS = new Set(['media', 'display-capture', 'notifications', 'fullscreen', 'clipboard-sanitized-write', 'clipboard-read', 'speaker-selection']);
const originOf = (u) => { try { return new URL(u).origin; } catch { return ''; } };
const trusted = (u) => TRUSTED.has(originOf(u));
const openExternal = (u) => { if (/^(https?:|mailto:)/i.test(u)) shell.openExternal(u); };

// один экземпляр: второй запуск показывает уже открытое окно
if (!SMOKE && !app.requestSingleInstanceLock()) app.quit();

app.setAppUserModelId('com.prodit.desktop');   // уведомления Windows — от имени «Production IT»
let win = null;

// размер и положение окна — между запусками
const stateFile = () => path.join(app.getPath('userData'), 'window.json');
function loadState() { try { return JSON.parse(fs.readFileSync(stateFile(), 'utf8')); } catch { return { width: 1280, height: 820 }; } }
function saveState() {
  if (!win || win.isDestroyed()) return;
  try { fs.writeFileSync(stateFile(), JSON.stringify({ ...win.getNormalBounds(), maximized: win.isMaximized() })); } catch {}
}

function setupSession(ses) {
  ses.setPermissionRequestHandler((wc, permission, cb, details) => {
    const from = details.requestingUrl || (wc && wc.getURL()) || '';
    cb(MEDIA_PERMS.has(permission) && trusted(from));
  });
  ses.setPermissionCheckHandler((wc, permission, requestingOrigin, details) => {
    const from = (details && (details.requestingUrl || details.securityOrigin)) || requestingOrigin || '';
    return MEDIA_PERMS.has(permission) && trusted(from);
  });
  // демонстрация экрана в эфире: весь основной экран (системного выбора окна в Electron на Windows нет)
  ses.setDisplayMediaRequestHandler((request, cb) => {
    if (!trusted(request.securityOrigin || (request.frame && request.frame.url) || '')) return cb({});
    desktopCapturer.getSources({ types: ['screen'] }).then((s) => cb(s[0] ? { video: s[0], audio: process.platform === 'win32' ? 'loopback' : undefined } : {}))
      .catch(() => cb({}));
  });
}

function offlinePage(err) {
  const html = `<!doctype html><meta charset=utf-8><title>Production IT</title><body style="margin:0;height:100vh;display:grid;place-items:center;
    background:#12111C;color:#C7C5D9;font:15px system-ui,sans-serif;text-align:center"><div><div style="font:700 22px system-ui;color:#F1F0F7;margin-bottom:10px">Production IT</div>
    <p>Нет связи с сервером · No connection</p><p style="color:#9C99B5;font-size:13px">${String(err).replace(/[<>&]/g, '')}</p>
    <button onclick="location.href=${JSON.stringify(START_URL)}" style="margin-top:12px;padding:11px 22px;border-radius:40px;border:0;background:#5E27D4;color:#fff;font:600 15px system-ui;cursor:pointer">
    Повторить · Retry</button></div>`;
  win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
}

function createWindow() {
  const st = loadState();
  win = new BrowserWindow({
    width: st.width || 1280, height: st.height || 820, x: st.x, y: st.y, minWidth: 380, minHeight: 560,
    backgroundColor: '#12111C', title: 'Production IT', autoHideMenuBar: true, show: false,
    icon: path.join(__dirname, 'build', process.platform === 'win32' ? 'icon.ico' : 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: true },
  });
  if (st.maximized) win.maximize();
  win.once('ready-to-show', () => { if (!SMOKE) win.show(); });
  win.on('close', saveState);

  const wc = win.webContents;
  // новые окна (target=_blank, «В новой вкладке», ссылки из чата) — в обычном браузере
  wc.setWindowOpenHandler(({ url }) => { openExternal(url); return { action: 'deny' }; });
  // уход главного окна на чужой сайт — тоже в браузер
  wc.on('will-navigate', (e, url) => { if (!trusted(url) && !url.startsWith('data:')) { e.preventDefault(); openExternal(url); } });
  wc.on('did-fail-load', (e, code, desc, url, isMain) => { if (isMain && code !== -3 && !SMOKE) offlinePage(`${desc} (${code})`); });
  wc.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    if (input.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (input.key === 'F5' || (input.control && input.key.toLowerCase() === 'r')) { wc.reload(); e.preventDefault(); }
    if (input.control && input.shift && input.key.toLowerCase() === 'i') { wc.toggleDevTools(); e.preventDefault(); }
  });
  wc.on('context-menu', (e, p) => {   // копировать/вставить/ссылка — правой кнопкой
    const items = [];
    if (p.linkURL) items.push({ label: 'Открыть ссылку в браузере', click: () => openExternal(p.linkURL) }, { label: 'Копировать ссылку', click: () => require('electron').clipboard.writeText(p.linkURL) }, { type: 'separator' });
    if (p.isEditable) items.push({ role: 'cut', label: 'Вырезать' }, { role: 'copy', label: 'Копировать' }, { role: 'paste', label: 'Вставить' }, { role: 'selectAll', label: 'Выделить всё' });
    else if (p.selectionText) items.push({ role: 'copy', label: 'Копировать' });
    if (p.misspelledWord) p.dictionarySuggestions.slice(0, 5).forEach((s) => items.unshift({ label: s, click: () => wc.replaceMisspelling(s) }));
    if (items.length) Menu.buildFromTemplate(items).popup({ window: win });
  });

  win.loadURL(START_URL);
  if (SMOKE) smoke(wc);
}

// проверка сборки без показа окна: загрузить адрес, дождаться экрана входа/приложения, сохранить кадр и выйти (0 — успех)
function smoke(wc) {
  const out = SMOKE === '1' ? path.join(app.getPath('temp'), 'prodit-smoke.png') : SMOKE;
  const fail = (m) => { console.log('SMOKE FAIL ' + m); app.exit(1); };
  setTimeout(() => fail('timeout'), 30000);
  wc.on('did-fail-load', (e, code, desc, url, isMain) => { if (isMain) fail(`${desc} ${url}`); });
  wc.on('did-finish-load', async () => {
    for (let i = 0; i < 40; i++) {
      const ok = await wc.executeJavaScript(`!!document.querySelector('#login:not([hidden]), #app:not([hidden])') && document.fonts.status === 'loaded'`).catch(() => false);
      if (ok) break;
      await new Promise((r) => setTimeout(r, 250));
    }
    const info = await wc.executeJavaScript(`({title: document.title, login: !document.getElementById('login').hidden, ua: navigator.userAgent})`).catch((e) => ({ error: String(e) }));
    const perm = await wc.executeJavaScript(`navigator.permissions.query({name:'camera'}).then(p=>p.state).catch(e=>'err '+e)`).catch(() => '?');
    const img = await wc.capturePage();
    fs.writeFileSync(out, img.toPNG());
    console.log('SMOKE OK ' + JSON.stringify({ url: wc.getURL(), ...info, camera: perm, shot: out }));
    app.exit(0);
  });
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } });
app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  setupSession(session.defaultSession);
  createWindow();
}).catch((e) => { dialog.showErrorBox('Production IT', String(e)); app.quit(); });
app.on('window-all-closed', () => app.quit());
