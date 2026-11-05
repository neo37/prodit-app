#!/usr/bin/env python3
"""Автотест регулярных встреч в meet/meet_api.py — без сети и без Docker: своя временная база, письма перехватываются.
Запуск: python3 tests/meet_api_test.py"""
import json, os, sys, tempfile
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

os.environ.update(MEET_DATA=tempfile.mkdtemp(prefix='meet-test-'), MEET_KEY='mk', SMTP_USER='edu@test.local', SMTP_HOST='')
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'meet'))
import meet_api as M

fails = 0
def check(name, cond):
    global fails
    print(('  ok  ' if cond else 'FAIL  ') + name)
    fails += 0 if cond else 1

mails = []
M.mail = lambda to, subject, text, html, ical, cancel=False: (mails.append(dict(to=to, subject=subject, text=text, html=html, ical=ical, cancel=cancel)), 'primary')[1]
UTC = timezone.utc
BG = ZoneInfo('Europe/Belgrade')
NOW = datetime.now(UTC)

def next_weekday(wd, hour=10, tz=BG, weeks=0):
    d = NOW.astimezone(tz) + timedelta(days=1 + weeks * 7)
    while d.weekday() != wd:
        d += timedelta(days=1)
    return d.replace(hour=hour, minute=0, second=0, microsecond=0)

def new(title='Планёрка', start=None, **kw):
    code, r = M.create(dict(key='mk', title=title, start=(start or next_weekday(0)).isoformat(), tz='Europe/Belgrade', duration=30,
                            organizer='Анна', organizer_email='anna@acme.io', emails=['boris@acme.io'], lang='ru', **kw))
    assert code == 200, r
    return r['uid']

def items(uid):
    return [i for i in M.upcoming('mk')[1]['items'] if i['uid'] == uid]

def edit(uid, **kw):
    return M.edit(dict(key='mk', uid=uid, **kw))

def local(i):
    return datetime.fromisoformat(i['start']).astimezone(BG)

print('разворачивание серий')
first = next_weekday(0)
uid = new(recur='weekly')
got = items(uid)
check('еженедельная: одна дата в неделю, ~27 дат за полгода', 25 <= len(got) <= 28)
check('все в понедельник и в 10:00 по местному, в том числе после перевода часов',
      all(local(i).weekday() == 0 and local(i).hour == 10 for i in got))
check('дата, день за днём, идёт через 7 суток', (local(got[1]).date() - local(got[0]).date()).days == 7)
check('серия одна в базе', M.db().execute('SELECT COUNT(*) FROM meetings WHERE uid = ?', (uid,)).fetchone()[0] == 1)
check('в списке есть recur', got[0]['recur'] == 'weekly' and got[0]['until'] == '')

uid2 = new(recur='weekdays', start=next_weekday(5))
check('по будням: начало в субботу уходит на понедельник, выходных нет', all(local(i).weekday() < 5 for i in items(uid2)) and len(items(uid2)) > 100)
uid3 = new(recur='biweekly')
check('раз в две недели', (local(items(uid3)[1]).date() - local(items(uid3)[0]).date()).days == 14)
uid4 = new(recur='daily', until=(first + timedelta(days=4)).date().isoformat())
check('до даты включительно', len(items(uid4)) == 5)
d31 = datetime(2027, 1, 31, 10, tzinfo=BG)
m31 = dict(start=d31.astimezone(UTC).isoformat(), recur='monthly', recur_until='', exdates='[]', tz='Europe/Belgrade')
days = [x.astimezone(BG).date().isoformat() for x in M.occurrences(m31, d31.astimezone(UTC), datetime(2027, 6, 1, tzinfo=UTC))]
check('ежемесячно 31-го: месяцы без такого числа пропускаются', days == ['2027-01-31', '2027-03-31', '2027-05-31'])
dst = datetime(2027, 3, 22, 10, tzinfo=BG)
m_dst = dict(start=dst.astimezone(UTC).isoformat(), recur='weekly', recur_until='', exdates='[]', tz='Europe/Belgrade')
hrs = [x.hour for x in M.occurrences(m_dst, dst.astimezone(UTC), datetime(2027, 4, 10, tzinfo=UTC))]
check('перевод часов: в UTC сдвиг, по местному те же 10:00', hrs[0] == 9 and hrs[-1] == 8)
check('неверное правило — 400', M.create(dict(key='mk', title='x', start=first.isoformat(), emails=['b@acme.io'], recur='hourly'))[0] == 400)
check('конец раньше начала — 400', M.create(dict(key='mk', title='x', start=first.isoformat(), emails=['b@acme.io'], recur='daily', until='2020-01-01'))[0] == 400)
check('обычная встреча без изменений', len(items(new('Разовая'))) == 1)

print('приглашение')
mails.clear()
uid = new('Синк', start=next_weekday(1), recur='weekly', until=next_weekday(1, weeks=8).date().isoformat())
ical = mails[0]['ical']
check('одно письмо каждому, а не на каждую дату', len(mails) == 2)
check('RRULE с UNTIL в UTC', 'RRULE:FREQ=WEEKLY;UNTIL=' in ical and [l for l in ical.split('\r\n') if l.startswith('RRULE')][0].endswith('Z'))
check('DTSTART в часовом поясе серии + VTIMEZONE', 'DTSTART;TZID=Europe/Belgrade:' in ical and 'BEGIN:VTIMEZONE' in ical and 'TZID:Europe/Belgrade' in ical)
check('в VTIMEZONE есть переход на летнее время', 'BEGIN:DAYLIGHT' in ical and 'TZOFFSETTO:+0200' in ical)
check('письмо говорит о повторении', 'Повторяется: каждую неделю до' in mails[0]['text'] and '↻' in mails[0]['html'])

print('отмена одной даты')
got = items(uid)
mails.clear()
code, _ = edit(uid, occurrence=got[2]['start'], scope='one', cancel=True)
after = items(uid)
check('дата исчезла, остальные на месте', code == 200 and got[2]['start'] not in [i['start'] for i in after] and len(after) == len(got) - 1)
ical = mails[0]['ical']
check('гостям — CANCEL с RECURRENCE-ID без RRULE', 'METHOD:CANCEL' in ical and 'RECURRENCE-ID;TZID=Europe/Belgrade:' in ical and 'RRULE' not in ical)
check('письмо про одну дату', 'Отменена только эта дата' in mails[0]['text'])
check('несуществующая дата — 400', edit(uid, occurrence='2020-01-01T10:00:00+00:00', scope='one', cancel=True)[0] == 400)
edit(uid, title='Синк-2', occurrence=after[0]['start'], scope='all')
ical_up = [m for m in mails if m['subject'].startswith('Изменено')][-1]['ical']
check('в новом приглашении серии — EXDATE', 'EXDATE;TZID=Europe/Belgrade:' in ical_up)

print('правка одной даты')
got = items(uid)
mails.clear()
moved = (local(got[1]) + timedelta(hours=2)).isoformat()
code, r = edit(uid, occurrence=got[1]['start'], scope='one', title='Синк особый', start=moved)
check('ответ — отдельная встреча', code == 200 and r['uid'] != uid)
check('в серии даты больше нет', got[1]['start'] not in [i['start'] for i in items(uid)])
one = items(r['uid'])
check('новая встреча разовая, в новое время, комната та же', len(one) == 1 and one[0]['recur'] == '' and one[0]['title'] == 'Синк особый'
      and local(one[0]).hour == 12 and one[0]['link'] == got[1]['link'])
check('гостям: отмена даты и новое приглашение', any(m['cancel'] for m in mails) and any(not m['cancel'] and 'RRULE' not in m['ical'] for m in mails))

print('правка всей серии')
uid = new('Всё', start=next_weekday(2), recur='weekly')
got = items(uid)
edit(uid, occurrence=got[3]['start'], scope='one', cancel=True)
skipped = local(got[3]).date()
target = local(got[5]) + timedelta(days=1, hours=3)   # выбрана шестая дата: среда → четверг, 10:00 → 13:00
code, _ = edit(uid, occurrence=got[5]['start'], scope='all', start=target.isoformat())
after = items(uid)
check('сдвинулись все даты, начало серии — не выбранная дата', code == 200 and local(after[0]).date() == local(got[0]).date() + timedelta(days=1)
      and all(local(i).weekday() == 3 and local(i).hour == 13 for i in after))
check('отменённая дата сдвинулась вместе с серией', skipped + timedelta(days=1) not in [local(i).date() for i in after])
edit(uid, occurrence=after[2]['start'], scope='all', title='Всё-2', tz='Asia/Tokyo')
check('часовой пояс серии не меняется от правки из другого пояса', items(uid)[0]['tz'] == 'Europe/Belgrade' and items(uid)[0]['title'] == 'Всё-2')
pick = items(uid)[2]
edit(uid, occurrence=pick['start'], scope='all', recur='monthly', start=pick['start'])
check('смена правила: серия начинается с выбранной даты', items(uid)[0]['start'] == pick['start'] and items(uid)[0]['recur'] == 'monthly')
pick = items(uid)[1]
edit(uid, occurrence=pick['start'], scope='all', recur='', start=pick['start'])
check('«не повторять»: остаётся одна встреча', [i['start'] for i in items(uid)] == [pick['start']])
edit(uid, recur='daily', until=(local(pick) + timedelta(days=2)).date().isoformat())
check('обычную встречу можно сделать серией', len(items(uid)) == 3)
mails.clear()
edit(uid, occurrence=items(uid)[1]['start'], scope='all', cancel=True)
check('отмена всей серии', items(uid) == [] and 'RECURRENCE-ID' not in mails[0]['ical'] and 'METHOD:CANCEL' in mails[0]['ical'])

print('старая база')
import sqlite3
M.DB = os.path.join(M.DATA, 'old.db')
c = sqlite3.connect(M.DB)
c.execute('''CREATE TABLE meetings (uid TEXT PRIMARY KEY, room TEXT, title TEXT, start TEXT, minutes INTEGER, organizer TEXT, org_email TEXT, emails TEXT,
    note TEXT, backup TEXT, lang TEXT, tz TEXT, owner TEXT, ip TEXT, created INTEGER, updated INTEGER, seq INTEGER DEFAULT 0, cancelled INTEGER DEFAULT 0)''')
c.execute("INSERT INTO meetings (uid, room, title, start, minutes, emails, tz) VALUES ('old', 'r', 'Старая', ?, 30, '[]', 'UTC')", ((NOW + timedelta(days=1)).isoformat(),))
c.commit(); c.close()
got = items('old')
check('база без новых колонок обновляется, встреча разовая', len(got) == 1 and got[0]['recur'] == '')
print(f'\nИТОГО: {"0 ошибок" if not fails else str(fails) + " ОШИБОК"}')
sys.exit(1 if fails else 0)
