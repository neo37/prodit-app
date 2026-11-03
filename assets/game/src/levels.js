/**
 * Уровни: набережные Герцег-Нови, от простой Топлы до крепости Канли-Кула.
 * Маршрут задаётся ломаной, площадки под подружек расставляются сами вдоль неё.
 */

/* ── генераторы маршрутов ── */

const zig = (n, w, z0, dz) => {              // змейка: n колен шириной w
  const p = [];
  for (let i = 0; i <= n; i++) {
    const x = i % 2 ? w : -w;
    p.push([i % 2 ? -w : w, z0 + i * dz], [x, z0 + i * dz]);
  }
  return p;
};

const spiral = (turns, r0, dr) => {           // спираль внутрь
  const p = [];
  const steps = turns * 8;
  for (let i = 0; i <= steps; i++) {
    const a = (i / 8) * Math.PI * 2;
    const r = r0 - i * dr;
    p.push([Math.cos(a) * r, Math.sin(a) * r]);
  }
  return p;
};

const ring = (r, from = 0, to = Math.PI * 2, steps = 20) => {
  const p = [];
  for (let i = 0; i <= steps; i++) {
    const a = from + (to - from) * (i / steps);
    p.push([Math.cos(a) * r, Math.sin(a) * r]);
  }
  return p;
};

/**
 * Площадки: по обе стороны от маршрута, не ближе 4.6 к дороге и 5.5 друг к другу.
 */
export function makeSpots(path, limit = 12) {
  const dist2 = (a, b) => (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2;
  const near = (p, list, r) => list.some((q) => dist2(p, q) < r * r);
  const spots = [];
  for (let i = 0; i < path.length - 1 && spots.length < limit; i++) {
    const a = path[i], b = path[i + 1];
    const mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2;
    const dx = b[0] - a[0], dz = b[1] - a[1];
    const len = Math.hypot(dx, dz) || 1;
    for (const side of [1, -1]) {
      const p = [mx + (-dz / len) * 5.2 * side, mz + (dx / len) * 5.2 * side];
      if (Math.hypot(p[0], p[1]) > 43) continue;
      if (near(p, path, 4.6) || near(p, spots, 5.5)) continue;
      spots.push(p);
      if (spots.length >= limit) break;
    }
  }
  return spots;
}

/** Уровни идут по порядку: пройденный открывает следующий. */
export const LEVELS = [
  {
    id: 1, name: 'Топла', hint: 'Первая набережная. Тени идут медленно — тут учимся.',
    path: [[-38, -14], [-26, -14], [-20, -4], [-20, 8], [-8, 15], [6, 15], [13, 6], [13, -6], [22, -13], [33, -13], [37, -4], [37, 6]],
    enemy: 'dancer', friends: ['mila', 'teya'], shells: 130, target: 8, speed: 0.9,
    sky: 0x8fd4e8, sea: 0x1f7fa8, land: 0x86a86a,
  },
  {
    id: 2, name: 'Игало', hint: 'Длинная дуга у воды: тени успевают разогнаться.',
    path: [[-40, 6], [-28, 12], [-14, 14], [0, 10], [10, 2], [16, -8], [26, -14], [38, -12]],
    enemy: 'dancer', friends: ['mila', 'teya', 'niksha'], shells: 140, target: 10, speed: 1,
    sky: 0x9fdcea, sea: 0x1d86ad, land: 0x8fae6f,
  },
  {
    id: 3, name: 'Савина', hint: 'Кипарисы и повороты — стреляй из-за угла.',
    path: zig(4, 20, -22, 10), enemy: 'dancer', friends: ['mila', 'teya', 'niksha'],
    shells: 150, target: 12, speed: 1.05, sky: 0x93d3df, sea: 0x1b7796, land: 0x7fa063,
  },
  {
    id: 4, name: 'Шкверская лестница', hint: 'Тысяча ступенек вниз. Здесь появляются новые тени.',
    path: zig(6, 24, -30, 9), enemy: 'maskot', friends: ['mila', 'teya', 'niksha', 'iva'],
    shells: 170, target: 12, speed: 1.1, sky: 0x8ecbd8, sea: 0x1a6f8e, land: 0x86a86a,
  },
  {
    id: 5, name: 'Пет Даница', hint: 'Семь километров вдоль моря — самая длинная дорога.',
    path: [[-42, -18], [-30, -14], [-30, -2], [-16, 2], [-16, 16], [-2, 20], [8, 14], [8, 0], [20, -4], [20, -18], [32, -20], [40, -12], [40, 4]],
    enemy: 'maskot', friends: ['mila', 'teya', 'niksha', 'iva'], shells: 180, target: 14, speed: 1.15,
    sky: 0x9adbe6, sea: 0x1f7fa8, land: 0x8cae6c,
  },
  {
    id: 6, name: 'Стари град', hint: 'Улочки закручиваются к площади Белависта.',
    path: spiral(3, 38, 1.4), enemy: 'maskot', friends: ['mila', 'teya', 'niksha', 'iva', 'maskot'],
    shells: 200, target: 15, speed: 1.15, sky: 0xa9d8dd, sea: 0x1c7591, land: 0x9aa878,
  },
  {
    id: 7, name: 'Белависта', hint: 'Площадь с часами: тени идут широким кругом.',
    path: [...ring(34, Math.PI, -Math.PI * 0.2, 16), ...ring(16, -Math.PI * 0.2, Math.PI * 1.2, 14)],
    enemy: 'maskot', friends: ['mila', 'teya', 'niksha', 'iva', 'maskot'],
    shells: 210, target: 16, speed: 1.2, sky: 0x9ed2e2, sea: 0x1b7392, land: 0x93a875,
  },
  {
    id: 8, name: 'Форте-Маре', hint: 'Узкая стена над водой — плит мало, выбирай с умом.',
    path: [[-40, 14], [-24, 16], [-8, 12], [4, 4], [6, -8], [18, -16], [32, -14], [40, -2]],
    enemy: 'maskot', friends: ['teya', 'niksha', 'iva', 'maskot'], spots: 7,
    shells: 220, target: 16, speed: 1.25, sky: 0x8ec7d6, sea: 0x186a88, land: 0xa39a76,
  },
  {
    id: 9, name: 'Мамула', hint: 'Остров посреди Боки: тени обходят его по кругу.',
    path: ring(30, Math.PI * 0.9, -Math.PI * 1.1, 24), enemy: 'maskot',
    friends: ['mila', 'teya', 'niksha', 'iva', 'maskot'], shells: 230, target: 18, speed: 1.3,
    sky: 0x86bfd2, sea: 0x14607e, land: 0x87a06a,
  },
  {
    id: 10, name: 'Канли-Кула', hint: 'Крепость на горе. Сюда идут самые быстрые тени.',
    path: zig(8, 26, -34, 8), enemy: 'maskot', friends: ['mila', 'teya', 'niksha', 'iva', 'maskot'],
    shells: 250, target: 20, speed: 1.4, sky: 0x7fb6cc, sea: 0x125777, land: 0x8d9a6a,
  },
];

export const levelById = (id) => LEVELS.find((l) => l.id === Number(id)) || LEVELS[0];

/* ── прогресс в localStorage ── */

export const progress = {
  get unlocked() { return Number(localStorage.getItem('tails.unlocked') || 1); },
  unlock(id) {
    if (id > this.unlocked && id <= LEVELS.length) localStorage.setItem('tails.unlocked', String(id));
  },
  best(id) { return Number(localStorage.getItem(`tails.best.${id}`) || 0); },
  setBest(id, wave) {
    if (wave > this.best(id)) localStorage.setItem(`tails.best.${id}`, String(wave));
  },
};
