// Production IT — веб-клиент (тот же API, что у Android: /prodit/video_api.php, пользователь — заголовок X-UID).
// Без сборщика: один ES-модуль. Маршруты — в адресе после #, чтобы «Назад» в браузере и на телефоне работало как в приложении.
const VERSION = '1.0.0';
const API = '/prodit/video_api.php';
const POLL_MS = 5000;          // опрос, пока нет Centrifugo
const POLL_RT_MS = 20000;      // личка «на всякий случай», когда Centrifugo подключён
const RING_SEC = 60;           // сколько секунд приглашение в звонок считается входящим звонком (как Calls.RING_SEC)
const LS = 'pitapp.';          // свой префикс: календарь /meet/ на том же сайте хранит pit.*

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get: (k, d = '') => { try { const v = localStorage.getItem(LS + k); return v === null ? d : v; } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem(LS + k, v); } catch {} },
  del: (k) => { try { localStorage.removeItem(LS + k); } catch {} },
  json: (k, d) => { try { return JSON.parse(store.get(k, '')) ?? d; } catch { return d; } },
};

// ---------- тексты ----------
const T = {
  ru: {
    tagline: 'Чат, звонки и встречи команды', email: 'Рабочий email', name: 'Как вас зовут', get_code: 'Получить код',
    code_sent: 'Код отправлен на %s. Проверьте почту (и «Спам»).', code: 'Код из письма', sign_in: 'Войти', other_email: 'Другой email',
    resend: 'Отправить код ещё раз', bad_email: 'Проверьте адрес почты', need_name: 'Напишите имя — его увидят собеседники',
    wrong_code: 'Неверный код', code_expired: 'Код устарел — запросите новый', wait: 'Подождите минуту перед новым кодом',
    limit: 'Слишком много попыток, попробуйте завтра', mail_failed: 'Не удалось отправить письмо, попробуйте позже',
    offline: 'Нет связи с сервером', about: 'О приложении',
    chats: 'Чаты', chats_sub: 'Личная переписка и звонки', team_chat: 'Общий чат', team_chat_sub: 'Вся команда Production IT',
    team: 'Команда', team_sub: 'Коллеги @%s и чат компании', team_sub_none: 'Коллеги по рабочей почте', company_chat: 'Чат компании',
    no_company: 'Вы вошли с личной почты — команды по домену нет. Войдите с рабочей почты, чтобы видеть коллег.',
    no_colleagues: 'Пока никого с @%s — пригласите коллег через «Написать по email».',
    backlog: 'Бэклог', backlog_sub: 'Планы развития Production IT — обсуждаем',
    meetings: 'Встречи', meetings_sub: 'Календарь Production IT', new_chat: 'Написать по email', new_chat_sub: 'Найти коллегу по адресу почты',
    profile: 'Профиль', sign_out: 'Выйти', sign_out_q: 'Выйти из аккаунта %s в этом браузере?', find: 'Найти',
    not_registered: '%s ещё нет в Production IT. Отправить приглашение установить приложение?', invite: 'Пригласить',
    invited: 'Приглашение отправлено', already_invited: 'Приглашение уже отправлено сегодня', self: 'Это ваш адрес', cancel: 'Отмена',
    no_meetings: 'Встреч пока нет', only_mine: 'Только мои', all: 'Все', join: 'Войти', now: 'идёт', new_meeting: 'Новая встреча',
    title: 'Тема', date: 'Дата', time: 'Время', minutes: 'Длительность, мин', guests: 'Гости — email через запятую', note: 'Комментарий',
    create: 'Создать и разослать', created: 'Встреча создана, приглашения отправлены', need_title: 'Нужны тема и хотя бы один гость',
    today: 'Сегодня', tomorrow: 'Завтра', yesterday: 'Вчера', share_link: 'Ссылка для гостей', link_copied: 'Ссылка скопирована', organizer: 'Организатор',
    r_repeat: 'Повтор', r_none: 'Не повторять', r_daily: 'Каждый день', r_weekdays: 'По будням', r_weekly: 'Каждую неделю', r_biweekly: 'Раз в две недели', r_monthly: 'Каждый месяц',
    r_until: 'До даты (необязательно)', r_until_short: 'до %s', r_scope: 'Что изменить', r_one: 'Только эту встречу', r_all: 'Всю серию', r_bad_until: 'Дата окончания раньше начала',
    r_confirm_one: 'Отменить только эту встречу? Участники получат уведомление, остальные даты серии останутся.', r_confirm_all: 'Отменить всю серию встреч? Участники получат уведомление.',
    cal_prev: 'Предыдущая неделя', cal_next: 'Следующая неделя', participants: 'Участники', close: 'Закрыть', edit_meeting: 'Изменить', save: 'Сохранить', saved: 'Изменения сохранены, участники получат письмо',
    cancel_meeting: 'Отменить встречу', confirm_cancel: 'Отменить встречу? Участники получат уведомление.', cancelled: 'Встреча отменена', admin_note: 'Вы админ и только поэтому видите эту встречу',
    cal_grid: 'Календарь', no_chats: 'Диалогов пока нет. Напишите коллеге по email.', pick_chat: 'Выберите диалог слева',
    message: 'Сообщение…', send: 'Отправить', delete_q: 'Удалить сообщение у всех?', delete: 'Удалить', deleted: 'Удалено',
    too_fast: 'Не так быстро — подождите пару секунд', chat_closed: 'Общий чат закрыт администратором', blocked: 'Собеседник ограничил личные сообщения',
    no_msgs: 'Сообщений пока нет — напишите первым', call: 'Позвонить', plan: 'Запланировать встречу',
    call_invite: 'Звонок — нажмите на ссылку, чтобы присоединиться', call_join: 'Присоединиться', call_incoming: 'Входящий звонок',
    call_accept: 'Принять', call_decline: 'Отклонить', call_declined: 'Звонок отклонён', call_starting: 'Начинаю звонок…',
    call_failed: 'Не удалось начать звонок', call_missed: 'Пропущенный звонок', call_title: 'Звонок · %s', leave: 'Завершить', 
    meet_with: 'Встреча: %s', backlog_hint: 'Предложения и планы развития. Видно всем, кто вошёл.',
    company_hint: 'Видят только сотрудники @%s', general_hint: 'Все, кто вошёл в Production IT',
    save: 'Сохранить', saved: 'Сохранено', language: 'Язык', notifications: 'Уведомления', notif_on: 'Включить уведомления в браузере',
    notif_ok: 'Уведомления включены', notif_denied: 'Браузер запретил уведомления — разрешите их в настройках сайта', install: 'Установить приложение',
    domain: 'Компания', online: 'в сети', guests_n: 'гостей: %s', back: 'Назад',
    login_again: 'Сессия завершена — войдите снова', email_hint: 'Адрес коллеги, например anna@company.com', meeting_soon: 'Встреча через %s мин',
    you: 'Вы',
  },
  en: {
    tagline: 'Team chat, calls and meetings', email: 'Work email', name: 'Your name', get_code: 'Get a code',
    code_sent: 'We sent a code to %s. Check your inbox (and spam).', code: 'Code from the email', sign_in: 'Sign in', other_email: 'Use another email',
    resend: 'Send the code again', bad_email: 'Check the email address', need_name: 'Enter your name — people will see it',
    wrong_code: 'Wrong code', code_expired: 'The code expired — request a new one', wait: 'Wait a minute before requesting a new code',
    limit: 'Too many attempts, try tomorrow', mail_failed: 'Could not send the email, try later',
    offline: 'No connection to the server', about: 'About',
    chats: 'Chats', chats_sub: 'Direct messages and calls', team_chat: 'Team chat', team_chat_sub: 'Everyone at Production IT',
    team: 'Team', team_sub: 'Colleagues @%s and company chat', team_sub_none: 'Colleagues by work email', company_chat: 'Company chat',
    no_company: 'You signed in with a personal email — no team by domain. Sign in with your work email to see colleagues.',
    no_colleagues: 'Nobody from @%s yet — invite colleagues via “Message by email”.',
    backlog: 'Backlog', backlog_sub: 'Production IT roadmap — let’s discuss',
    meetings: 'Meetings', meetings_sub: 'Production IT calendar', new_chat: 'Message by email', new_chat_sub: 'Find a colleague by email',
    profile: 'Profile', sign_out: 'Sign out', sign_out_q: 'Sign out of %s in this browser?', find: 'Find',
    not_registered: '%s is not on Production IT yet. Send an invitation to install the app?', invite: 'Invite',
    invited: 'Invitation sent', already_invited: 'Already invited today', self: 'This is your address', cancel: 'Cancel',
    no_meetings: 'No meetings yet', only_mine: 'Only mine', all: 'All', join: 'Join', now: 'now', new_meeting: 'New meeting',
    title: 'Title', date: 'Date', time: 'Time', minutes: 'Duration, min', guests: 'Guests — emails, comma separated', note: 'Note',
    create: 'Create and send', created: 'Meeting created, invitations sent', need_title: 'A title and at least one guest are required',
    today: 'Today', tomorrow: 'Tomorrow', yesterday: 'Yesterday', share_link: 'Link for guests', link_copied: 'Link copied', organizer: 'Organizer',
    r_repeat: 'Repeat', r_none: 'Does not repeat', r_daily: 'Every day', r_weekdays: 'Every weekday', r_weekly: 'Every week', r_biweekly: 'Every two weeks', r_monthly: 'Every month',
    r_until: 'Until (optional)', r_until_short: 'until %s', r_scope: 'Apply to', r_one: 'This meeting only', r_all: 'The whole series', r_bad_until: 'The end date is before the start',
    r_confirm_one: 'Cancel this meeting only? Participants will be notified, other dates stay.', r_confirm_all: 'Cancel the whole series? Participants will be notified.',
    cal_prev: 'Previous week', cal_next: 'Next week', participants: 'Participants', close: 'Close', edit_meeting: 'Edit', save: 'Save', saved: 'Saved, participants will get an email',
    cancel_meeting: 'Cancel meeting', confirm_cancel: 'Cancel the meeting? Participants will be notified.', cancelled: 'Meeting cancelled', admin_note: 'You are an admin — that is the only reason you can see this meeting',
    cal_grid: 'Calendar', no_chats: 'No chats yet. Message a colleague by email.', pick_chat: 'Pick a chat on the left',
    message: 'Message…', send: 'Send', delete_q: 'Delete the message for everyone?', delete: 'Delete', deleted: 'Deleted',
    too_fast: 'Too fast — wait a couple of seconds', chat_closed: 'The team chat is closed by the admin', blocked: 'This person limited direct messages',
    no_msgs: 'No messages yet — say hi', call: 'Call', plan: 'Schedule a meeting',
    call_invite: 'Call — tap the link to join', call_join: 'Join the call', call_incoming: 'Incoming call',
    call_accept: 'Accept', call_decline: 'Decline', call_declined: 'Call declined', call_starting: 'Starting the call…',
    call_failed: 'Could not start the call', call_missed: 'Missed call', call_title: 'Call · %s', leave: 'Leave', 
    meet_with: 'Meeting: %s', backlog_hint: 'Ideas and roadmap. Visible to everyone signed in.',
    company_hint: 'Only people @%s can see it', general_hint: 'Everyone signed in to Production IT',
    save: 'Save', saved: 'Saved', language: 'Language', notifications: 'Notifications', notif_on: 'Enable browser notifications',
    notif_ok: 'Notifications are on', notif_denied: 'The browser blocked notifications — allow them in site settings', install: 'Install the app',
    domain: 'Company', online: 'online', guests_n: 'guests: %s', back: 'Back',
    login_again: 'Session ended — please sign in again', email_hint: 'Colleague’s email, e.g. anna@company.com', meeting_soon: 'Meeting in %s min',
    you: 'You',
  },
};
let LANG = store.get('lang') || ((navigator.language || 'ru').toLowerCase().startsWith('ru') ? 'ru' : 'en');
if (!T[LANG]) LANG = 'en';
const t = (k, ...a) => { let s = (T[LANG][k] ?? T.en[k] ?? k); a.forEach((v) => { s = s.replace('%s', v); }); return s; };

// ---------- состояние ----------
const S = {
  uid: store.get('uid'), author: store.get('author'), email: store.get('email'), name: store.get('name'), domain: store.get('domain'),
  cfg: store.json('cfg', {}), names: store.json('names', {}),
  threads: [], unread: 0, company: null, meetings: null, role: '', onlyMine: store.get('only_mine', '1') === '1',
  view: null, rt: null, rtOk: false, timer: 0, ringing: null, seenCalls: new Set(store.json('seen_calls', [])), notified: store.json('notified', {}),
};

// ---------- API ----------
class ApiError extends Error { constructor(status, code) { super(code || ('HTTP ' + status)); this.status = status; this.code = code || ''; } }
async function api(action, { qs = {}, body = null } = {}) {
  const u = new URL(API, location.href);
  u.searchParams.set('action', action);
  for (const [k, v] of Object.entries(qs)) u.searchParams.set(k, v);
  const h = { 'Content-Type': 'application/json' };
  if (S.uid) h['X-UID'] = S.uid;
  let r;
  try {
    r = await fetch(u, { method: body ? 'POST' : 'GET', headers: h, body: body ? JSON.stringify(body) : undefined, cache: 'no-store' });
  } catch { throw new ApiError(0, 'offline'); }
  let j = null;
  try { j = await r.json(); } catch { /* не JSON */ }
  if (!r.ok || !j || j.ok === false) {
    const e = new ApiError(r.status, j && j.error);
    if (r.status === 401 && e.code === 'login required' && S.uid) { logout(true); }
    throw e;
  }
  return j;
}
function errText(e) {
  const m = (e && (e.code || e.message)) || '';
  if (m.includes('wrong code')) return t('wrong_code');
  if (m.includes('code expired')) return t('code_expired');
  if (m === 'wait') return t('wait');
  if (m.includes('daily limit')) return t('limit');
  if (m.includes('mail failed') || (e && e.status === 502)) return t('mail_failed');
  if (m.includes('bad email')) return t('bad_email');
  if (m === 'self') return t('self');
  if (m === 'too fast') return t('too_fast');
  if (m === 'chat closed') return t('chat_closed');
  if (m === 'blocked') return t('blocked');
  return t('offline');
}

// ---------- мелочи интерфейса ----------
function toast(msg, ms = 2600) {
  const el = $('toast'); el.textContent = msg; el.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => { el.hidden = true; }, ms);
}
function modal(html, onMount) {
  const m = $('modal'), c = $('modalCard');
  c.innerHTML = html; m.hidden = false;
  const close = () => { m.hidden = true; c.innerHTML = ''; m.onclick = null; document.removeEventListener('keydown', esc_); };
  const esc_ = (e) => { if (e.key === 'Escape') close(); };
  m.onclick = (e) => { if (e.target === m) close(); };
  document.addEventListener('keydown', esc_);
  onMount && onMount(c, close);
  const f = c.querySelector('[autofocus]'); f && setTimeout(() => f.focus(), 30);
  return close;
}
function confirmBox(text, okLabel, danger = false) {
  return new Promise((res) => {
    modal(`<p>${esc(text)}</p><div class="acts"><button class="btn ghost" data-a="no">${esc(t('cancel'))}</button>
      <button class="btn ${danger ? 'danger' : ''}" data-a="yes" autofocus>${esc(okLabel)}</button></div>`, (c, close) => {
      c.querySelector('[data-a=no]').onclick = () => { close(); res(false); };
      c.querySelector('[data-a=yes]').onclick = () => { close(); res(true); };
    });
  });
}
const initials = (n) => (String(n || '?').trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2) || '?').toUpperCase();
function hue(s) { let h = 0; for (const ch of String(s)) h = (h * 31 + ch.codePointAt(0)) % 360; return h; }
function ava(name, { id = '', avatar = '', cls = '', online = false, emoji = '' } = {}) {
  const st = avatar ? `background-image:url('${API}?action=avatar&id=${encodeURIComponent(avatar)}')`
    : (emoji ? '' : `background:linear-gradient(135deg,hsl(${hue(id || name)} 55% 42%),hsl(${(hue(id || name) + 40) % 360} 60% 52%))`);
  return `<span class="ava ${cls}" style="${st}">${avatar ? '' : esc(emoji || initials(name))}${online ? '<i class="dot"></i>' : ''}</span>`;
}
const linkify = (s) => esc(s).replace(/https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"]/g, (u) => `<a href="${u}" target="_blank" rel="noopener">${u}</a>`);
function dayLabel(d) {
  const a = new Date(d); a.setHours(0, 0, 0, 0);
  const b = new Date(); b.setHours(0, 0, 0, 0);
  const diff = Math.round((a - b) / 86400000);
  if (diff === 0) return t('today');
  if (diff === 1) return t('tomorrow');
  if (diff === -1) return t('yesterday');
  return new Date(d).toLocaleDateString(LANG, { weekday: 'long', day: 'numeric', month: 'long' });
}
const hm = (d) => new Date(d).toLocaleTimeString(LANG, { hour: '2-digit', minute: '2-digit' });
function shortTime(iso) {
  const d = new Date(iso), now = new Date();
  if (d.toDateString() === now.toDateString()) return hm(d);
  if (now - d < 6 * 86400000) return d.toLocaleDateString(LANG, { weekday: 'short' });
  return d.toLocaleDateString(LANG, { day: 'numeric', month: 'short' });
}
function rememberName(peer, name) {
  if (!peer || !name || S.names[peer] === name) return;
  S.names[peer] = name; store.set('names', JSON.stringify(S.names));
}
const callLink = (text) => { const m = /https:\/\/[^\s/]+(?:\/[^\s]*)?\/rooms\/([A-Za-z0-9_-]{6,64})/.exec(text || ''); return m ? m[0] : null; };
const isCall = (text) => String(text || '').startsWith('📞') && !!callLink(text);
const roomUrl = (link) => link + (link.includes('?') ? '&' : '?') + 'autojoin=1&name=' + encodeURIComponent(S.name || 'Guest');

function applyLang() {
  document.documentElement.lang = LANG;
  document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = t(el.dataset.t); });
  document.querySelectorAll('[data-lang-switch]').forEach((box) => {
    box.innerHTML = ['ru', 'en'].map((l) => `<button type="button" class="${l === LANG ? 'on' : ''}" data-l="${l}">${l.toUpperCase()}</button>`).join('');
    box.querySelectorAll('button').forEach((b) => { b.onclick = () => setLang(b.dataset.l); });
  });
  $('lEmail').placeholder = 'name@company.com';
}
function setLang(l) { LANG = l; store.set('lang', l); applyLang(); if (S.uid) { renderNav(); route(); } }

// ---------- вход ----------
function showLogin() {
  $('boot').hidden = true; $('app').hidden = true; $('login').hidden = false;
  $('stepEmail').hidden = false; $('stepCode').hidden = true; $('lErr').textContent = '';
  $('lEmail').value = store.get('last_email'); $('lName').value = store.get('last_name');
  setTimeout(() => ($('lEmail').value ? $('lName') : $('lEmail')).focus(), 50);
}
function initLogin() {
  const form = $('loginForm');
  const start = async () => {
    const email = $('lEmail').value.trim().toLowerCase();
    $('lErr').textContent = '';
    if (!/^[^@\s]+@[^@\s]+\.[a-z]{2,}$/.test(email)) { $('lErr').textContent = t('bad_email'); return; }
    $('lGet').disabled = true; $('lResend').disabled = true;
    try {
      await api('prodit_auth_start', { body: { email, lang: LANG } });
      store.set('last_email', email); store.set('last_name', $('lName').value.trim());
      $('stepEmail').hidden = true; $('stepCode').hidden = false;
      $('lSent').textContent = t('code_sent', email); $('lCode').value = ''; $('lCode').focus();
    } catch (e) { $('lErr').textContent = errText(e); } finally { $('lGet').disabled = false; $('lResend').disabled = false; }
  };
  const verify = async () => {
    const code = $('lCode').value.replace(/\D/g, '');
    $('lErr').textContent = '';
    if (code.length !== 6) { $('lErr').textContent = t('wrong_code'); return; }
    $('lVerify').disabled = true;
    try {
      const o = await api('prodit_auth_verify', { body: { email: store.get('last_email'), code, name: $('lName').value.trim() } });
      Object.assign(S, { uid: o.uid, author: o.author, email: o.email, name: o.name || $('lName').value.trim(), domain: o.domain || '' });
      for (const k of ['uid', 'author', 'email', 'name', 'domain']) store.set(k, S[k]);
      $('login').hidden = true;
      startApp();
      if (!S.name) askName();
    } catch (e) { $('lErr').textContent = errText(e); } finally { $('lVerify').disabled = false; }
  };
  form.onsubmit = (e) => { e.preventDefault(); ($('stepCode').hidden ? start : verify)(); };
  $('lCode').oninput = () => { if ($('lCode').value.replace(/\D/g, '').length === 6) verify(); };
  $('lResend').onclick = start;
  $('lOther').onclick = () => { $('stepEmail').hidden = false; $('stepCode').hidden = true; $('lErr').textContent = ''; $('lEmail').focus(); };
}
function askName() {
  modal(`<h3>${esc(t('profile'))}</h3><p class="hint">${esc(t('need_name'))}</p>
    <form id="nmF"><label>${esc(t('name'))}<input id="nmI" maxlength="40" autofocus required></label>
    <div class="acts"><button class="btn">${esc(t('save'))}</button></div></form>`, (c, close) => {
    c.querySelector('#nmF').onsubmit = async (e) => { e.preventDefault(); const n = c.querySelector('#nmI').value.trim(); if (!n) return; await saveName(n); close(); };
  });
}
async function saveName(n) {
  try { await api('hello', { body: { name: n } }); S.name = n; store.set('name', n); renderNav(); toast(t('saved')); } catch (e) { toast(errText(e)); }
}
function logout(expired = false) {
  for (const k of ['uid', 'author', 'email', 'name', 'domain', 'cfg', 'names', 'seen_calls', 'notified']) store.del(k);
  Object.assign(S, { uid: '', author: '', email: '', name: '', domain: '', threads: [], unread: 0, company: null, meetings: null, role: '', names: {} });
  clearInterval(S.timer); rtStop(); closeCall();
  showLogin();
  if (expired) toast(t('login_again'));
}

// ---------- навигация ----------
const NAV = [
  { r: 'chats', ic: '💬', k: 'chats', sub: () => t('chats_sub') },
  { r: 'general', ic: '👥', k: 'team_chat', sub: () => t('team_chat_sub') },
  { r: 'team', ic: '🏢', k: 'team', sub: () => (S.domain ? t('team_sub', S.domain) : t('team_sub_none')) },
  { r: 'meet', ic: '📅', k: 'meetings', sub: () => t('meetings_sub') },
  { r: 'backlog', ic: '📋', k: 'backlog', sub: () => t('backlog_sub') },
  { r: 'find', ic: '✉', k: 'new_chat', sub: () => t('new_chat_sub') },
  { r: 'profile', ic: '👤', k: 'profile', sub: () => S.email },
];
function renderNav() {
  $('meLine').textContent = (S.name || '') + (S.email ? ' · ' + S.email : '');
  const cur = (location.hash.slice(2).split('/')[0]) || '';
  const sec = cur === 'dm' ? 'chats' : cur;
  $('nav').innerHTML = NAV.map((n) => `<a href="#/${n.r}" class="${n.r === sec ? 'on' : ''}" data-r="${n.r}">
    <span class="ic">${n.ic}</span><span class="tx"><span class="tt">${esc(t(n.k))}</span><span class="ts">${esc(n.sub())}</span></span>
    ${n.r === 'chats' && S.unread ? `<span class="badge">${S.unread}</span>` : ''}<span class="arrow">›</span></a>`).join('');
  $('nav').querySelector('[data-r=find]').onclick = (e) => { e.preventDefault(); findDialog(); };
  document.title = (S.unread ? `(${S.unread}) ` : '') + 'Production IT';
  $('ver').textContent = 'v' + VERSION;
}
const wide = () => matchMedia('(min-width: 861px)').matches;
function setLayout(depth, nolist) {
  const a = $('app'); a.dataset.depth = depth; a.classList.toggle('nolist', !!nolist);
}
const backBtn = (href) => `<a class="icon-btn back" href="${href}" aria-label="${esc(t('back'))}">‹</a>`;

function route() {
  if (!S.uid) return;
  const p = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  const sec = p[0] || '';
  renderNav();
  if (S.view && S.view.stop) S.view.stop();
  S.view = null;
  if (!sec) {   // главный: на телефоне — карточки разделов, на широком экране — чаты
    if (wide()) { location.replace('#/chats'); return; }
    setLayout(0, false); $('main').innerHTML = ''; $('list').innerHTML = ''; return;
  }
  switch (sec) {
    case 'chats': renderThreads(); placeholder(t('pick_chat')); setLayout(1, false); break;
    case 'dm': renderThreads(); openChat({ kind: 'dm', peer: p[1], back: '#/chats' }); setLayout(2, false); break;
    case 'general': openChat({ kind: 'room', room: '', title: t('team_chat'), sub: t('general_hint'), icon: '👥', back: '#/' }); setLayout(2, true); break;
    case 'backlog': openChat({ kind: 'room', room: 'backlog', title: t('backlog'), sub: t('backlog_hint'), icon: '📋', back: '#/' }); setLayout(2, true); break;
    case 'team':
      renderTeam();
      if (p[1] === 'co' && S.domain) { openChat({ kind: 'room', room: 'co:' + S.domain, title: t('company_chat'), sub: t('company_hint', S.domain), icon: '🏢', back: '#/team' }); setLayout(2, false); } else { placeholder(t('team_sub', S.domain || '…'), '🏢'); setLayout(1, false); }
      break;
    case 'meet':
      renderMeetings();
      renderCalendar();   // на широком экране календарь всегда рядом со списком; на телефоне — отдельный экран #/meet/cal
      setLayout(p[1] === 'cal' ? 2 : 1, false);
      break;
    case 'profile': renderProfile(); setLayout(2, true); break;
    case 'find': findDialog(); location.replace('#/' + (wide() ? 'chats' : '')); break;
    default: location.replace('#/');
  }
}
function placeholder(text, icon = '💬') {
  $('main').innerHTML = `<div class="placeholder"><div><span class="logo">Production IT</span><div style="font-size:40px;margin:14px 0">${icon}</div>${esc(text)}</div></div>`;
}

// ---------- диалоги ----------
function threadsHtml() {
  if (!S.threads.length) return `<div class="empty"><span class="big">💬</span>${esc(t('no_chats'))}<br><br><button class="btn sm" data-find>${esc(t('new_chat'))}</button></div>`;
  const cur = location.hash.startsWith('#/dm/') ? decodeURIComponent(location.hash.slice(5)) : '';
  return S.threads.map((th) => {
    let text = th.text || '';
    if (isCall(text)) text = '📞 ' + (th.mine ? t('call') : t('call_incoming'));
    return `<a class="item ${th.unread ? 'unread' : ''} ${th.peer === cur ? 'on' : ''}" href="#/dm/${encodeURIComponent(th.peer)}">
      ${ava(th.name, { id: th.peer, avatar: th.avatar })}
      <span class="tx"><span class="t1"><span class="nm">${esc(th.name)}</span><span class="tm">${esc(shortTime(th.ts))}</span></span>
      <span class="t2"><span>${th.mine ? esc(t('you')) + ': ' : ''}${esc(text)}</span>${th.unread ? `<span class="badge">${th.unread}</span>` : ''}</span></span></a>`;
  }).join('');
}
function renderThreads() {
  $('list').innerHTML = `<div class="head">${backBtn('#/')}<h2>${esc(t('chats'))}</h2>
    <button class="icon-btn" data-find title="${esc(t('new_chat'))}" aria-label="${esc(t('new_chat'))}">✉</button></div>
    <div class="scroll" id="threads">${threadsHtml()}</div>`;
  $('list').querySelectorAll('[data-find]').forEach((b) => { b.onclick = findDialog; });
}
function refreshThreadsList() {
  const box = $('threads');
  if (box) { box.innerHTML = threadsHtml(); box.querySelectorAll('[data-find]').forEach((b) => { b.onclick = findDialog; }); }
}
async function loadThreads() {
  const o = await api('dm_threads');
  S.threads = o.items || [];
  S.unread = o.unread || 0;
  S.threads.forEach((th) => rememberName(th.peer, th.name));
  refreshThreadsList();
  renderNav();
  checkIncoming();
  notifyNew();
}

// ---------- окно переписки (личка или комната общего чата) ----------
function openChat(v) {
  const main = $('main');
  const isDm = v.kind === 'dm';
  if (isDm && !/^[a-f0-9]{16}$/.test(v.peer || '')) { location.replace('#/chats'); return; }
  const title = isDm ? (S.names[v.peer] || '…') : v.title;
  main.innerHTML = `<div class="head">${backBtn(v.back)}
      ${isDm ? ava(title, { id: v.peer }) : ava('', { emoji: v.icon, cls: 'room' })}
      <div class="htx"><h2 id="chTitle">${esc(title)}</h2><div class="hsub">${esc(isDm ? '' : v.sub)}</div></div>
      ${isDm ? `<button class="icon-btn" id="chCall" title="${esc(t('call'))}" aria-label="${esc(t('call'))}">📞</button>
      <button class="icon-btn" id="chPlan" title="${esc(t('plan'))}" aria-label="${esc(t('plan'))}">📅</button>` : ''}</div>
    <div class="msgs" id="msgs" aria-live="polite"></div>
    <form class="compose" id="compose"><textarea id="txt" rows="1" maxlength="2000" placeholder="${esc(t('message'))}" aria-label="${esc(t('message'))}"></textarea>
      <button class="send" id="sendBtn" aria-label="${esc(t('send'))}" title="${esc(t('send'))}">➤</button></form>`;
  const st = { ...v, items: [], sig: '', busy: false, first: true };
  S.view = st;
  const txt = $('txt');
  const grow = () => { txt.style.height = 'auto'; txt.style.height = Math.min(160, txt.scrollHeight + 2) + 'px'; };
  txt.oninput = grow;
  txt.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('compose').requestSubmit(); } };
  $('compose').onsubmit = async (e) => {
    e.preventDefault();
    const text = txt.value.trim();
    if (!text || st.busy) return;
    st.busy = true; $('sendBtn').disabled = true;
    try {
      if (isDm) await api('dm_post', { body: { to: v.peer, to_name: S.names[v.peer] || '', name: S.name || 'Guest', text } });
      else await api('chat_post', { body: { text, name: S.name || 'Guest', ...(v.room ? { room: v.room } : {}) } });
      txt.value = ''; grow();
      await loadChat(st, true);
      if (isDm) loadThreads().catch(() => {});
    } catch (er) { toast(errText(er)); } finally { st.busy = false; $('sendBtn').disabled = false; txt.focus(); }
  };
  if (isDm) {
    $('chCall').onclick = () => startCall(v.peer, S.names[v.peer] || '');
    $('chPlan').onclick = () => meetingDialog({ withAuthor: v.peer, withName: S.names[v.peer] || '' });
  }
  $('msgs').onclick = async (e) => {
    const del = e.target.closest('[data-del]');
    if (del) {
      if (!(await confirmBox(t('delete_q'), t('delete'), true))) return;
      try { await api(isDm ? 'dm_delete' : 'chat_delete', { body: { ref: del.dataset.del } }); await loadChat(st, true); toast(t('deleted')); } catch (er) { toast(errText(er)); }
      return;
    }
    const j = e.target.closest('[data-join]');
    if (j) { e.preventDefault(); openCall(j.dataset.join, isDm ? t('call_title', S.names[v.peer] || '') : v.title); }
  };
  if (wide()) setTimeout(() => txt.focus(), 30);
  loadChat(st, true).catch((er) => {
    $('msgs').innerHTML = `<div class="empty">${esc(er.status === 403 ? '🔒' : errText(er))}</div>`;
    if (er.status === 403) $('compose').hidden = true;
  });
}
async function loadChat(st, force = false) {
  if (S.view !== st || (!force && document.hidden)) return;
  const o = st.kind === 'dm' ? await api('dm', { qs: { with: st.peer, since: 0 } }) : await api('chat', { qs: st.room ? { room: st.room } : {} });
  if (S.view !== st) return;
  const items = o.items || [];
  if (st.kind === 'dm') {
    const theirs = items.filter((m) => !m.mine).pop();
    if (theirs) { rememberName(st.peer, theirs.name); $('chTitle') && ($('chTitle').textContent = theirs.name); }
  }
  const sig = items.map((m) => m.id).join(',');
  if (sig === st.sig) return;
  st.sig = sig; st.items = items;
  const box = $('msgs');
  const atBottom = st.first || box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  box.innerHTML = items.length ? msgsHtml(items, st) : `<div class="empty"><span class="big">${st.kind === 'dm' ? '👋' : '💬'}</span>${esc(t('no_msgs'))}</div>`;
  if (atBottom) box.scrollTop = box.scrollHeight;
  st.first = false;
  if (o.closed && !st.room) { const c = $('compose'); if (c && !box.previousElementSibling.classList.contains('notice')) box.insertAdjacentHTML('beforebegin', `<div class="notice">${esc(t('chat_closed'))}</div>`); }
}
function msgsHtml(items, st) {
  let out = '', lastDay = '', prev = null;
  for (const m of items) {
    const d = new Date(m.ts).toDateString();
    if (d !== lastDay) { out += `<div class="day">${esc(dayLabel(m.ts))}</div>`; lastDay = d; prev = null; }
    const same = prev && prev.author === m.author && !prev.mine === !m.mine;
    const showWho = st.kind !== 'dm' && !m.mine && !same;
    let body;
    if (isCall(m.text)) {
      const link = callLink(m.text);
      body = `<div class="callcard"><span class="ci">📞</span><div><b>${esc(m.mine ? t('call') : t('call_incoming'))}</b>
        <a href="${esc(link)}" data-join="${esc(link)}">${esc(t('call_join'))}</a></div></div>`;
    } else if (String(m.text).startsWith('📵')) {
      body = `<div class="callcard"><span class="ci">📵</span><div>${esc(t('call_declined'))}</div></div>`;
    } else {
      body = `<div class="txt">${linkify(m.text)}</div>`;
      if (m.image) body = `<img class="pic" alt="" loading="lazy" src="${API}?action=dm_image&f=${encodeURIComponent(m.image)}">` + (m.text === '🖼' ? '' : body);
      if (m.share && m.share.url) body += `<div class="txt">📁 <a href="${esc(m.share.file || m.share.url)}" target="_blank" rel="noopener">${esc(m.share.name || m.share.url)}</a></div>`;
    }
    out += `<div class="msg ${m.mine ? 'mine' : ''}">
      ${m.mine ? '' : ava(m.name, { id: m.author, avatar: m.avatar, cls: same ? 'hide' : '' })}
      <div class="bub">${showWho ? `<div class="who">${esc(m.name)}</div>` : ''}${body}
      <div class="meta">${esc(hm(m.ts))}${m.mine && st.kind === 'dm' ? (m.seen ? ' ✓✓' : ' ✓') : ''}</div>
      ${m.mine ? `<button class="del" data-del="${m.id}" title="${esc(t('delete'))}" aria-label="${esc(t('delete'))}">✕</button>` : ''}</div></div>`;
    prev = m;
  }
  return out;
}

// ---------- звонки ----------
function newRoom() {
  const abc = 'abcdefghijkmnpqrstuvwxyz23456789';
  const r = crypto.getRandomValues(new Uint8Array(14));
  return (S.cfg.room_prefix || 'productionit-') + Array.from(r, (b) => abc[b % abc.length]).join('');
}
async function startCall(peer, peerName) {
  toast(t('call_starting'));
  await loadConfig();
  const link = (S.cfg.rooms_url || 'https://efir.umno.cloud/rooms/') + newRoom();
  try {
    await api('dm_post', { body: { to: peer, to_name: peerName, name: S.name || 'Guest', text: '📞 ' + t('call_invite') + '\n' + link } });
  } catch (e) { toast(e.code === 'blocked' ? t('blocked') : t('call_failed')); return; }
  openCall(link, t('call_title', peerName));
  if (S.view && S.view.kind === 'dm') loadChat(S.view, true).catch(() => {});
  loadThreads().catch(() => {});
}
function openCall(link, title) {
  const u = roomUrl(link);
  $('callTitle').textContent = title || '';
  $('callTab').href = u;
  $('callFrame').src = u;
  $('call').hidden = false;
}
function closeCall() { $('callFrame').src = 'about:blank'; $('call').hidden = true; }
function checkIncoming() {
  if (S.ringing) return;
  const now = Date.now();
  for (const th of S.threads) {
    if (th.mine || !th.unread || !isCall(th.text)) continue;
    const key = th.peer + '|' + th.ts;
    if (S.seenCalls.has(key)) continue;
    S.seenCalls.add(key); store.set('seen_calls', JSON.stringify([...S.seenCalls].slice(-50)));
    if (now - new Date(th.ts).getTime() > RING_SEC * 1000) continue;   // старое приглашение — уже пропущенный звонок
    ring(th);
    break;
  }
}
function ring(th) {
  const link = callLink(th.text);
  const tone = beep();
  S.ringing = th;
  if (document.hidden) sysNotify(t('call_incoming'), th.name, 'call');
  const close = modal(`<div class="ring">${ava(th.name, { id: th.peer, avatar: th.avatar })}<h3>${esc(th.name)}</h3><p class="hint">${esc(t('call_incoming'))}</p>
    <div class="acts"><button class="btn danger" data-a="no">${esc(t('call_decline'))}</button><button class="btn" data-a="yes" autofocus>${esc(t('call_accept'))}</button></div></div>`, (c, cl) => {
    const done = () => { tone.stop(); S.ringing = null; clearTimeout(timer); cl(); };
    const timer = setTimeout(done, RING_SEC * 1000);
    c.querySelector('[data-a=yes]').onclick = () => { done(); openCall(link, t('call_title', th.name)); api('dm', { qs: { with: th.peer, since: 2000000000 } }).catch(() => {}); };
    c.querySelector('[data-a=no]').onclick = () => {
      done();
      api('dm_post', { body: { to: th.peer, to_name: th.name, name: S.name || 'Guest', text: '📵 ' + t('call_declined') } }).then(() => loadThreads()).catch(() => {});
    };
  });
  return close;
}
function beep() {   // тихий сигнал входящего звонка; браузер может не дать звук без нажатия — тогда молча
  let ctx = null, iv = 0;
  try {
    ctx = new (window.AudioContext || window.webkitAudioContext)();
    const one = () => {
      for (const [f, at] of [[880, 0], [660, 0.25]]) {
        const o = ctx.createOscillator(), g = ctx.createGain();
        o.frequency.value = f; g.gain.setValueAtTime(0.0001, ctx.currentTime + at);
        g.gain.exponentialRampToValueAtTime(0.12, ctx.currentTime + at + 0.03); g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + at + 0.22);
        o.connect(g).connect(ctx.destination); o.start(ctx.currentTime + at); o.stop(ctx.currentTime + at + 0.25);
      }
    };
    one(); iv = setInterval(one, 1600);
  } catch { /* без звука */ }
  return { stop() { clearInterval(iv); try { ctx && ctx.close(); } catch {} } };
}

// ---------- уведомления браузера (когда вкладка свёрнута) ----------
function sysNotify(title, body, tag) {
  try {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    const n = new Notification(title, { body, tag, icon: 'icon-192.png' });
    n.onclick = () => { window.focus(); n.close(); };
  } catch { /* нет поддержки */ }
}
function notifyNew() {
  let changed = false;
  for (const th of S.threads) {
    if (th.mine || !th.unread) continue;
    if (S.notified[th.peer] === th.ts) continue;
    const first = S.notified[th.peer] === undefined && notifyNew.init !== true;
    S.notified[th.peer] = th.ts; changed = true;
    if (!first && document.hidden && !isCall(th.text)) sysNotify(th.name, th.text.slice(0, 140), 'dm-' + th.peer);
  }
  notifyNew.init = true;
  if (changed) store.set('notified', JSON.stringify(S.notified));
}

// ---------- «Написать по email» ----------
function findDialog() {
  modal(`<h3>${esc(t('new_chat'))}</h3><p class="hint">${esc(t('new_chat_sub'))}</p>
    <form id="fF" novalidate><label>${esc(t('email'))}<input id="fE" type="email" inputmode="email" placeholder="${esc(t('email_hint'))}" autofocus></label>
    <p class="err" id="fErr"></p><div class="acts"><button type="button" class="btn ghost" data-a="no">${esc(t('cancel'))}</button>
    <button class="btn" id="fGo">${esc(t('find'))}</button></div></form>`, (c, close) => {
    c.querySelector('[data-a=no]').onclick = close;
    c.querySelector('#fF').onsubmit = async (e) => {
      e.preventDefault();
      const email = c.querySelector('#fE').value.trim().toLowerCase();
      const err = c.querySelector('#fErr');
      err.textContent = '';
      if (!/^[^@\s]+@[^@\s]+\.[a-z]{2,}$/.test(email)) { err.textContent = t('bad_email'); return; }
      c.querySelector('#fGo').disabled = true;
      try {
        const o = await api('prodit_find', { qs: { email } });
        rememberName(o.author, o.name);
        close();
        location.hash = '#/dm/' + o.author;
      } catch (er) {
        c.querySelector('#fGo').disabled = false;
        if (er.code === 'not registered') { close(); inviteDialog(email); } else err.textContent = errText(er);
      }
    };
  });
}
function inviteDialog(email) {
  confirmBox(t('not_registered', email), t('invite')).then(async (ok) => {
    if (!ok) return;
    try { const o = await api('prodit_invite', { body: { email, lang: LANG } }); toast(o.already ? t('already_invited') : t('invited')); } catch (e) { toast(errText(e)); }
  });
}

// ---------- команда ----------
async function renderTeam() {
  const draw = () => {
    const c = S.company;
    let body;
    if (!c) body = '<div class="empty">…</div>';
    else if (!c.domain) body = `<div class="empty"><span class="big">🏢</span>${esc(t('no_company'))}</div>`;
    else {
      const on = location.hash === '#/team/co';
      body = `<a class="item ${on ? 'on' : ''}" href="#/team/co">${ava('', { emoji: '🏢', cls: 'room' })}<span class="tx"><span class="t1"><span class="nm">${esc(t('company_chat'))}</span></span>
        <span class="t2"><span>@${esc(c.domain)}</span></span></span></a><div class="sect">${esc(t('team'))} · ${c.members.length}</div>`;
      body += c.members.length ? c.members.map((m) => `<a class="item" href="#/dm/${encodeURIComponent(m.author)}" data-name="${esc(m.name)}" data-peer="${esc(m.author)}">
        ${ava(m.name, { id: m.author, online: m.online })}<span class="tx"><span class="t1"><span class="nm">${esc(m.name)}</span>${m.online ? `<span class="tm live">${esc(t('online'))}</span>` : ''}</span>
        <span class="t2"><span>${esc(m.email)}</span></span></span></a>`).join('')
        : `<div class="empty">${esc(t('no_colleagues', c.domain))}<br><br><button class="btn sm" data-find>${esc(t('new_chat'))}</button></div>`;
    }
    $('list').innerHTML = `<div class="head">${backBtn('#/')}<h2>${esc(t('team'))}</h2></div><div class="scroll">${body}</div>`;
    $('list').querySelectorAll('[data-find]').forEach((b) => { b.onclick = findDialog; });
    $('list').querySelectorAll('[data-peer]').forEach((a) => { a.onclick = () => rememberName(a.dataset.peer, a.dataset.name); });
  };
  draw();
  try {
    S.company = await api('prodit_company');
    if (S.company.domain !== undefined && S.company.domain !== S.domain) { S.domain = S.company.domain; store.set('domain', S.domain); }
  } catch (e) { S.company = S.company || { domain: S.domain, members: [] }; toast(errText(e)); }
  if (location.hash.startsWith('#/team')) draw();
}

// ---------- встречи ----------
const RECURS = ['daily', 'weekdays', 'weekly', 'biweekly', 'monthly'];
const meetEnd = (m) => new Date(new Date(m.start).getTime() + (m.minutes || 60) * 60000);
const shownMeetings = () => (S.meetings || []).filter((m) => !S.role || !S.onlyMine || m.mine);
const recurLabel = (m) => (m.recur ? `↻ ${t('r_' + m.recur)}${m.until ? ' ' + t('r_until_short', new Date(m.until + 'T12:00').toLocaleDateString(LANG)) : ''}` : '');
function joinBtn(b) { b.onclick = (e) => { e.stopPropagation(); openCall(b.dataset.join, b.dataset.title); }; }
async function loadMeetings() {
  try { const r = await api('prodit_meetings'); S.meetings = r.items || []; S.role = r.role || ''; } catch (e) { S.meetings = S.meetings || []; toast(errText(e)); }
}
async function renderMeetings(reload = true) {
  const draw = () => {
    let body;
    let list = [];
    if (!S.meetings) body = '<div class="empty">…</div>';
    else {
      const now = Date.now();
      const seen = new Set();   // от серии в списке — только ближайшая дата, все даты видны в календаре
      list = shownMeetings().filter((m) => meetEnd(m).getTime() > now - 3600000).sort((a, b) => new Date(a.start) - new Date(b.start))
        .filter((m) => { if (!m.recur) return true; if (seen.has(m.uid)) return false; seen.add(m.uid); return true; });
      if (!list.length) body = `<div class="empty"><span class="big">📅</span>${esc(t('no_meetings'))}</div>`;
      else {
        body = ''; let lastDay = '';
        list.forEach((m, i) => {
          const st = new Date(m.start), end = meetEnd(m);
          if (st.toDateString() !== lastDay) { body += `<div class="sect">${esc(dayLabel(st))}</div>`; lastDay = st.toDateString(); }
          const live = now >= st.getTime() - 5 * 60000 && now <= end.getTime();
          const guests = (m.emails || []).length;
          body += `<div class="meet" data-open="${i}"><div class="when">${esc(hm(st))}<small>${esc(hm(end))}</small></div>
            <div class="tx"><div class="nm">${esc(m.title || '—')}</div><div class="sub">${esc(t('organizer'))}: ${esc(m.organizer || m.organizer_email || '—')}${guests ? ' · ' + esc(t('guests_n', guests)) : ''}</div>
            ${m.recur ? `<div class="sub rep">${esc(recurLabel(m))}</div>` : ''}${m.admin_view ? `<div class="sub warn">${esc(t('admin_note'))}</div>` : ''}${live ? `<div class="live">● ${esc(t('now'))}</div>` : ''}</div>
            <div class="acts">${m.link ? `<button class="btn sm" data-join="${esc(m.link)}" data-title="${esc(m.title || '')}">${esc(t('join'))}</button>
            <button class="chip" data-copy="${esc(m.link)}" title="${esc(t('share_link'))}">🔗</button>` : ''}</div></div>`;
        });
      }
    }
    const on = location.hash === '#/meet/cal';
    $('list').innerHTML = `<div class="head">${backBtn('#/')}<h2>${esc(t('meetings'))}</h2>
      <button class="icon-btn" id="mNew" title="${esc(t('new_meeting'))}" aria-label="${esc(t('new_meeting'))}">＋</button></div>
      <div class="list-tools ${S.role ? '' : 'phone-only'}">${S.role ? `<button class="chip ${S.onlyMine ? 'on' : ''}" data-f="1">${esc(t('only_mine'))}</button><button class="chip ${S.onlyMine ? '' : 'on'}" data-f="0">${esc(t('all'))}</button>` : ''}
      <a class="chip cal-link ${on ? 'on' : ''}" href="#/meet/cal" style="margin-left:auto">▦ ${esc(t('cal_grid'))}</a></div><div class="scroll">${body}</div>`;
    $('mNew').onclick = () => meetingDialog({});
    $('list').querySelectorAll('[data-f]').forEach((b) => { b.onclick = () => { S.onlyMine = b.dataset.f === '1'; store.set('only_mine', S.onlyMine ? '1' : '0'); draw(); drawCalendar(); }; });
    $('list').querySelectorAll('[data-join]').forEach(joinBtn);
    $('list').querySelectorAll('[data-open]').forEach((r) => { r.onclick = () => meetingView(list[+r.dataset.open]); });
    $('list').querySelectorAll('[data-copy]').forEach((b) => { b.onclick = (e) => { e.stopPropagation(); copyLink(b.dataset.copy); }; });
  };
  draw();
  if (!reload) return;
  await loadMeetings();
  if (location.hash.startsWith('#/meet')) { draw(); drawCalendar(); }
}
function copyLink(link) { navigator.clipboard?.writeText(link).then(() => toast(t('link_copied')), () => prompt(t('share_link'), link)); }

// календарь: на широком экране неделя, на телефоне — день (вкладки дней), как у встреч в списке — данные prodit_meetings
const CAL_HOUR = 48;   // высота часа, px
const startOfWeek = (d) => { const x = new Date(d); x.setHours(0, 0, 0, 0); x.setDate(x.getDate() - ((x.getDay() + 6) % 7)); return x; };
const CAL = { week: startOfWeek(new Date()), day: (new Date().getDay() + 6) % 7, scrolled: false };
function renderCalendar() {
  CAL.scrolled = false;
  $('main').innerHTML = `<div class="head cal-head">${backBtn('#/meet')}<h2 id="calRange"></h2>
    <button class="icon-btn" id="calPrev" aria-label="${esc(t('cal_prev'))}">‹</button>
    <button class="chip" id="calToday">${esc(t('today'))}</button>
    <button class="icon-btn" id="calNext" aria-label="${esc(t('cal_next'))}">›</button>
    <button class="icon-btn" id="calNew" title="${esc(t('new_meeting'))}" aria-label="${esc(t('new_meeting'))}">＋</button></div>
    <div class="cal-days" id="calDays"></div><div class="cal-scroll" id="calScroll"></div>`;
  const go = (dd) => { CAL.week = new Date(CAL.week.getFullYear(), CAL.week.getMonth(), CAL.week.getDate() + dd); drawCalendar(); };
  $('calPrev').onclick = () => go(-7);
  $('calNext').onclick = () => go(7);
  $('calToday').onclick = () => { CAL.week = startOfWeek(new Date()); CAL.day = (new Date().getDay() + 6) % 7; CAL.scrolled = false; drawCalendar(); };
  $('calNew').onclick = () => meetingDialog({});
  drawCalendar();
}
/** Раскладка дня: пересекающиеся встречи — рядом, каждая в своей дорожке. */
function layDay(items) {
  const out = []; let group = [], groupEnd = 0;
  const flush = () => {
    const lanes = [];
    for (const e of group) { let i = lanes.findIndex((x) => x <= e.s); if (i < 0) { i = lanes.length; lanes.push(0); } lanes[i] = e.e; e.lane = i; }
    group.forEach((e) => { e.lanes = lanes.length; out.push(e); }); group = [];
  };
  items.sort((a, b) => a.s - b.s).forEach((e) => { if (group.length && e.s >= groupEnd) { flush(); groupEnd = 0; } group.push(e); groupEnd = Math.max(groupEnd, e.e); });
  flush();
  return out;
}
function drawCalendar() {
  const scroll = $('calScroll');
  if (!scroll) return;
  const now = new Date();
  const days = Array.from({ length: 7 }, (_, i) => new Date(CAL.week.getFullYear(), CAL.week.getMonth(), CAL.week.getDate() + i));
  const fmt = (d, o) => d.toLocaleDateString(LANG, o);
  $('calRange').textContent = wide() ? `${fmt(days[0], { day: 'numeric', month: 'short' })} — ${fmt(days[6], { day: 'numeric', month: 'short', year: 'numeric' })}`
    : days[0].getMonth() === days[6].getMonth() ? `${days[0].getDate()}–${fmt(days[6], { day: 'numeric', month: 'short' })}` : `${days[0].getDate()}.${days[0].getMonth() + 1}–${days[6].getDate()}.${days[6].getMonth() + 1}`;
  $('calDays').innerHTML = '<span class="cal-gut"></span>' + days.map((d, i) => `<button class="cal-day ${d.toDateString() === now.toDateString() ? 'today' : ''} ${i === CAL.day ? 'on' : ''}" data-i="${i}">
    ${esc(fmt(d, { weekday: 'short' }))}<b>${d.getDate()}</b></button>`).join('');
  $('calDays').querySelectorAll('[data-i]').forEach((b) => { b.onclick = () => { CAL.day = +b.dataset.i; drawCalendar(); }; });
  const items = shownMeetings();
  const hours = Array.from({ length: 24 }, (_, h) => `<div class="cal-hour">${h ? String(h).padStart(2, '0') + ':00' : ''}</div>`).join('');
  const nowTop = (now.getHours() + now.getMinutes() / 60) * CAL_HOUR;
  const shown = [];
  const cols = days.map((d, i) => {
    const today = d.toDateString() === now.toDateString();
    const list = items.filter((m) => new Date(m.start).toDateString() === d.toDateString()).map((m) => {
      const st = new Date(m.start), s = st.getHours() * 60 + st.getMinutes();
      return { m, s, e: Math.min(1440, s + (m.minutes || 60)) };
    });
    const evs = layDay(list).map((e) => {
      const idx = shown.push(e.m) - 1;
      const past = meetEnd(e.m) < now;
      return `<button class="cal-ev ${past ? 'past' : ''} ${e.e - e.s < 45 ? 'short' : ''}" data-ev="${idx}" style="top:${e.s / 60 * CAL_HOUR}px;height:${Math.max(22, (e.e - e.s) / 60 * CAL_HOUR - 2)}px;left:calc(${e.lane / e.lanes * 100}% + 2px);width:calc(${100 / e.lanes}% - 4px)">
        <b>${e.m.recur ? '↻ ' : ''}${esc(e.m.title || '—')}</b><span>${esc(hm(e.m.start))}</span></button>`;
    }).join('');
    return `<div class="cal-col ${i === CAL.day ? 'on' : ''}" data-col="${i}" style="height:${24 * CAL_HOUR}px">${evs}${today ? `<i class="cal-now" style="top:${nowTop}px"></i>` : ''}</div>`;
  }).join('');
  const keep = scroll.scrollTop;
  scroll.innerHTML = `<div class="cal-grid"><div class="cal-hours">${hours}</div>${cols}</div>`;
  scroll.scrollTop = keep;
  if (!CAL.scrolled) { scroll.scrollTop = Math.max(0, (Math.min(now.getHours(), 18) - 1)) * CAL_HOUR; if (now.getHours() < 9) scroll.scrollTop = 7 * CAL_HOUR; CAL.scrolled = true; }
  scroll.querySelectorAll('[data-ev]').forEach((b) => { b.onclick = (e) => { e.stopPropagation(); meetingView(shown[+b.dataset.ev]); }; });
  scroll.querySelectorAll('[data-col]').forEach((c) => {
    c.onclick = (e) => {   // свободное время → новая встреча на это время (с шагом 30 минут)
      const min = Math.floor((e.clientY - c.getBoundingClientRect().top) / CAL_HOUR * 2) * 30;
      const when = new Date(days[+c.dataset.col]); when.setHours(0, min, 0, 0);
      if (when > now) meetingDialog({ when });
    };
  });
}
function meetingView(m) {
  const st = new Date(m.start), end = meetEnd(m);
  const live = Date.now() >= st.getTime() - 5 * 60000 && Date.now() <= end.getTime();
  const guests = (m.emails || []).filter((e) => e.toLowerCase() !== (m.organizer_email || '').toLowerCase());
  modal(`<h3>${esc(m.title || '—')}</h3>
    <p class="meet-when">${esc(st.toLocaleDateString(LANG, { weekday: 'long', day: 'numeric', month: 'long' }))}, ${esc(hm(st))} – ${esc(hm(end))}${live ? ` <span class="live">● ${esc(t('now'))}</span>` : ''}</p>
    ${m.recur ? `<p class="sub rep">${esc(recurLabel(m))}</p>` : ''}${m.admin_view ? `<p class="sub warn">${esc(t('admin_note'))}</p>` : ''}
    <dl class="kv"><dt>${esc(t('organizer'))}</dt><dd>${esc(m.organizer || m.organizer_email || '—')}</dd>
    ${guests.length ? `<dt>${esc(t('participants'))}</dt><dd>${guests.map(esc).join(', ')}</dd>` : ''}${m.note ? `<dt>${esc(t('note'))}</dt><dd>${esc(m.note)}</dd>` : ''}</dl>
    <div class="acts view-acts"><button class="btn ghost" data-a="no">${esc(t('close'))}</button>
    ${m.can_edit ? `<button class="btn ghost" data-a="edit">${esc(t('edit_meeting'))}</button>` : ''}
    <button class="btn ghost" data-a="copy">🔗 ${esc(t('share_link'))}</button>
    <button class="btn" data-a="join" autofocus>${esc(t('join'))}</button></div>`, (c, close) => {
    c.querySelector('[data-a=no]').onclick = close;
    c.querySelector('[data-a=copy]').onclick = () => copyLink(m.link);
    c.querySelector('[data-a=join]').onclick = () => { close(); openCall(m.link, m.title); };
    const e = c.querySelector('[data-a=edit]');
    if (e) e.onclick = () => { close(); meetingDialog({ edit: m }); };
  });
}
function meetingDialog({ withAuthor = '', withName = '', when = null, edit = null }) {
  let d = when;
  if (edit) d = new Date(edit.start);
  if (!d) { d = new Date(Date.now() + 60 * 60000); d.setMinutes(d.getMinutes() < 30 ? 30 : 60, 0, 0); }
  const pad = (n) => String(n).padStart(2, '0');
  const dv = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, tv = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const head = edit ? t('edit_meeting') : withName ? t('meet_with', withName) : t('new_meeting');
  const series = !!(edit && edit.recur);
  const guests0 = edit ? (edit.emails || []).filter((e) => e.toLowerCase() !== (edit.organizer_email || '').toLowerCase()).join(', ') : '';
  modal(`<h3>${esc(head)}</h3>${edit && edit.admin_view ? `<p class="sub warn">${esc(t('admin_note'))}</p>` : ''}
    <form id="mF" novalidate>${series ? `<label>${esc(t('r_scope'))}<select id="mS"><option value="one">${esc(t('r_one'))}</option><option value="all">${esc(t('r_all'))}</option></select></label>` : ''}
    <label>${esc(t('title'))}<input id="mT" maxlength="120" autofocus value="${esc(edit ? edit.title : withName ? t('meet_with', withName) : '')}"></label>
    <div class="grid2"><label>${esc(t('date'))}<input id="mD" type="date" value="${dv}"></label><label>${esc(t('time'))}<input id="mH" type="time" value="${tv}" step="300"></label></div>
    <label>${esc(t('minutes'))}<input id="mM" type="number" min="15" max="480" step="15" value="${edit ? edit.minutes : 60}"></label>
    <div class="grid2" id="mRec"><label>${esc(t('r_repeat'))}<select id="mR"><option value="">${esc(t('r_none'))}</option>${RECURS.map((r) => `<option value="${r}" ${edit && edit.recur === r ? 'selected' : ''}>${esc(t('r_' + r))}</option>`).join('')}</select></label>
    <label id="mUL">${esc(t('r_until'))}<input id="mU" type="date" value="${edit && edit.until ? esc(edit.until) : ''}"></label></div>
    ${withAuthor ? '' : `<label>${esc(t('guests'))}<input id="mG" type="text" inputmode="email" placeholder="anna@company.com, ivan@company.com" value="${esc(guests0)}"></label>`}
    <label>${esc(t('note'))}<textarea id="mN" rows="2" maxlength="500">${esc(edit ? edit.note || '' : '')}</textarea></label>
    <p class="err" id="mErr"></p><div class="acts">${edit ? `<button type="button" class="btn danger" data-a="drop">${esc(t('cancel_meeting'))}</button>` : ''}
    <button type="button" class="btn ghost" data-a="no">${esc(t('cancel'))}</button><button class="btn" id="mGo">${esc(edit ? t('save') : t('create'))}</button></div></form>`, (c, close) => {
    c.querySelector('[data-a=no]').onclick = close;
    const q = (s) => c.querySelector(s);
    const oneOnly = () => series && q('#mS').value === 'one';
    const sync = () => { q('#mRec').hidden = oneOnly(); q('#mUL').style.visibility = q('#mR').value ? '' : 'hidden'; };
    q('#mR').onchange = sync;
    if (series) q('#mS').onchange = sync;
    sync();
    const scopeBody = () => (series ? { occurrence: edit.start, scope: q('#mS').value } : {});
    const fail = (er) => { const m = er.code || ''; return m === 'bad until' ? t('r_bad_until') : m && m !== 'meet api' ? m : errText(er); };
    const drop = q('[data-a=drop]');
    if (drop) drop.onclick = async () => {
      const body = { uid: edit.uid, cancel: true, ...scopeBody() };
      const msg = !series ? t('confirm_cancel') : oneOnly() ? t('r_confirm_one') : t('r_confirm_all');
      close();
      if (!(await confirmBox(msg, t('cancel_meeting'), true))) return;
      try { await api('prodit_meet_edit', { body }); toast(t('cancelled')); renderMeetings(); } catch (er) { toast(fail(er)); }
    };
    q('#mF').onsubmit = async (e) => {
      e.preventDefault();
      const title = q('#mT').value.trim();
      const emails = withAuthor ? [] : q('#mG').value.split(/[\s,;]+/).map((s) => s.trim().toLowerCase()).filter((s) => s.includes('@'));
      const err = q('#mErr');
      if (!title || (!withAuthor && !edit && !emails.length)) { err.textContent = t('need_title'); return; }
      const w = new Date(`${q('#mD').value}T${q('#mH').value || '10:00'}:00`);
      if (isNaN(w)) { err.textContent = t('date'); return; }
      const off = -w.getTimezoneOffset(), sign = off >= 0 ? '+' : '-', a = Math.abs(off);
      const start = `${q('#mD').value}T${q('#mH').value}:00${sign}${pad(Math.floor(a / 60))}:${pad(a % 60)}`;
      const body = { title, start, tz: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Moscow',
        duration: Math.max(15, parseInt(q('#mM').value, 10) || 60), emails, note: q('#mN').value.trim(), lang: LANG, ...scopeBody(),
        ...(oneOnly() ? {} : { recur: q('#mR').value, until: q('#mR').value ? q('#mU').value : '' }) };
      q('#mGo').disabled = true;
      try {
        if (edit) await api('prodit_meet_edit', { body: { ...body, uid: edit.uid } });
        else await api('prodit_meet_create', { body: { ...body, ...(withAuthor ? { with: withAuthor } : {}) } });
        close(); toast(t(edit ? 'saved' : 'created'));
        if (location.hash.startsWith('#/meet')) renderMeetings();
      } catch (er) { q('#mGo').disabled = false; err.textContent = fail(er); }
    };
  });
}

// ---------- профиль ----------
function renderProfile() {
  const canInstall = !!S.installPrompt;
  const nperm = 'Notification' in window ? Notification.permission : 'unsupported';
  $('main').innerHTML = `<div class="head">${backBtn('#/')}<h2>${esc(t('profile'))}</h2></div><div class="scroll">
    <div class="card"><div class="row" style="gap:14px;margin-bottom:16px">${ava(S.name, { id: S.author })}<div><div style="color:var(--ink);font-weight:600;font-size:17px">${esc(S.name)}</div><div class="hint" style="margin:0">${esc(S.email)}</div></div></div>
      <form id="pF"><label>${esc(t('name'))}<input id="pN" maxlength="40" value="${esc(S.name)}"></label><button class="btn sm">${esc(t('save'))}</button></form></div>
    <div class="card"><dl class="kv"><dt>Email</dt><dd>${esc(S.email)}</dd><dt>${esc(t('domain'))}</dt><dd>${esc(S.domain ? '@' + S.domain : '—')}</dd>
      <dt>${esc(t('language'))}</dt><dd><span class="lang" data-lang-switch></span></dd></dl></div>
    <div class="card"><h3>${esc(t('notifications'))}</h3>
      ${nperm === 'granted' ? `<p class="hint" style="margin:0">✓ ${esc(t('notif_ok'))}</p>` : nperm === 'denied' ? `<p class="hint" style="margin:0">${esc(t('notif_denied'))}</p>`
      : nperm === 'unsupported' ? '<p class="hint" style="margin:0">—</p>' : `<button class="btn sm ghost" id="pNotif">${esc(t('notif_on'))}</button>`}
      ${canInstall ? `<div style="margin-top:12px"><button class="btn sm" id="pInstall">⤓ ${esc(t('install'))}</button></div>` : ''}</div>
    <div class="card"><button class="btn danger" id="pOut">${esc(t('sign_out'))}</button><span class="ver" style="margin-left:12px">Production IT web v${VERSION}</span></div></div>`;
  applyLang();
  $('pF').onsubmit = async (e) => { e.preventDefault(); const n = $('pN').value.trim(); if (!n) { toast(t('need_name')); return; } await saveName(n); renderProfile(); };
  $('pOut').onclick = async () => { if (await confirmBox(t('sign_out_q', S.email), t('sign_out'), true)) logout(); };
  const pn = $('pNotif'); if (pn) pn.onclick = () => Notification.requestPermission().then(() => renderProfile());
  const pi = $('pInstall'); if (pi) pi.onclick = async () => { S.installPrompt.prompt(); await S.installPrompt.userChoice.catch(() => {}); S.installPrompt = null; renderProfile(); };
}

// ---------- мгновенная доставка: Centrifugo (JSON-протокол без библиотеки); нет — опрос ----------
async function rtStart() {
  rtStop();
  let o;
  try { o = await api('prodit_rt_token'); } catch { return; }
  if (!o.url || !o.token || !window.WebSocket) return;
  let ws, id = 0, stopped = false, retry = 0;
  const connect = (token) => {
    try { ws = new WebSocket(o.url); } catch { return; }
    ws.onopen = () => {
      ws.send(JSON.stringify({ id: ++id, connect: { token, name: 'prodit-web' } }) + '\n' + JSON.stringify({ id: ++id, subscribe: { channel: o.channel } }));
    };
    ws.onmessage = (ev) => {
      for (const line of String(ev.data).split('\n')) {
        if (!line.trim()) continue;
        let msg; try { msg = JSON.parse(line); } catch { continue; }
        if (!Object.keys(msg).length) { ws.send('{}'); continue; }   // ping сервера — отвечаем pong
        if (msg.connect) { S.rtOk = true; retry = 0; }
        if (msg.push) tick(true);
        if (msg.error && msg.id) { S.rtOk = false; }
      }
    };
    ws.onclose = () => {
      S.rtOk = false;
      if (stopped) return;
      retry = Math.min(retry + 1, 6);
      setTimeout(async () => { if (stopped) return; try { const n = await api('prodit_rt_token'); connect(n.token); } catch { connect(token); } }, 1000 * 2 ** retry);
    };
  };
  connect(o.token);
  S.rt = { stop() { stopped = true; try { ws && ws.close(); } catch {} } };
}
function rtStop() { if (S.rt) { S.rt.stop(); S.rt = null; } S.rtOk = false; }

// ---------- опрос ----------
// Комнаты (общий чат, компания, бэклог) Centrifugo не присылает — их опрашиваем всегда, пока открыты.
// Открытую личку — тоже (удаления не приходят событием). Список диалогов: без Centrifugo — каждые 5 с, с ним — раз в 20 с и сразу по событию.
let ticks = 0;
async function tick(now = false) {
  if (!S.uid) return;
  ticks++;
  const slow = S.rtOk && !now && ticks % Math.round(POLL_RT_MS / POLL_MS) !== 0;
  const v = S.view;
  if (!slow) loadThreads().catch(() => {});   // в фоне тоже: входящие звонки и счётчик
  if (document.hidden && !now) return;
  if (v && (v.kind === 'room' || v.kind === 'dm')) loadChat(v).catch(() => {});   // открытая переписка — всегда: удаления Centrifugo не присылает
}
function schedule() { clearInterval(S.timer); S.timer = setInterval(tick, POLL_MS); }

async function loadConfig() {
  try {
    const c = await api('prodit_config');
    S.cfg = c; store.set('cfg', JSON.stringify(c));
  } catch { /* остаются прежние */ }
}

function startApp() {
  $('boot').hidden = true; $('login').hidden = true; $('app').hidden = false;
  applyLang();
  renderNav();
  route();
  loadConfig();
  loadThreads().catch(() => {});
  schedule();
  rtStart();
}

// ---------- запуск ----------
window.addEventListener('hashchange', route);
let wasWide = wide();
window.addEventListener('resize', () => { if (wide() !== wasWide) { wasWide = wide(); if (S.uid) route(); } });
document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(true); });
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); S.installPrompt = e; if (location.hash === '#/profile') renderProfile(); });
$('callEnd').onclick = closeCall;
if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
initLogin();
applyLang();
if (S.uid) startApp(); else showLogin();
window.__prodit = { S, api, version: VERSION };   // для автотеста и отладки
