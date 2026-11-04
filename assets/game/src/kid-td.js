/**
 * «Хвосты: набережная» — башенная защита для семилетней Алисы.
 * Крупные кнопки, никакой арифметики, волн много, уровней тоже.
 * Персонажи — наши .glb: Tiny Dancer и маскот, оба с анимациями.
 */
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { clone as cloneSkinned } from 'three/addons/utils/SkeletonUtils.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { LEVELS, levelById, makeSpots, progress } from './levels.js';

const $ = (id) => document.getElementById(id);
const rnd = (a, b) => a + Math.random() * (b - a);

/* ─────────────────── модели и клипы ─────────────────── */

const MODELS = {
  dancer: {
    url: 'models/tiny-dancer.opt.glb', height: 6.4,
    clips: { idle: 'Walking', walk: 'Walking', run: 'Running', dance: 'Running', attack: 'Running' },
    idleSpeed: 0.35,
  },
  maskot: {
    url: 'models/maskot.web.glb', height: 6.8,
    clips: { idle: 'Stylish_Walk_inplace', walk: 'Walking', run: 'Running',
             dance: 'All_Night_Dance', attack: 'Fast_Lightning' },
    idleSpeed: 0.7,
  },
  // уровень «Замок»: не персонаж, а 4 стадии роста одной башни (tools/castle_glb.py)
  castle: { stages: [1, 2, 3, 4].map((n) => `assets/castle/stage${n}.glb`) },
};

const FRIENDS = {
  mila:   { name: 'Мила',   emoji: '🌼', model: 'dancer', tint: 0xffe08a, cost: 40,  range: 13, damage: 12, rate: 0.75 },
  teya:   { name: 'Тея',    emoji: '🐈', model: 'dancer', tint: 0xffb0c8, cost: 50,  range: 12, damage: 6,  rate: 0.9, slow: 0.5 },
  niksha: { name: 'Никша',  emoji: '🌊', model: 'dancer', tint: 0x8fd0ff, cost: 60,  range: 10, damage: 20, rate: 1.2, splash: 3 },
  iva:    { name: 'Ива',    emoji: '🔔', model: 'dancer', tint: 0xc7b0ff, cost: 80,  range: 18, damage: 34, rate: 1.8 },
  maskot: { name: 'Маскот', emoji: '⚡', model: 'maskot', tint: 0xffffff, cost: 110, range: 15, damage: 46, rate: 1.1 },
  castle: { name: 'Замок',  emoji: '🏰', model: 'castle', tint: 0xffd447, cost: 50,  range: 16, damage: 14, rate: 0.8 },
};

/* ─────────────────── волны ─────────────────── */

const waveInfo = (n, level) => ({
  n,
  count: Math.min(3 + Math.floor(n * 0.9), 28),
  hp: Math.round(24 * Math.pow(1.16, n - 1) * (level.speed || 1)),
  speed: Math.min(2 + n * 0.03, 4.4) * (level.speed || 1),
  bounty: 6 + Math.floor(n / 2),
  gap: Math.max(0.4, 1.15 - n * 0.02),
  big: n % 5 === 0,
});

/* ─────────────────── уровень ─────────────────── */

const level = levelById(new URLSearchParams(location.search).get('level') || 1);
const PATH = level.path.map(([x, z]) => new THREE.Vector3(x, 0, z));
const SPOTS = (level.spotsList || makeSpots(level.path, level.spots || 12)).map(([x, z]) => new THREE.Vector3(x, 0, z));
const FRIEND_IDS = level.friends;
// что выбрано в прошлый раз — его подсвечивает экран выбора (start.html)
try { localStorage.setItem('tails.choice', level.id === 'castle' ? 'castle' : 'tails'); } catch (e) { /* без памяти */ }
if (level.id === 'castle') $('load-text').textContent = 'Носим камни для замка…';

/* ─────────────────── сцена ─────────────────── */

const canvas = $('scene');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;

const scene = new THREE.Scene();
scene.background = new THREE.Color(level.sky);
scene.fog = new THREE.Fog(level.sky, 120, 300);   // дальность подгоняется под камеру в begin()

const camera = new THREE.PerspectiveCamera(46, 1, 0.5, 300);
camera.position.set(0, 38, 40);

const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.minDistance = 18;
controls.maxDistance = 80;
controls.maxPolarAngle = Math.PI * 0.45;

scene.add(new THREE.HemisphereLight(0xdff4ff, 0x6f8a63, 1.1));
const sun = new THREE.DirectionalLight(0xfff4d8, 2.2);
sun.position.set(20, 34, 16);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.camera.top = sun.shadow.camera.right = 46;
sun.shadow.camera.bottom = sun.shadow.camera.left = -46;
sun.shadow.camera.far = 100;
sun.shadow.bias = -0.0015;
sun.shadow.normalBias = 0.03;
scene.add(sun);

const sea = new THREE.Mesh(new THREE.PlaneGeometry(400, 400),
  new THREE.MeshStandardMaterial({ color: level.sea, roughness: 0.35, metalness: 0.1 }));
sea.rotation.x = -Math.PI / 2;
sea.position.y = -0.6;
scene.add(sea);

const land = new THREE.Mesh(new THREE.CircleGeometry(46, 64),
  new THREE.MeshStandardMaterial({ color: level.land, roughness: 1 }));
land.rotation.x = -Math.PI / 2;
land.receiveShadow = true;
scene.add(land);

// дорожка
const stoneMat = new THREE.MeshStandardMaterial({ color: 0xe3d7c0, roughness: 0.95 });
for (let i = 0; i < PATH.length - 1; i++) {
  const a = PATH[i], b = PATH[i + 1];
  const road = new THREE.Mesh(new THREE.BoxGeometry(a.distanceTo(b) + 6.2, 0.4, 6.2), stoneMat);
  road.position.copy(a).lerp(b, 0.5).setY(0.05);
  road.rotation.y = -Math.atan2(b.z - a.z, b.x - a.x);
  road.receiveShadow = true;
  scene.add(road);
}

// домик в конце, калитка в начале
const house = new THREE.Group();
const walls = new THREE.Mesh(new THREE.BoxGeometry(10, 7, 10),
  new THREE.MeshStandardMaterial({ color: 0xf6efe2, roughness: 0.9 }));
walls.position.y = 3.5;
const roof = new THREE.Mesh(new THREE.ConeGeometry(8.8, 4.8, 4),
  new THREE.MeshStandardMaterial({ color: 0xd9663f, roughness: 0.8 }));
roof.position.y = 9.4;
roof.rotation.y = Math.PI / 4;
house.add(walls, roof);
house.traverse((o) => { o.castShadow = true; o.receiveShadow = true; });
const last = PATH[PATH.length - 1], prev = PATH[PATH.length - 2];
house.position.copy(last).add(last.clone().sub(prev).setY(0).normalize().multiplyScalar(5));
scene.add(house);

const gate = new THREE.Mesh(new THREE.TorusGeometry(3.6, 0.5, 10, 24, Math.PI),
  new THREE.MeshStandardMaterial({ color: 0x3a3550, roughness: 0.7 }));
gate.position.copy(PATH[0]).setY(0.3);
gate.rotation.y = Math.atan2(PATH[1].z - PATH[0].z, PATH[1].x - PATH[0].x);
gate.castShadow = true;
scene.add(gate);

// зелень
for (let i = 0; i < 40; i++) {
  const ang = Math.random() * Math.PI * 2, r = rnd(10, 45);
  const p = new THREE.Vector3(Math.cos(ang) * r, 0, Math.sin(ang) * r);
  const clear = level.id === 'castle' ? 9 : 6;   // замок шире подружки — деревья подальше от плит
  if (PATH.some((w) => w.distanceTo(p) < 8) || SPOTS.some((s) => s.distanceTo(p) < clear)) continue;
  const t = new THREE.Group();
  if (Math.random() < 0.55) {
    const c = new THREE.Mesh(new THREE.ConeGeometry(rnd(1, 1.7), rnd(5.5, 9), 7),
      new THREE.MeshStandardMaterial({ color: 0x2f5d3f, roughness: 1 }));
    c.position.y = 4;
    t.add(c);
  } else {
    const b = new THREE.Mesh(new THREE.SphereGeometry(rnd(1.4, 2.4), 10, 8),
      new THREE.MeshStandardMaterial({ color: Math.random() < 0.5 ? 0xff8fb0 : 0xffd447, roughness: 1 }));
    b.position.y = 1.9;
    t.add(b);
  }
  t.position.copy(p);
  t.traverse((o) => { o.castShadow = true; });
  scene.add(t);
}

// площадки
const spotMeshes = SPOTS.map((p, i) => {
  const disc = new THREE.Mesh(new THREE.CylinderGeometry(3.2, 3.4, 0.5, 22),
    new THREE.MeshStandardMaterial({ color: 0xf3e6c8, roughness: 0.9, emissive: 0x2a2410, emissiveIntensity: 0.4 }));
  disc.position.copy(p).setY(0.2);
  disc.receiveShadow = true;
  disc.userData.spotIndex = i;
  scene.add(disc);

  const ring = new THREE.Mesh(new THREE.TorusGeometry(2.9, 0.16, 8, 30),
    new THREE.MeshBasicMaterial({ color: 0xffd447, transparent: true, opacity: 0.75 }));
  ring.rotation.x = -Math.PI / 2;
  ring.position.copy(p).setY(0.55);
  scene.add(ring);
  disc.userData.ring = ring;
  return disc;
});

// круг дальности выбранной подружки
const rangeRing = new THREE.Mesh(
  new THREE.RingGeometry(1, 1.04, 48),
  new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.35, side: THREE.DoubleSide })
);
rangeRing.rotation.x = -Math.PI / 2;
rangeRing.visible = false;
scene.add(rangeRing);

/* ─────────────────── персонажи ─────────────────── */

const loaded = {};        // kind → { gltf, scale }

class Actor {
  constructor(kind, { tint = 0xffffff, scale = 1, clip = 'idle', dark = false } = {}) {
    const src = loaded[kind];
    this.kind = kind;
    this.def = MODELS[kind];
    this.root = new THREE.Group();
    this.model = cloneSkinned(src.gltf.scene);
    this.model.scale.setScalar(src.scale * scale);
    this.root.add(this.model);

    this.hips = null;
    this.model.traverse((o) => {
      if (o.isBone && !this.hips && /hips/i.test(o.name)) this.hips = o;
      if (!o.isMesh) return;
      o.castShadow = true;
      o.receiveShadow = !dark;
      o.frustumCulled = false;
      o.material = o.material.clone();
      o.material.color = new THREE.Color(tint);
      if (dark) { o.material.emissive = new THREE.Color(0x140a26); o.material.emissiveIntensity = 0.55; }
    });

    this.mixer = new THREE.AnimationMixer(this.model);
    this.actions = {};
    for (const c of src.gltf.animations) this.actions[c.name] = this.mixer.clipAction(c);
    this.current = null;
    this._v = new THREE.Vector3();
    this.play(clip, clip === 'idle' ? this.def.idleSpeed : 1, 0);
  }

  play(semantic, timeScale = 1, fade = 0.22) {
    const name = this.def.clips[semantic] || semantic;
    const a = this.actions[name] || Object.values(this.actions)[0];
    if (!a) return;
    a.reset();
    a.setEffectiveTimeScale(timeScale);
    a.setEffectiveWeight(1);
    a.play();
    if (fade > 0) a.fadeIn(fade);
    if (this.current && this.current !== a) { fade > 0 ? this.current.fadeOut(fade) : this.current.stop(); }
    this.current = a;
  }

  setSpeed(t) { if (this.current) this.current.setEffectiveTimeScale(t); }

  update(dt) {
    this.mixer.update(dt);
    if (this.hips) {                       // гасим смещение корня: ходьбой рулит игра
      this.hips.updateWorldMatrix(true, false);
      this._v.setFromMatrixPosition(this.hips.matrixWorld);
      this.root.worldToLocal(this._v);
      this.model.position.x -= this._v.x;
      this.model.position.z -= this._v.z;
    }
  }
}

/* ─────────── замок: башня, которая растёт 1 → 4 ─────────── */

const castleStages = [];  // кэш загруженных стадий: [stage1.scene … stage4.scene], клонируются на каждую плиту
const easeOutBack = (t) => 1 + 2.2 * (t - 1) ** 3 + 1.2 * (t - 1) ** 2;

class CastleActor {
  constructor() {
    this.root = new THREE.Group();
    this.def = { idleSpeed: 1, clips: {} };
    this.stage = 0;
    this.model = null;
    this.old = [];          // уходящие стадии (сжимаются в землю)
    this.grow = 1;
    this.yaw = 0;           // куда смотрит прицел-баллиста
    this.aimYaw = 0;
    this.setStage(1);
  }

  /** Показать стадию n (1..4; выше 4 — остаётся 4-я, только «подпрыгивает»). */
  setStage(n) {
    n = Math.max(1, Math.min(castleStages.length, n));
    if (n === this.stage) { this.grow = 0.55; return; }
    if (this.model) this.old.push({ obj: this.model, t: 0 });
    this.stage = n;
    this.model = castleStages[n - 1].clone();
    this.model.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
    this.ballista = this.model.getObjectByName('ballista');
    if (this.ballista) this.ballista.rotation.y = this.yaw;
    this.root.add(this.model);
    this.grow = 0;
    this.update(0);
  }

  play() {}                 // анимаций-клипов у замка нет
  setSpeed() {}

  aim(target) {
    const b = this.ballista;
    if (!b) return;
    b.getWorldPosition(this._p || (this._p = new THREE.Vector3()));
    this.aimYaw = Math.atan2(target.x - this._p.x, target.z - this._p.z);
  }

  /** Откуда вылетает снаряд: из колеса баллисты. */
  muzzle(out) {
    if (this.ballista) return this.ballista.getWorldPosition(out).setY(out.y + 1.35);
    return out.copy(this.root.position).setY(4);
  }

  update(dt) {
    if (this.grow < 1) {    // рост: из земли вверх с перехлёстом
      this.grow = Math.min(1, this.grow + dt / 0.7);
      const k = easeOutBack(this.grow);
      this.model.scale.set(0.85 + 0.15 * k, Math.max(0.02, k), 0.85 + 0.15 * k);
    }
    for (let i = this.old.length - 1; i >= 0; i--) {
      const o = this.old[i];
      o.t += dt / 0.35;
      o.obj.scale.set(1 + 0.05 * o.t, Math.max(0.01, 1 - o.t), 1 + 0.05 * o.t);
      if (o.t >= 1) { this.root.remove(o.obj); this.old.splice(i, 1); }
    }
    if (this.ballista) {    // плавный поворот прицела
      let d = this.aimYaw - this.yaw;
      d = Math.atan2(Math.sin(d), Math.cos(d));
      this.yaw += d * Math.min(1, dt * 8);
      this.ballista.rotation.y = this.yaw;
    }
  }
}

/* ─────────── привидения: часть теней в волне (весёлые, не страшные) ─────────── */

// доля привидений среди теней волны, на всех уровнях (?ghosts=0..1 — для проверок)
const GHOST_CHANCE = Number(new URLSearchParams(location.search).get('ghosts') ?? 0.25);

/** Общая геометрия привидения строится один раз: капля-тело с хвостиком, глазки, щёчки, ротик «о», ручки. */
const ghostKit = (() => {
  // профиль капли: круглая голова сверху, книзу сужается в хвостик
  const prof = [];
  for (let i = 0; i <= 16; i++) {
    const t = i / 16;                                   // 0 — кончик хвоста, 1 — макушка
    const y = t * 4.6;
    const r = t > 0.62 ? Math.sqrt(Math.max(0, 1 - ((t - 0.62) / 0.38) ** 2)) * 1.55
                       : 1.55 * Math.pow(t / 0.62, 0.7) * (0.25 + 0.75 * t / 0.62) + 0.02;
    prof.push(new THREE.Vector2(Math.max(0.001, r), y));
  }
  const body = new THREE.LatheGeometry(prof, 20);
  // хвостик загибается назад и чуть вбок, низ тела — волнистый
  const p = body.attributes.position;
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i), y = p.getY(i), z = p.getZ(i);
    const low = Math.max(0, 1 - y / 2.2);               // 1 у кончика, 0 выше середины
    const a = Math.atan2(z, x);
    p.setXYZ(i, x + low * low * 1.0, y + Math.sin(a * 5) * 0.12 * low, z - low * low * 1.6);
  }
  body.computeVertexNormals();
  const eye = new THREE.SphereGeometry(0.3, 12, 10);
  const shine = new THREE.SphereGeometry(0.09, 8, 6);
  const cheek = new THREE.CircleGeometry(0.22, 14);
  const mouth = new THREE.TorusGeometry(0.15, 0.06, 6, 14);
  const arm = new THREE.SphereGeometry(0.42, 10, 8);
  const blob = new THREE.CircleGeometry(1.5, 20);
  const dark = new THREE.MeshBasicMaterial({ color: 0x2b2c4a });
  const white = new THREE.MeshBasicMaterial({ color: 0xffffff });
  const blush = new THREE.MeshBasicMaterial({ color: 0xffa3c0, transparent: true, opacity: 0.8 });
  return { body, eye, shine, cheek, mouth, arm, blob, dark, white, blush };
})();

class GhostActor {
  constructor(scale = 1) {
    const k = ghostKit;
    this.root = new THREE.Group();
    this.def = { idleSpeed: 1, clips: {} };
    this.model = new THREE.Group();
    this.model.scale.setScalar(scale);
    this.root.add(this.model);
    // своё тело у каждого — чтобы мерцали и таяли по отдельности
    const hue = [0xeaf6ff, 0xdff0ff, 0xf1ecff][Math.floor(Math.random() * 3)];
    this.mat = new THREE.MeshStandardMaterial({ color: hue, emissive: 0x9fd8ff, emissiveIntensity: 0.35,
      roughness: 0.5, transparent: true, opacity: 0.65, depthWrite: false });
    this.base = rnd(0.58, 0.7);
    const body = new THREE.Mesh(k.body, this.mat);
    this.model.add(body);
    this.face = new THREE.Group();          // лицо и ручки — непрозрачные, но тоже тают в конце
    for (const s of [-1, 1]) {
      const e = new THREE.Mesh(k.eye, k.dark);
      e.position.set(s * 0.5, 3.55, 1.28);
      e.scale.set(1, 1.15, 0.5);
      const h = new THREE.Mesh(k.shine, k.white);
      h.position.set(s * 0.5 + 0.09, 3.68, 1.43);
      const c = new THREE.Mesh(k.cheek, k.blush);
      c.position.set(s * 0.9, 3.05, 1.2);
      c.rotation.y = s * 0.55;
      const a = new THREE.Mesh(k.arm, this.mat);
      a.position.set(s * 1.55, 2.6, 0.3);
      a.scale.set(0.8, 0.55, 0.7);
      this.face.add(e, h, c, a);
    }
    const m = new THREE.Mesh(k.mouth, k.dark);
    m.position.set(0, 2.95, 1.42);
    this.face.add(m);
    this.model.add(this.face);
    // мягкая тень-пятно на земле вместо настоящей (у полупрозрачного тела тень была бы чёрной)
    this.blobMat = new THREE.MeshBasicMaterial({ color: 0x24304a, transparent: true, opacity: 0.22, depthWrite: false });
    this.blob = new THREE.Mesh(k.blob, this.blobMat);
    this.blob.rotation.x = -Math.PI / 2;
    this.blob.position.y = 0.32;
    this.blob.scale.setScalar(scale);
    this.root.add(this.blob);
    this.time = Math.random() * 10;
    this.rate = 1;
    this.flash = 0;
    this.fade = 1;                          // 1 — живое, к 0 — растворилось
  }

  play() {}
  setSpeed(t) { this.rate = t; }
  hit() { this.flash = 1; }

  update(dt) {
    this.time += dt * (0.6 + 0.4 * this.rate);
    const t = this.time;
    this.model.position.y = 1.1 + Math.sin(t * 2.4) * 0.45;              // парение вверх-вниз
    // мордочкой к ребёнку: тело летит по дороге, а лицо всегда повёрнуто к камере
    const cam = Math.atan2(camera.position.x - this.root.position.x, camera.position.z - this.root.position.z);
    this._q = this._q || new THREE.Quaternion();
    this._e = this._e || new THREE.Euler(0, 0, 0, 'YXZ');
    this._e.set(0.08 + Math.sin(t * 2.4 + 1) * 0.05, cam, Math.sin(t * 1.7) * 0.09);   // наклон и покачивание
    this.model.quaternion.copy(this.root.quaternion).invert().multiply(this._q.setFromEuler(this._e));
    this.blob.scale.setScalar(this.model.scale.x * (1 - Math.sin(t * 2.4) * 0.12));
    this.flash = Math.max(0, this.flash - dt * 4);
    const flicker = Math.sin(t * 5.3) * 0.04 + Math.sin(t * 13.1) * 0.02;
    this.mat.opacity = (this.base + flicker - this.flash * 0.3) * this.fade;
    this.mat.emissiveIntensity = 0.35 + this.flash * 0.8;
  }

  /** Растворение: возвращает true, когда исчезло совсем. */
  vanish(dt) {
    this.fade = Math.max(0, this.fade - dt / 0.6);
    this.update(dt);
    const g = 1 + (1 - this.fade) * 0.5;
    this.model.scale.setScalar(this.model.userData.s0 * g);
    this.model.position.y += (1 - this.fade) * 2.5;
    this.face.visible = this.fade > 0.35;
    this.blobMat.opacity = 0.22 * this.fade;
    return this.fade <= 0;
  }
}

/** Облачко при исчезновении привидения. */
function puff(pos, scale = 1) {
  for (let i = 0; i < 7; i++) {
    const m = new THREE.Mesh(new THREE.SphereGeometry(rnd(0.5, 0.9) * scale, 10, 8),
      new THREE.MeshBasicMaterial({ color: 0xf4f9ff, transparent: true, opacity: 0.7, depthWrite: false }));
    const a = (i / 7) * Math.PI * 2;
    m.position.copy(pos).add(new THREE.Vector3(Math.cos(a) * 0.8, rnd(2.5, 4.2) * scale, Math.sin(a) * 0.8));
    scene.add(m);
    game.vanishing.push({ puff: m, life: 0, vx: Math.cos(a) * 1.6, vz: Math.sin(a) * 1.6 });
  }
}

/** Убрать врага со сцены: привидение тает с облачком, обычная тень исчезает сразу (как было). */
function removeEnemy(e) {
  if (e.actor instanceof GhostActor) {
    e.bar.visible = false;
    e.actor.model.userData.s0 = e.actor.model.scale.x;
    game.vanishing.push({ ghost: e.actor });
    puff(e.actor.root.position, e.actor.model.scale.x);
  } else {
    scene.remove(e.actor.root);
  }
}

/* ─────────────────── состояние ─────────────────── */

const game = {
  shells: level.shells, tails: 5, wave: 0, running: false, over: false, won: false,
  spawnQueue: [], spawnTimer: 0, enemies: [], towers: new Map(), shots: [], drops: [], vanishing: [],
  danceUntil: 0, danceReady: 0,
  best: progress.best(level.id),
  voice: localStorage.getItem('tails.voice') !== 'off',
};

/* ─────────────────── голос и подсказки ─────────────────── */

let ruVoice = null;
// Голоса может не быть (WebView в приложениях, часть браузеров) — тогда игра просто молчит, а не падает.
const synth = typeof window !== 'undefined' && window.speechSynthesis && window.SpeechSynthesisUtterance ? window.speechSynthesis : null;
const pickVoice = () => {
  try { ruVoice = synth ? synth.getVoices().find((v) => v.lang.startsWith('ru')) || null : null; } catch (e) { ruVoice = null; }
};
pickVoice();
if (synth) synth.onvoiceschanged = pickVoice;
function say(text) {
  if (!game.voice || !text || !synth) return;
  try {
  synth.cancel();
  const u = new SpeechSynthesisUtterance(text);
  if (ruVoice) u.voice = ruVoice;
  u.lang = 'ru-RU'; u.pitch = 1.3; u.rate = 1.05;
  synth.speak(u);
  } catch (e) { /* голос недоступен — без звука */ }
}

function toast(text, ms = 2600) {
  const el = $('toast');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.remove('show'), ms);
}

function drawHud() {
  $('tails').textContent = '🎀'.repeat(Math.max(0, game.tails)) + '·'.repeat(Math.max(0, 5 - game.tails));
  $('shells').textContent = game.shells;
  $('wave-num').textContent = game.wave || '—';
  $('wave-place').textContent = game.wave
    ? `${level.name} · до конца ${Math.max(0, level.target - game.wave)}`
    : `${level.name} · жми «Волна!»`;
  $('best').textContent = game.best;
  $('btn-dance').disabled = performance.now() < game.danceReady;
}

/* ─────────────────── постройка ─────────────────── */

let pickedSpot = -1;

function showBuild(spotIndex) {
  pickedSpot = spotIndex;
  const host = $('build');
  host.innerHTML = '';
  const tower = game.towers.get(spotIndex);

  if (tower) {
    const up = document.createElement('button');
    up.className = 'friend';
    up.innerHTML = `<span class="face">⬆️</span><b>Подрасти</b><span class="price">🐚 ${tower.upCost}</span>`;
    up.disabled = game.shells < tower.upCost;
    up.onclick = () => upgrade(spotIndex);
    const bye = document.createElement('button');
    bye.className = 'friend';
    bye.innerHTML = `<span class="face">🏠</span><b>Домой</b><span class="price">🐚 +${Math.round(tower.spent / 2)}</span>`;
    bye.onclick = () => sell(spotIndex);
    host.append(up, bye);
  } else {
    for (const id of FRIEND_IDS) {
      const f = FRIENDS[id];
      const b = document.createElement('button');
      b.className = 'friend';
      b.innerHTML = `<span class="face">${f.emoji}</span><b>${f.name}</b><span class="price">🐚 ${f.cost}</span>`;
      b.disabled = game.shells < f.cost;
      b.onclick = () => build(spotIndex, id);
      host.append(b);
    }
  }
  host.classList.add('show');
}

const hideBuild = () => { pickedSpot = -1; $('build').classList.remove('show'); highlight(-1); };

function highlight(index) {
  spotMeshes.forEach((d, i) => {
    const on = i === index;
    d.material.emissiveIntensity = on ? 1.4 : 0.4;
    d.scale.setScalar(on ? 1.08 : 1);
    if (d.userData.ring) d.userData.ring.material.opacity = on ? 1 : 0.75;
  });
  const t = game.towers.get(index);
  const r = t ? t.range : (FRIEND_IDS.length ? FRIENDS[FRIEND_IDS[0]].range : 0);
  if (index < 0 || !r) { rangeRing.visible = false; return; }
  rangeRing.visible = true;
  rangeRing.scale.setScalar(r);
  rangeRing.position.copy(SPOTS[index]).setY(0.5);
}

function build(spotIndex, id) {
  const f = FRIENDS[id];
  if (game.shells < f.cost || game.towers.has(spotIndex)) return;
  game.shells -= f.cost;

  const actor = f.model === 'castle' ? new CastleActor() : new Actor(f.model, { tint: f.tint, clip: 'idle' });
  actor.root.position.copy(SPOTS[spotIndex]).setY(0.4);
  actor.root.userData.spotIndex = spotIndex;
  scene.add(actor.root);

  game.towers.set(spotIndex, {
    id, actor, level: 1, spent: f.cost, upCost: Math.round(f.cost * 0.8),
    range: f.range, damage: f.damage, rate: f.rate, cool: 0,
    slow: f.slow || 0, splash: f.splash || 0, tint: f.tint,
  });
  const ring = spotMeshes[spotIndex].userData.ring;
  if (ring) ring.visible = false;
  say(`${f.name} на месте!`);
  toast(`${f.emoji} ${f.name} ${f.model === 'castle' ? 'встал' : 'встала'} на плиту`);
  hideBuild();
  drawHud();
}

function upgrade(spotIndex) {
  const t = game.towers.get(spotIndex);
  if (!t || game.shells < t.upCost) return;
  game.shells -= t.upCost;
  t.spent += t.upCost;
  t.level++;
  t.damage = Math.round(t.damage * 1.9);
  t.range += 2.5;
  t.rate *= 0.85;
  t.upCost = Math.round(t.upCost * 1.7);
  if (t.actor.setStage) {   // замок: новая стадия модели (после 4-й — остаётся 4-я)
    t.actor.setStage(t.level);
    sparks(t.actor.root.position, 10);
    const words = ['', 'Башня!', 'Выросла стена!', 'Появился мост!', 'Настоящая крепость!'];
    say(t.level <= 4 ? words[t.level] : 'Замок стал сильнее!');
    toast(`🏰 Замок — ${t.level <= 4 ? 'стадия ' + t.level : 'уровень ' + t.level}`);
    showBuild(spotIndex);   // обновить цену следующего роста, меню остаётся под рукой
    drawHud();
    return;
  }
  t.actor.root.scale.setScalar(1 + t.level * 0.12);
  sparks(t.actor.root.position, 6);
  say('Подросла!');
  toast(`${FRIENDS[t.id].name} — уровень ${t.level}`);
  drawHud();
}

function sell(spotIndex) {
  const t = game.towers.get(spotIndex);
  if (!t) return;
  game.shells += Math.round(t.spent / 2);
  scene.remove(t.actor.root);
  game.towers.delete(spotIndex);
  const ring = spotMeshes[spotIndex].userData.ring;
  if (ring) ring.visible = true;
  hideBuild();
  drawHud();
}

/* ─────────────────── волны ─────────────────── */

function startWave() {
  if (game.over) return;
  if (game.spawnQueue.length) { toast('Волна ещё идёт!'); return; }
  game.wave++;
  const w = waveInfo(game.wave, level);
  game.spawnQueue = Array.from({ length: w.count }, (_, i) => ({
    hp: Math.round(w.hp * (w.big && i === 0 ? 6 : 1)),
    speed: w.speed * (w.big && i === 0 ? 0.6 : 1),
    scale: w.big && i === 0 ? 1.9 : 1,
    bounty: w.bounty * (w.big && i === 0 ? 5 : 1),
  }));
  game.spawnTimer = 0;
  game.running = true;
  game.best = Math.max(game.best, game.wave);
  progress.setBest(level.id, game.wave);
  say(`Волна ${game.wave}!`);
  toast(`🌊 Волна ${game.wave}${w.big ? ' — большая тень!' : ''}`);
  drawHud();
}

function spawnEnemy(cfg) {
  // иногда вместо тени прилетает привидение: здоровье, скорость и награда — те же
  const ghost = Math.random() < GHOST_CHANCE;
  const a = ghost ? new GhostActor(0.95 * cfg.scale)
    : new Actor(level.enemy, { tint: 0x6a5f8a, scale: 0.92 * cfg.scale, clip: 'run', dark: true });
  a.root.position.copy(PATH[0]);
  scene.add(a.root);

  const bar = makeBar();
  bar.position.y = (ghost ? 6.6 : 7.2) * cfg.scale + 1.0;
  a.root.add(bar);

  game.enemies.push({ actor: a, bar, hp: cfg.hp, maxHp: cfg.hp, speed: cfg.speed,
                      bounty: cfg.bounty, seg: 0, t: 0, slow: 0 });
}

function makeBar() {
  const g = new THREE.Group();
  const back = new THREE.Mesh(new THREE.PlaneGeometry(3.4, 0.42), new THREE.MeshBasicMaterial({ color: 0x2a1c3a }));
  const fill = new THREE.Mesh(new THREE.PlaneGeometry(3.4, 0.42), new THREE.MeshBasicMaterial({ color: 0xff6f91 }));
  fill.position.z = 0.01;
  g.add(back, fill);
  g.userData.fill = fill;
  return g;
}

/* ─────────────────── бой ─────────────────── */

function shoot(tower, enemy) {
  const ball = new THREE.Mesh(new THREE.SphereGeometry(0.55, 12, 10),
    new THREE.MeshBasicMaterial({ color: tower.tint }));
  if (tower.actor.muzzle) tower.actor.muzzle(ball.position);
  else ball.position.copy(tower.actor.root.position).setY(3.6);
  scene.add(ball);
  game.shots.push({ mesh: ball, target: enemy, damage: tower.damage, slow: tower.slow, splash: tower.splash });
  tower.actor.play('attack', 1.4, 0.12);
  clearTimeout(tower.back);
  tower.back = setTimeout(() => tower.actor.play('idle', tower.actor.def.idleSpeed, 0.2), 600);
}

function sparks(pos, n = 4) {
  for (let i = 0; i < n; i++) {
    const m = new THREE.Mesh(new THREE.SphereGeometry(0.3, 8, 6), new THREE.MeshBasicMaterial({ color: 0xffd447 }));
    m.position.copy(pos).add(new THREE.Vector3(rnd(-0.6, 0.6), rnd(1, 2), rnd(-0.6, 0.6)));
    scene.add(m);
    game.drops.push({ mesh: m, life: 0.9, vy: rnd(1.5, 3) });
  }
}

function killEnemy(e, i) {
  removeEnemy(e);
  game.enemies.splice(i, 1);
  game.shells += e.bounty;
  sparks(e.actor.root.position, 3);
  drawHud();
}

function leak(e, i) {
  removeEnemy(e);
  game.enemies.splice(i, 1);
  game.tails--;
  document.body.classList.add('shake');
  setTimeout(() => document.body.classList.remove('shake'), 500);
  say(game.tails > 0 ? 'Ой! Хвостик утащили!' : 'Хвостики кончились!');
  if (game.tails > 0) toast(`🎀 Хвостик утащили. Осталось ${game.tails}`);
  drawHud();
  if (game.tails <= 0) finish(false);
}

function finish(won) {
  game.over = true;
  game.running = false;
  game.won = won;
  const solo = typeof level.id !== 'number';   // «Замок» вне цепочки мест: ничего не открывает
  const next = solo ? null : LEVELS.find((l) => l.id === level.id + 1);
  if (won && !solo) progress.unlock(level.id + 1);

  $('ov-title').textContent = won ? '🏆 Уровень пройден!' : 'Тени утащили хвостики';
  $('ov-text').textContent = won
    ? (solo ? `${level.name} выстоял! Можно остаться и держать волны дальше.`
      : next ? `${level.name} — наша. Открылось новое место: ${next.name}.`
            : 'Пройдены все места Герцег-Нови! Можно остаться и держать волны дальше.')
    : `Ты продержалась ${game.wave} волн на месте «${level.name}». Рекорд — ${game.best}.`;

  const row = $('ov-buttons');
  row.innerHTML = '';
  if (won && next) {
    const b = document.createElement('a');
    b.className = 'huge';
    b.href = `play.html?level=${next.id}`;
    b.textContent = `▶ Дальше: ${next.name}`;
    row.append(b);
  }
  const more = document.createElement('button');
  more.className = 'huge ghost';
  more.textContent = won ? '🌊 Ещё волны здесь' : '↻ Ещё раз';
  more.onclick = () => { if (won) { game.over = false; $('overlay').classList.remove('show'); startWave(); } else location.reload(); };
  const home = document.createElement('a');
  home.className = 'huge ghost';
  home.href = 'index.html';
  home.textContent = '🏠 Домой';
  row.append(more, home);

  $('overlay').classList.add('show');
  say(won ? 'Ура! Уровень пройден!' : 'Ничего страшного! Попробуем ещё раз!');
}

/* ─────────────────── ввод ─────────────────── */

const ray = new THREE.Raycaster();
const mouse = new THREE.Vector2();

canvas.addEventListener('pointerdown', (e) => {
  mouse.x = (e.clientX / innerWidth) * 2 - 1;
  mouse.y = -(e.clientY / innerHeight) * 2 + 1;
  ray.setFromCamera(mouse, camera);
  // замок шире плиты — по нему самому тоже можно ткнуть («Подрасти» / «Домой»)
  const castles = [...game.towers.values()].filter((t) => t.actor.setStage).map((t) => t.actor.root);
  const hit = ray.intersectObjects([...spotMeshes, ...castles], true)[0];
  let o = hit && hit.object;
  while (o && o.userData.spotIndex === undefined) o = o.parent;
  if (o) { highlight(o.userData.spotIndex); showBuild(o.userData.spotIndex); }
  else hideBuild();
});

$('btn-wave').onclick = startWave;
$('btn-dance').onclick = () => {
  if (performance.now() < game.danceReady) return;
  game.danceUntil = performance.now() + 5000;
  game.danceReady = performance.now() + 22000;
  for (const t of game.towers.values()) t.actor.play('dance', 1.3, 0.15);
  setTimeout(() => { for (const t of game.towers.values()) t.actor.play('idle', t.actor.def.idleSpeed, 0.3); }, 5000);
  say('Танцуем!');
  toast('💃 Все танцуют — тени засмотрелись и замедлились!');
  drawHud();
};
$('btn-voice').onclick = () => {
  game.voice = !game.voice;
  localStorage.setItem('tails.voice', game.voice ? 'on' : 'off');
  $('btn-voice').textContent = game.voice ? '🔊' : '🔇';
  if (!game.voice && synth) { try { synth.cancel(); } catch (e) { /* нет голоса */ } }
};

addEventListener('keydown', (e) => {
  if (e.code === 'Space') { e.preventDefault(); startWave(); }
  if (e.code === 'KeyD') $('btn-dance').click();
  if (e.code === 'Escape') hideBuild();
});

/* ─────────────────── цикл ─────────────────── */

const clock = new THREE.Clock();
const tmp = new THREE.Vector3();

function tick() {
  requestAnimationFrame(tick);
  const dt = Math.min(clock.getDelta(), 0.1);
  const now = performance.now();
  const dancing = now < game.danceUntil;

  if (game.spawnQueue.length) {
    game.spawnTimer -= dt;
    if (game.spawnTimer <= 0) {
      spawnEnemy(game.spawnQueue.shift());
      game.spawnTimer = waveInfo(game.wave, level).gap;
    }
  }

  for (let i = game.enemies.length - 1; i >= 0; i--) {
    const e = game.enemies[i];
    const a = PATH[e.seg], b = PATH[e.seg + 1];
    if (!b) { leak(e, i); continue; }

    const slowK = (dancing ? 0.5 : 1) * (e.slow > 0 ? 0.45 : 1);
    if (e.slow > 0) e.slow -= dt;
    e.t += (e.speed * slowK * dt) / a.distanceTo(b);
    if (e.t >= 1) { e.t -= 1; e.seg++; }
    else {
      e.actor.root.position.lerpVectors(a, b, e.t);
      e.actor.root.lookAt(tmp.copy(b).setY(e.actor.root.position.y));
    }
    e.actor.setSpeed(Math.max(0.35, slowK));
    e.actor.update(dt);
    e.bar.quaternion.copy(camera.quaternion);
    e.bar.userData.fill.scale.x = Math.max(0.001, e.hp / e.maxHp);
    e.bar.userData.fill.position.x = -(1 - e.hp / e.maxHp) * 1.7;
  }

  for (const t of game.towers.values()) {
    t.actor.update(dt);
    t.cool -= dt;
    if (t.cool > 0) continue;
    let target = null, bestSeg = -1;
    for (const e of game.enemies) {
      const d = e.actor.root.position.distanceTo(t.actor.root.position);
      if (d <= t.range && e.seg > bestSeg) { target = e; bestSeg = e.seg; }
    }
    if (target) {
      if (t.actor.aim) t.actor.aim(target.actor.root.position);   // замок стоит фасадом к камере, крутится только прицел
      else t.actor.root.lookAt(tmp.copy(target.actor.root.position).setY(t.actor.root.position.y));
      shoot(t, target);
      t.cool = t.rate;
    }
  }

  for (let i = game.shots.length - 1; i >= 0; i--) {
    const s = game.shots[i];
    if (!game.enemies.includes(s.target)) { scene.remove(s.mesh); game.shots.splice(i, 1); continue; }
    const to = tmp.copy(s.target.actor.root.position).setY(3);
    const d = s.mesh.position.distanceTo(to);
    if (d < 1.6) {
      s.target.hp -= s.damage;
      if (s.target.actor.hit) s.target.actor.hit();   // привидение вздрагивает и светлеет
      if (s.slow) s.target.slow = 1.6;
      if (s.splash) {
        for (const e of game.enemies) {
          if (e !== s.target && e.actor.root.position.distanceTo(to) < s.splash * 1.6) e.hp -= s.damage * 0.5;
        }
      }
      scene.remove(s.mesh);
      game.shots.splice(i, 1);
    } else {
      s.mesh.position.lerp(to, Math.min(1, (30 * dt) / d));
    }
  }

  for (let i = game.enemies.length - 1; i >= 0; i--) {
    if (game.enemies[i].hp <= 0) killEnemy(game.enemies[i], i);
  }

  for (let i = game.vanishing.length - 1; i >= 0; i--) {   // тающие привидения и облачка
    const v = game.vanishing[i];
    if (v.ghost) {
      if (v.ghost.vanish(dt)) { scene.remove(v.ghost.root); v.ghost.mat.dispose(); v.ghost.blobMat.dispose(); game.vanishing.splice(i, 1); }
    } else {
      v.life += dt / 0.8;
      v.puff.scale.setScalar(1 + v.life * 1.4);
      v.puff.position.x += v.vx * dt; v.puff.position.z += v.vz * dt; v.puff.position.y += 1.2 * dt;
      v.puff.material.opacity = 0.7 * (1 - v.life);
      if (v.life >= 1) { scene.remove(v.puff); v.puff.geometry.dispose(); v.puff.material.dispose(); game.vanishing.splice(i, 1); }
    }
  }

  for (let i = game.drops.length - 1; i >= 0; i--) {
    const d = game.drops[i];
    d.life -= dt;
    d.mesh.position.y += d.vy * dt;
    d.vy -= 6 * dt;
    if (d.life <= 0) { scene.remove(d.mesh); game.drops.splice(i, 1); }
  }

  if (game.running && !game.over && !game.spawnQueue.length && !game.enemies.length) {
    game.running = false;
    const bonus = 20 + game.wave * 3;
    game.shells += bonus;
    drawHud();
    if (game.wave >= level.target) { finish(true); }
    else {
      toast(`✨ Волна ${game.wave} прошла! Ракушек +${bonus}`);
      say('Молодец!');
      setTimeout(() => { if (!game.over) startWave(); }, 4500);
    }
  }

  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== w * renderer.getPixelRatio() || canvas.height !== h * renderer.getPixelRatio()) {
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  controls.update();
  renderer.render(scene, camera);
}

/* ─────────────────── старт ─────────────────── */

const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder);
const need = [...new Set([level.enemy, ...FRIEND_IDS.map((id) => FRIENDS[id].model)])];
// загрузки: персонажи — по одной модели; замок — все 4 стадии сразу (в кэш, чтобы смена была мгновенной)
const jobs = [];
for (const kind of need) {
  if (MODELS[kind].stages) {
    MODELS[kind].stages.forEach((url, i) => jobs.push({ url, ok: (gltf) => { castleStages[i] = gltf.scene; } }));
  } else {
    jobs.push({ url: MODELS[kind].url, ok: (gltf) => {
      const size = new THREE.Box3().setFromObject(gltf.scene).getSize(new THREE.Vector3());
      loaded[kind] = { gltf, scale: MODELS[kind].height / Math.max(0.001, size.y) };
    } });
  }
}

let done = 0;
for (const job of jobs) {
  loader.load(job.url, (gltf) => {
    job.ok(gltf);
    if (++done === jobs.length) begin();
  }, (e) => {
    if (e.lengthComputable) $('load-bar').style.width = Math.round((e.loaded / e.total) * 100) + '%';
  }, (err) => {
    console.error(err);
    $('load-text').textContent = 'Не получилось загрузить модель :(';
  });
}

function begin() {
  $('loading').classList.add('done');

  // подгоняем камеру под размах маршрута: на каждом уровне он свой
  const box = new THREE.Box3();
  for (const p of [...PATH, ...SPOTS]) box.expandByPoint(p);
  const c = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  // вписываем маршрут целиком: и по ширине экрана, и по высоте
  const aspect = Math.max(0.6, canvas.clientWidth / Math.max(1, canvas.clientHeight));
  const half = Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2);
  const dist = Math.max(size.z * 0.62 / half, size.x * 0.62 / (half * aspect)) + 14;
  controls.target.copy(c);
  camera.position.set(c.x, dist * 0.66, c.z + dist * 0.75);
  controls.maxDistance = dist * 1.8;
  scene.fog.near = dist * 1.2;          // дымка только за краем острова, поле остаётся чистым
  scene.fog.far = dist * 2.8;
  drawHud();
  toast(`${level.name}: ${level.hint}`, 6000);
  say(level.speech || `Уровень ${level.id}. ${level.name}. Ставь подружек на плиты!`);
  tick();
  // для автопроверок (скриншоты стадий замка): play.html?level=castle&debug=1
  if (new URLSearchParams(location.search).has('debug')) {
    window.__td = { game, camera, controls, SPOTS, spotMeshes, build, upgrade, showBuild };
  }
}
