// Автотест веб-клиента Production IT в headless Chromium против локального стенда (serve.py).
// Запуск: python3 tests/prodit_web/serve.py &   затем
//   PUPPETEER=<путь к node_modules/puppeteer-core> node tests/prodit_web/e2e.cjs [http://127.0.0.1:18150] [папка скриншотов]
// Два пользователя (Анна — широкий экран, Борис — телефон), вход через подставную почту, всё — локально.
const path = require('path');
const os = require('os');
const fs = require('fs');
const puppeteer = require(process.env.PUPPETEER || 'puppeteer-core');

const BASE = process.argv[2] || 'http://127.0.0.1:18150';
const APP = BASE + '/production-it/app/';
const SHOTS = process.argv[3] || path.join(os.homedir(), 'prodit-web-shots');
const CHROME = process.env.CHROME || '/snap/bin/chromium';
fs.mkdirSync(SHOTS, { recursive: true });

let fails = 0;
const check = (name, cond) => { console.log((cond ? '  ok  ' : 'FAIL  ') + name); if (!cond) fails++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function shot(page, name) { await sleep(350); await page.screenshot({ path: path.join(SHOTS, name + '.png') }); }
async function waitFor(fn, ms = 15000, step = 250) { const t0 = Date.now(); while (Date.now() - t0 < ms) { try { if (await fn()) return true; } catch {} await sleep(step); } return false; }
const text = (page, sel) => page.$eval(sel, (e) => e.innerText).catch(() => '');

async function login(page, email, name, shotName) {
  await page.goto(APP, { waitUntil: 'networkidle0' });
  await page.waitForSelector('#lEmail', { visible: true });
  if (shotName) await shot(page, shotName + '-login');
  await page.$eval('#lEmail', (e) => { e.value = ''; });
  await page.type('#lEmail', email);
  await page.$eval('#lName', (e) => { e.value = ''; });
  await page.type('#lName', name);
  await page.click('#lGet');
  await page.waitForSelector('#lCode', { visible: true });
  if (shotName) await shot(page, shotName + '-code');
  let code = '';
  await waitFor(async () => { const r = await fetch(BASE + '/__test/code?email=' + encodeURIComponent(email)); code = (await r.json()).code; return !!code; });
  await page.type('#lCode', code);   // 6 цифр — вход сам
  await page.waitForSelector('#app:not([hidden])', { timeout: 10000 });
  return page.evaluate(() => ({ uid: localStorage.getItem('pitapp.uid'), author: localStorage.getItem('pitapp.author'), name: localStorage.getItem('pitapp.name') }));
}

async function send(page, msg) {
  await page.waitForSelector('#txt', { visible: true });
  await page.type('#txt', msg);
  await page.keyboard.press('Enter');
  return waitFor(async () => (await text(page, '#msgs')).includes(msg), 8000);
}

(async () => {
  const profile = fs.mkdtempSync(path.join(os.homedir(), 'prodit-e2e-'));   // snap-chromium видит только домашнюю папку
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, userDataDir: profile,
    args: ['--no-first-run', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--lang=ru'] });
  const errors = [];
  try {
    const ctxA = await browser.createBrowserContext();
    const ctxB = await browser.createBrowserContext();
    const A = await ctxA.newPage();
    const B = await ctxB.newPage();
    await A.setViewport({ width: 1440, height: 900 });
    await B.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
    for (const [p, n] of [[A, 'A'], [B, 'B']]) {
      p.on('pageerror', (e) => errors.push(n + ': ' + e.message));
      p.on('response', (r) => { if (r.status() >= 400 && !r.url().includes('/video_api.php')) errors.push(n + ' HTTP ' + r.status() + ' ' + r.url()); });
      p.on('console', (m) => { if (m.type() === 'error' && !/efir\.invalid|ERR_NAME_NOT_RESOLVED|Failed to load resource/.test(m.text())) errors.push(n + ' console: ' + m.text()); });
    }

    console.log('вход');
    const a = await login(A, 'anna@acme.io', 'Анна', 'desktop-01');
    check('Анна вошла (uid p…)', a.uid && a.uid.startsWith('p') && a.name === 'Анна');
    const b = await login(B, 'boris@acme.io', 'Борис', 'phone-01');
    check('Борис вошёл с телефона', b.uid && b.uid.startsWith('p') && b.author !== a.author);
    await shot(A, 'desktop-02-chats-empty');
    await shot(B, 'phone-02-home');
    check('телефон: главный экран — карточки разделов', await B.$eval('#app', (e) => e.dataset.depth) === '0' && (await text(B, '#nav')).includes('Общий чат'));
    check('широкий экран: сразу колонки «Чаты»', A.url().endsWith('#/chats') && await A.$eval('#list', (e) => getComputedStyle(e).display) !== 'none');

    console.log('общий чат');
    await A.goto(APP + '#/general');
    check('Анна пишет в общий чат', await send(A, 'Всем привет из веба!'));
    await B.goto(APP + '#/general');
    check('Борис видит сообщение в общем чате', await waitFor(async () => (await text(B, '#msgs')).includes('Всем привет из веба!')));
    await sleep(3100);   // сервер: не чаще раза в 3 с
    check('Борис отвечает в общий чат', await send(B, 'Привет, Анна 👋'));
    check('у Анны появился ответ (опрос/Centrifugo)', await waitFor(async () => (await text(A, '#msgs')).includes('Привет, Анна'), 12000));
    await shot(A, 'desktop-03-general');
    await shot(B, 'phone-03-general');

    console.log('Написать по email → личка');
    await A.goto(APP + '#/chats');
    await A.click('[data-find]');
    await A.waitForSelector('#fE', { visible: true });
    await A.type('#fE', 'boris@acme.io');
    await shot(A, 'desktop-04-find');
    await A.click('#fGo');
    check('найден по email → открылся диалог', await waitFor(async () => A.url().includes('#/dm/' + b.author)));
    check('Анна пишет Борису', await send(A, 'Борис, посмотри макет?'));
    await B.goto(APP + '#/chats');
    check('у Бориса диалог с непрочитанным', await waitFor(async () => (await text(B, '#threads')).includes('Анна') && !!(await B.$('#threads .badge'))));
    await shot(B, 'phone-04-threads');
    await B.click('#threads a.item');
    check('Борис видит сообщение в личке', await waitFor(async () => (await text(B, '#msgs')).includes('посмотри макет')));
    check('Борис отвечает в личку', await send(B, 'Да, через 5 минут'));
    const t0 = Date.now();
    check('Анна получает ответ в личке', await waitFor(async () => (await text(A, '#msgs')).includes('через 5 минут'), 12000, 100));
    const rt = await A.evaluate(() => window.__prodit.S.rtOk);
    console.log(`      (доставка за ${Date.now() - t0} мс, Centrifugo ${rt ? 'подключён' : 'нет — опрос'})`);
    check('Centrifugo подключён в браузере', rt === true);
    await shot(A, 'desktop-05-dm');
    await shot(B, 'phone-05-dm');

    console.log('удаление своего сообщения');
    await sleep(1100);
    await send(A, 'это сообщение удалю');
    const del = await A.$$('#msgs .msg.mine .del');
    await A.evaluate(() => { const d = [...document.querySelectorAll('#msgs .msg.mine .del')].pop(); d.click(); });
    await A.waitForSelector('#modal:not([hidden]) [data-a=yes]');
    await A.click('#modal [data-a=yes]');
    check('своё сообщение удалено', del.length > 0 && await waitFor(async () => !(await text(A, '#msgs')).includes('это сообщение удалю')));
    check('у Бориса тоже исчезло', await waitFor(async () => { const s = await text(B, '#msgs'); return s.includes('посмотри макет') && !s.includes('это сообщение удалю'); }, 12000));

    console.log('команда и чат компании');
    await A.goto(APP + '#/team');
    check('в команде — Борис', await waitFor(async () => (await text(A, '#list')).includes('Борис') && (await text(A, '#list')).includes('boris@acme.io')));
    await A.click('a[href="#/team/co"]');
    check('чат компании открыт', await waitFor(async () => (await text(A, '#main .head')).includes('Чат компании')));
    await sleep(3100);
    check('Анна пишет в чат компании', await send(A, 'Коллеги, релиз в пятницу'));
    await shot(A, 'desktop-06-team');
    await B.goto(APP + '#/team');
    await B.waitForSelector('a[href="#/team/co"]');
    await shot(B, 'phone-06-team');
    await B.click('a[href="#/team/co"]');
    check('Борис видит сообщение в чате компании', await waitFor(async () => (await text(B, '#msgs')).includes('релиз в пятницу')));
    const leaked = await A.evaluate(async () => (await window.__prodit.api('chat')).items.some((m) => m.text.includes('релиз в пятницу')));
    check('в общий чат сообщение компании не попало', !leaked);

    console.log('бэклог');
    await A.goto(APP + '#/backlog');
    await sleep(3100);
    check('пост в бэклог', await send(A, '2.0: веб-версия и Windows'));
    await shot(A, 'desktop-07-backlog');
    await B.goto(APP + '#/backlog');
    check('Борис видит бэклог', await waitFor(async () => (await text(B, '#msgs')).includes('веб-версия и Windows')));
    await shot(B, 'phone-07-backlog');

    console.log('встречи');
    await A.goto(APP + '#/meet');
    check('список встреч (мои)', await waitFor(async () => { const s = await text(A, '#list'); return s.includes('Планёрка команды') && s.includes('Демо заказчику') && !s.includes('Ретро спринта'); }));
    check('идущая встреча отмечена', (await text(A, '#list')).toLowerCase().includes('идёт'));
    check('обычный пользователь не видит чужую встречу', !(await text(A, '#list')).includes('Ретро спринта'));
    await shot(A, 'desktop-08-meetings');

    console.log('календарь в приложении');
    check('календарь — свой, без встроенных страниц', (await A.$$('#main iframe')).length === 0 && !!(await A.$('#main .cal-grid')));
    check('на широком экране — неделя', (await A.$$eval('.cal-col', (c) => c.filter((x) => getComputedStyle(x).display !== 'none').length)) === 7);
    check('встречи на сетке', await waitFor(async () => (await text(A, '#calScroll')).includes('Демо заказчику')));
    await shot(A, 'desktop-09-calendar');
    const range0 = await text(A, '#calRange');
    await A.click('#calNext');
    check('листает неделю вперёд', (await text(A, '#calRange')) !== range0);
    await A.click('#calToday');
    check('«Сегодня» возвращает', (await text(A, '#calRange')) === range0);

    console.log('регулярная встреча');
    await A.click('#mNew');
    await A.waitForSelector('#mT', { visible: true });
    await A.type('#mT', 'Еженедельный синк');
    await A.type('#mG', 'boris@acme.io');
    await A.select('#mR', 'weekly');
    await shot(A, 'desktop-09a-new-weekly');
    await A.click('#mGo');
    check('серия создана: много дат с одним uid', await waitFor(async () => {
      const ms = (await (await fetch(BASE + '/__test/meetings')).json()).filter((m) => m.title === 'Еженедельный синк');
      return ms.length > 20 && new Set(ms.map((m) => m.uid)).size === 1 && ms.every((m) => m.recur === 'weekly');
    }));
    const seriesRows = () => A.$$eval('#list .meet .nm', (e) => e.filter((x) => x.innerText.includes('Еженедельный синк')).length);
    check('в списке одна строка с пометкой повторения', await waitFor(async () => (await seriesRows()) === 1 && (await text(A, '#list')).includes('Каждую неделю')));
    const seriesTitle = () => A.$$eval('.cal-ev', (e) => e.filter((x) => x.innerText.includes('Еженедельный синк')).length);
    check('на этой неделе одна дата серии', await waitFor(async () => (await seriesTitle()) === 1));
    await A.click('#calNext');
    check('и на следующей', (await seriesTitle()) === 1);
    await shot(A, 'desktop-09b-calendar-series');
    // отмена одной даты: остальные на месте
    await A.evaluate(() => [...document.querySelectorAll('.cal-ev')].find((x) => x.innerText.includes('Еженедельный синк')).click());
    await A.waitForSelector('[data-a=edit]', { visible: true });
    await shot(A, 'desktop-09c-meeting-card');
    await A.click('[data-a=edit]');
    await A.waitForSelector('#mS', { visible: true });
    check('форма спрашивает, что менять; для одной даты повтор скрыт', await A.$eval('#mRec', (e) => e.hidden));
    await A.click('[data-a=drop]');
    await A.waitForSelector('[data-a=yes]', { visible: true });
    await A.click('[data-a=yes]');
    check('дата исчезла из календаря', await waitFor(async () => (await seriesTitle()) === 0));
    await A.click('#calNext');
    check('следующая дата осталась', await waitFor(async () => (await seriesTitle()) === 1));
    // правка всей серии: новая тема у всех дат
    await A.evaluate(() => [...document.querySelectorAll('.cal-ev')].find((x) => x.innerText.includes('Еженедельный синк')).click());
    await A.waitForSelector('[data-a=edit]', { visible: true });
    await A.click('[data-a=edit]');
    await A.waitForSelector('#mS', { visible: true });
    await A.select('#mS', 'all');
    check('для всей серии повтор виден', !(await A.$eval('#mRec', (e) => e.hidden)));
    await A.$eval('#mT', (e) => { e.value = ''; });
    await A.type('#mT', 'Синк всей команды');
    await A.click('#mGo');
    check('тема сменилась у всех дат', await waitFor(async () => {
      const ms = await (await fetch(BASE + '/__test/meetings')).json();
      return ms.filter((m) => m.title === 'Синк всей команды').length > 20 && !ms.some((m) => m.title === 'Еженедельный синк');
    }));
    // клик по свободному времени — новая встреча на это время
    await A.goto(APP + '#/meet');
    await A.waitForSelector('.cal-col');
    await A.click('#calNext');
    await A.evaluate(() => { const c = document.querySelector('.cal-col[data-col="4"]'), r = c.getBoundingClientRect(); c.dispatchEvent(new MouseEvent('click', { bubbles: true, clientY: r.top + 15 * 48 + 5 })); });
    await A.waitForSelector('#mH', { visible: true });
    check('время из клика по сетке', (await A.$eval('#mH', (e) => e.value)) === '15:00');
    await A.click('[data-a=no]');
    // телефон: календарь на весь экран, один день
    await B.goto(APP + '#/meet/cal');
    await B.waitForSelector('.cal-grid');
    check('на телефоне — один день и вкладки недели', await waitFor(async () => (await B.$$eval('.cal-col', (c) => c.filter((x) => getComputedStyle(x).display !== 'none').length)) === 1
      && (await B.$$('.cal-day')).length === 7));
    await shot(B, 'phone-08a-calendar');
    await B.goto(APP + '#/meet');
    await waitFor(async () => (await text(B, '#list')).includes('Планёрка'));
    await shot(B, 'phone-08-meetings');
    // кнопка «Войти» — комната efir во встроенном окне
    await A.goto(APP + '#/meet');
    await A.waitForSelector('#list [data-join]');
    await A.click('#list [data-join]');
    const src = await A.$eval('#callFrame', (f) => f.src);
    check('«Войти» открывает комнату с autojoin и именем', !(await A.$eval('#call', (e) => e.hidden)) && src.includes('/rooms/productionit-') && src.includes('autojoin=1') && src.includes('name=%D0%90%D0%BD%D0%BD%D0%B0'));
    check('iframe с доступом к камере/микрофону', (await A.$eval('#callFrame', (f) => f.getAttribute('allow'))).includes('camera; microphone'));
    await A.click('#callEnd');

    console.log('встреча из диалога');
    await A.goto(APP + '#/dm/' + b.author);
    await A.waitForSelector('#chPlan');
    await A.click('#chPlan');
    await A.waitForSelector('#mT', { visible: true });
    await shot(A, 'desktop-10-new-meeting');
    await A.click('#mGo');
    check('встреча создана с Борисом в гостях', await waitFor(async () => {
      const ms = await (await fetch(BASE + '/__test/meetings')).json();
      return ms.some((m) => m.emails.includes('boris@acme.io') && m.organizer_email === 'anna@acme.io' && /[+-]\d\d:\d\d$/.test(m.start));
    }));

    console.log('приглашение незарегистрированного');
    await A.goto(APP + '#/chats');
    await A.click('[data-find]');
    await A.waitForSelector('#fE', { visible: true });
    await A.type('#fE', 'newbie@acme.io');
    await A.click('#fGo');
    await A.waitForSelector('#modal:not([hidden]) [data-a=yes]');
    check('предложение пригласить', (await text(A, '#modalCard')).includes('newbie@acme.io'));
    await shot(A, 'desktop-11-invite');
    await A.click('#modal [data-a=yes]');
    check('письмо-приглашение ушло (подставная почта)', await waitFor(async () => (await (await fetch(BASE + '/__test/mails')).json()).some((m) => m.to === 'newbie@acme.io')));

    console.log('звонок 1:1');
    await B.goto(APP + '#/chats');
    await A.goto(APP + '#/dm/' + b.author);
    await A.waitForSelector('#chCall');
    await A.click('#chCall');
    check('у Анны открылось окно звонка', await waitFor(async () => !(await A.$eval('#call', (e) => e.hidden))));
    const callSrc = await A.$eval('#callFrame', (f) => f.src);
    check('комната productionit-<14 символов>', /\/rooms\/productionit-[a-z2-9]{14}\?autojoin=1/.test(callSrc));
    await shot(A, 'desktop-12-call');
    check('у Бориса — входящий звонок', await waitFor(async () => (await text(B, '#modalCard')).includes('Входящий звонок'), 15000));
    await shot(B, 'phone-09-incoming');
    await B.click('#modal [data-a=no]');
    await A.click('#callEnd');
    check('Анна видит «Звонок отклонён»', await waitFor(async () => (await text(A, '#msgs')).includes('Звонок отклонён'), 12000));
    await shot(A, 'desktop-13-declined');

    console.log('профиль и язык');
    await B.goto(APP + '#/profile');
    await B.waitForSelector('#pOut');
    await shot(B, 'phone-10-profile');
    await A.goto(APP + '#/chats');
    await A.click('#rail [data-lang-switch] [data-l=en]');
    check('переключение на English', await waitFor(async () => (await text(A, '#nav')).includes('Team chat')));
    await shot(A, 'desktop-14-english');
    await A.click('#rail [data-lang-switch] [data-l=ru]');
    await B.goto(APP + '#/dm/' + a.author);
    await waitFor(async () => (await text(B, '#msgs')).includes('макет'));
    await shot(B, 'phone-11-dm-call-history');

    console.log('выход');
    await B.goto(APP + '#/profile');
    await B.click('#pOut');
    await B.waitForSelector('#modal:not([hidden]) [data-a=yes]');
    await B.click('#modal [data-a=yes]');
    check('выход → экран входа', await waitFor(async () => !(await B.$eval('#login', (e) => e.hidden))));
    check('uid стёрт', (await B.evaluate(() => localStorage.getItem('pitapp.uid'))) === null);

    console.log('PWA');
    const man = await A.evaluate(async () => (await (await fetch('manifest.webmanifest')).json()));
    check('манифест: имя, standalone, иконки 192/512', man.name === 'Production IT' && man.display === 'standalone' && man.icons.some((i) => i.sizes === '512x512'));
    check('ошибок JS нет', errors.length === 0);
    if (errors.length) console.log(errors.slice(0, 10).join('\n'));
  } catch (e) {
    console.log('FAIL  исключение:', e && e.stack || e); fails++;
  } finally {
    await browser.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
  console.log('скриншоты:', SHOTS);
  console.log('ошибок:', fails);
  process.exit(fails ? 1 : 0);
})();
