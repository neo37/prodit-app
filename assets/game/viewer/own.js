import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/addons/loaders/DRACOLoader.js';
import { KTX2Loader } from 'three/addons/loaders/KTX2Loader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { createStage } from './scene.js';

/** Экран «свои модели»: файл читается в браузере, никуда не отправляется. */

const FADE = 0.3;                       // секунд на кроссфейд между клипами
const ROOT_BONE = /^(hips|pelvis|root|bip01|mixamorig:?hips)$/i;

const $ = (id) => document.getElementById(id);
const fmt = (s) => `${s.toFixed(1)} с`;
const mb = (b) => (b >= 1048576 ? (b / 1048576).toFixed(1) + ' МБ' : Math.round(b / 1024) + ' КБ');
const plural = (n, one, few, many) => {
  const a = Math.abs(n) % 100, b = a % 10;
  return `${n} ${a > 10 && a < 20 ? many : b === 1 ? one : b >= 2 && b <= 4 ? few : many}`;
};

/* ───────────────────────── сцена ───────────────────────── */

const canvas = $('scene');
const { renderer, scene, camera, controls, floor, frame, resize } = createStage(canvas);

const loader = new GLTFLoader()
  .setMeshoptDecoder(MeshoptDecoder)
  .setDRACOLoader(new DRACOLoader().setDecoderPath('vendor/three/examples/jsm/libs/draco/gltf/'))
  .setKTX2Loader(new KTX2Loader().setTranscoderPath('vendor/three/examples/jsm/libs/basis/').detectSupport(renderer));

/* ───────────────────────── состояние ───────────────────────── */

const files = [];          // { id, name, size, gltf, nodes:Set<string>, meshes, bones }
const items = new Map();   // ключ «id:имя клипа» → { key, clip, action, name, from, own }
let seq = 0;

let activeId = null;       // файл, чей меш сейчас в сцене
let model = null;
let mixer = null;
let rootBone = null;       // корневая кость — для режима «на месте»
let skeletonHelper = null;

let current = null;        // активный AnimationAction
let currentKey = '';
let speed = 1;
let playing = true;
let inPlace = true;
let queueMode = false;
let scrubbing = false;

const boneWorld = new THREE.Vector3();

/* ───────────────────────── чтение файлов ───────────────────────── */

$('file').addEventListener('change', (e) => { addFiles(e.target.files); e.target.value = ''; });

for (const type of ['dragenter', 'dragover']) {
  addEventListener(type, (e) => { e.preventDefault(); document.body.classList.add('dragging'); });
}
addEventListener('dragleave', (e) => { if (!e.relatedTarget) document.body.classList.remove('dragging'); });
addEventListener('drop', (e) => {
  e.preventDefault();
  document.body.classList.remove('dragging');
  addFiles(e.dataTransfer.files);
});

async function addFiles(list) {
  const picked = [...list].filter((f) => /\.(glb|gltf)$/i.test(f.name));
  const skipped = list.length - picked.length;
  showError(skipped ? `Пропущено файлов не-GLB: ${skipped}` : '');

  for (const file of picked) {
    $('loader').classList.remove('done');
    $('loader-bar').style.width = '0%';
    $('loader-text').textContent = `Читаю ${file.name}…`;
    try {
      await addFile(file);
    } catch (err) {
      console.error(err);
      showError(`${file.name}: не разобрался. ${String(err.message || err).slice(0, 140)}`);
    }
  }
  if (!model) $('loader').classList.add('done');
}

async function addFile(file) {
  const isText = /\.gltf$/i.test(file.name);
  const data = isText ? await file.text() : await file.arrayBuffer();
  $('loader-bar').style.width = '55%';
  $('loader-text').textContent = `Разбираю ${file.name}…`;

  // путь пустой: внешние .bin и текстуры .gltf подтянуть неоткуда, GLB же самодостаточен
  const gltf = await loader.parseAsync(data, '');
  $('loader-bar').style.width = '100%';

  const nodes = new Set();
  let meshes = 0, bones = 0;
  gltf.scene.traverse((o) => {
    if (o.name) nodes.add(o.name);
    if (o.isMesh) meshes++;
    if (o.isBone) bones++;
  });

  const entry = { id: ++seq, name: file.name, size: file.size, gltf, nodes, meshes, bones };
  files.push(entry);

  if (!model && meshes) install(entry);         // первый файл с мешем становится моделью
  else { buildActions(); buildFiles(); buildList($('search').value); }

  if (!model && !meshes && files.length === 1) {
    showError(`${file.name}: мешей нет, только анимации — добавь файл с моделью.`);
  }
}

function showError(text) {
  const el = $('drop-error');
  el.textContent = text;
  el.hidden = !text;
}

/* ───────────────────────── установка модели ───────────────────────── */

function install(entry) {
  if (model) {
    scene.remove(model);
    if (skeletonHelper) scene.remove(skeletonHelper);
    mixer.stopAllAction();
    current = null;
    currentKey = '';
  }

  activeId = entry.id;
  model = entry.gltf.scene;
  model.position.set(0, 0, 0);
  rootBone = null;
  model.traverse((o) => {
    if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; o.frustumCulled = false; }
    if (o.isBone && !rootBone && ROOT_BONE.test(o.name)) rootBone = o;
  });
  if (!rootBone) {                                  // имена нестандартные — берём верхнюю кость скелета
    model.traverse((o) => { if (o.isSkinnedMesh && !rootBone) rootBone = o.skeleton.bones[0]; });
  }
  scene.add(model);

  mixer = new THREE.AnimationMixer(model);
  skeletonHelper = new THREE.SkeletonHelper(model);
  skeletonHelper.visible = $('btn-skeleton').getAttribute('aria-pressed') === 'true';
  skeletonHelper.material.linewidth = 2;
  scene.add(skeletonHelper);

  buildActions();
  buildFiles();
  frame(model);
  buildList($('search').value);
  $('drop').classList.add('hide');
  $('loader').classList.add('done');

  const first = items.keys().next();
  if (!first.done) play(first.value, 0);
  else {
    $('np-name').textContent = entry.name;
    $('np-meta').textContent = 'в файле нет анимаций';
    $('t-dur').textContent = fmt(0);
  }
}

/**
 * Клип из другого файла ляжет на текущий скелет, если дорожки метят в кости с теми же именами
 * (так же по именам сшивает tools/merge-anims.mjs). Считаем долю попавших дорожек.
 */
function clipFit(clip, nodes) {
  if (!clip.tracks.length) return 0;
  let hit = 0;
  for (const t of clip.tracks) {
    const parsed = THREE.PropertyBinding.parseTrackName(t.name);
    if (nodes.has(parsed.nodeName)) hit++;
  }
  return hit / clip.tracks.length;
}

function buildActions() {
  items.clear();
  if (!mixer) return;
  const active = files.find((f) => f.id === activeId);

  for (const f of files) {
    f.fit = 0;
    for (const clip of f.gltf.animations) {
      const own = f.id === activeId;
      if (!own && clipFit(clip, active.nodes) < 0.5) continue;   // кости названы иначе — клип не подставить
      f.fit++;
      const key = `${f.id}:${clip.name}`;
      const action = mixer.clipAction(clip);
      action.clampWhenFinished = false;
      items.set(key, { key, clip, action, name: clip.name, from: f, own });
    }
  }
  $('clip-count').textContent = files.length
    ? `${plural(files.length, 'файл', 'файла', 'файлов')} · ${plural(items.size, 'анимация', 'анимации', 'анимаций')}`
    : 'файлы никуда не уходят — всё читается в браузере';
}

/* ───────────────────────── список файлов ───────────────────────── */

function buildFiles() {
  const host = $('files');
  host.textContent = '';
  for (const f of files) {
    const row = document.createElement('div');
    row.className = 'file';
    row.setAttribute('aria-current', String(f.id === activeId));

    const main = document.createElement('button');
    main.className = 'file-main';
    main.title = f.meshes ? 'Показать эту модель' : 'В файле нет мешей — только источник анимаций';
    main.disabled = !f.meshes;
    main.innerHTML = '<span class="fname"></span><span class="fmeta"></span>';
    main.querySelector('.fname').textContent = f.name;
    const total = f.gltf.animations.length;
    main.querySelector('.fmeta').textContent = [
      mb(f.size),
      f.meshes ? plural(f.meshes, 'меш', 'меша', 'мешей') : 'без меша',
      f.bones ? plural(f.bones, 'кость', 'кости', 'костей') : 'без скелета',
      f.id === activeId || !total || f.fit === total
        ? plural(total, 'клип', 'клипа', 'клипов')
        : `подошло ${f.fit} из ${total} клипов`,
    ].join(' · ');
    main.addEventListener('click', () => { if (f.id !== activeId) install(f); });

    const del = document.createElement('button');
    del.className = 'file-del';
    del.title = 'Убрать файл';
    del.textContent = '✕';
    del.addEventListener('click', () => removeFile(f));

    row.append(main, del);
    host.append(row);
  }
}

function removeFile(f) {
  files.splice(files.indexOf(f), 1);
  if (f.id === activeId) {
    scene.remove(model);
    if (skeletonHelper) scene.remove(skeletonHelper);
    mixer.stopAllAction();
    model = null; mixer = null; current = null; currentKey = ''; activeId = null;
    const next = files.find((x) => x.meshes);
    if (next) { install(next); return; }
    $('drop').classList.remove('hide');
    $('np-name').textContent = '—';
    $('np-meta').textContent = '';
  }
  buildActions();
  buildFiles();
  buildList($('search').value);
}

/* ───────────────────────── список анимаций ───────────────────────── */

function buildList(filter = '') {
  const q = filter.trim().toLowerCase();
  const host = $('clip-list');
  host.textContent = '';
  let shown = 0;

  for (const f of files) {
    const hits = [...items.values()].filter((it) => it.from === f
      && (!q || (it.name + ' ' + f.name).toLowerCase().includes(q)));
    if (!hits.length) continue;

    const sec = document.createElement('section');
    sec.className = 'cat';
    const h = document.createElement('h2');
    h.textContent = f.id === activeId ? '🎞 этот файл' : `↪ ${f.name}`;
    sec.append(h);

    for (const it of hits) {
      const b = document.createElement('button');
      b.className = 'clip';
      b.dataset.clip = it.key;
      b.setAttribute('aria-current', String(it.key === currentKey));
      b.innerHTML = '<span class="name"></span><span class="dur"></span>';
      b.querySelector('.name').textContent = it.name;
      b.querySelector('.dur').textContent = fmt(it.clip.duration);
      b.addEventListener('click', () => play(it.key));
      sec.append(b);
      shown++;
    }
    host.append(sec);
  }

  if (!shown) {
    const p = document.createElement('p');
    p.className = 'empty';
    p.textContent = files.length ? 'Ничего не найдено' : 'Пока пусто: перетащи .glb в окно';
    host.append(p);
  }
}

/* ───────────────────── переключение анимации ───────────────────── */

function play(key, fade = FADE) {
  const it = items.get(key);
  if (!it || it.action === current) return;
  const next = it.action;

  next.reset();
  next.setEffectiveTimeScale(speed);
  next.setEffectiveWeight(1);
  next.setLoop(THREE.LoopRepeat, Infinity);
  next.paused = false;
  next.play();
  if (fade > 0) next.fadeIn(fade);

  if (current) { if (fade > 0) current.fadeOut(fade); else current.stop(); }

  current = next;
  currentKey = key;
  if (!playing) togglePlay(true);

  $('np-name').textContent = it.name;
  $('np-meta').textContent = [it.own ? it.from.name : `из ${it.from.name}`, fmt(it.clip.duration)].join(' · ');
  $('t-dur').textContent = fmt(it.clip.duration);

  for (const el of document.querySelectorAll('.clip')) {
    el.setAttribute('aria-current', String(el.dataset.clip === key));
  }
  const active = document.querySelector('.clip[aria-current="true"]');
  if (active) active.scrollIntoView({ block: 'nearest' });
}

function step(delta) {
  const list = [...items.keys()];
  if (!list.length) return;
  const i = list.indexOf(currentKey);
  play(list[(i + delta + list.length) % list.length]);
}

/* ───────────────────── управление ───────────────────── */

function togglePlay(force) {
  playing = force === undefined ? !playing : force;
  if (current) current.paused = !playing;
  $('btn-play').textContent = playing ? '⏸' : '▶';
}

$('btn-play').addEventListener('click', () => togglePlay());

$('speed').addEventListener('input', (e) => {
  speed = e.target.value / 100;
  $('speed-val').textContent = speed.toFixed(1) + '×';
  if (current) current.setEffectiveTimeScale(speed);
});

const seek = $('seek');
seek.addEventListener('pointerdown', () => { scrubbing = true; });
seek.addEventListener('pointerup', () => { scrubbing = false; });
seek.addEventListener('input', () => {
  if (!current) return;
  current.time = (seek.value / 1000) * items.get(currentKey).clip.duration;
  mixer.update(0);
});

$('search').addEventListener('input', (e) => {
  $('search-clear').hidden = !e.target.value;
  buildList(e.target.value);
});
$('search-clear').addEventListener('click', () => {
  $('search').value = '';
  $('search-clear').hidden = true;
  buildList();
  $('search').focus();
});

$('btn-random').addEventListener('click', () => {
  const list = [...items.keys()].filter((k) => k !== currentKey);
  if (list.length) play(list[Math.floor(Math.random() * list.length)]);
});

$('btn-queue').addEventListener('click', (e) => {
  queueMode = !queueMode;
  e.currentTarget.setAttribute('aria-pressed', String(queueMode));
  e.currentTarget.textContent = queueMode ? '⏹ Стоп' : '▶ Всё подряд';
});

const toggle = (id, fn, init) => {
  const b = $(id);
  let on = init;
  b.setAttribute('aria-pressed', String(on));
  b.addEventListener('click', () => { on = !on; b.setAttribute('aria-pressed', String(on)); fn(on); });
};
toggle('btn-inplace', (on) => { inPlace = on; }, true);
toggle('btn-rotate', (on) => { controls.autoRotate = on; controls.autoRotateSpeed = 1.1; }, false);
toggle('btn-grid', (on) => { floor.visible = on; }, true);
toggle('btn-skeleton', (on) => { if (skeletonHelper) skeletonHelper.visible = on; }, false);

$('btn-full').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen();
});

addEventListener('keydown', (e) => {
  if (e.target.matches('input, textarea')) return;
  if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
  else if (e.code === 'ArrowRight') step(1);
  else if (e.code === 'ArrowLeft') step(-1);
  else if ((e.key === 'r' || e.key === 'R' || e.key === 'к' || e.key === 'К') && model) frame(model);
});

/* ───────────────────── цикл ───────────────────── */

const timer = new THREE.Timer();
let queueElapsed = 0;

renderer.setAnimationLoop(() => {
  timer.update();
  const dt = Math.min(timer.getDelta(), 0.1);
  resize();

  if (mixer) {
    mixer.update(playing ? dt : 0);

    // держим модель в центре кадра: гасим смещение корневой кости
    if (inPlace && rootBone && model) {
      rootBone.getWorldPosition(boneWorld);
      model.position.x -= boneWorld.x;
      model.position.z -= boneWorld.z;
    }

    if (current && !scrubbing) {
      const dur = items.get(currentKey).clip.duration;
      const t = dur ? current.time % dur : 0;
      seek.value = Math.round(dur ? (t / dur) * 1000 : 0);
      $('t-cur').textContent = fmt(t);
    }

    if (queueMode && playing && current) {
      queueElapsed += dt * speed;
      if (queueElapsed >= items.get(currentKey).clip.duration) { queueElapsed = 0; step(1); }
    } else {
      queueElapsed = 0;
    }
  }

  controls.update();
  renderer.render(scene, camera);
});

buildList();
