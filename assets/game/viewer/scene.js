// Общая сцена для обоих экранов: рендерер, свет, пол, камера, орбита.
// Размеры подобраны под персонажа ростом ~1.7 м; frame() масштабирует их под чужую модель.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

const BASE_HEIGHT = 1.7;

export function createStage(canvas) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0e1014);
  scene.fog = new THREE.Fog(0x0e1014, 8, 22);

  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

  const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 100);
  camera.position.set(2.2, 1.6, 3.4);

  const controls = new OrbitControls(camera, canvas);
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.minDistance = 1.2;
  controls.maxDistance = 12;
  controls.maxPolarAngle = Math.PI * 0.52;
  controls.target.set(0, 0.95, 0);

  // свет
  const key = new THREE.DirectionalLight(0xffffff, 2.4);
  key.position.set(3, 5.5, 3);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  key.shadow.camera.top = key.shadow.camera.right = 3;
  key.shadow.camera.bottom = key.shadow.camera.left = -3;
  key.shadow.camera.near = 0.5;
  key.shadow.camera.far = 14;
  key.shadow.bias = -0.0012;
  key.shadow.normalBias = 0.02;
  scene.add(key);
  scene.add(new THREE.DirectionalLight(0x8fa8ff, 0.5).translateX(-4).translateY(2).translateZ(-3));
  scene.add(new THREE.HemisphereLight(0xcfd8ff, 0x1a1d24, 0.6));

  // пол
  const floor = new THREE.Group();
  const plane = new THREE.Mesh(
    new THREE.PlaneGeometry(60, 60),
    new THREE.MeshStandardMaterial({ color: 0x14171d, roughness: 0.95, metalness: 0 })
  );
  plane.rotation.x = -Math.PI / 2;
  plane.receiveShadow = true;
  const grid = new THREE.GridHelper(40, 40, 0x39414f, 0x22262f);
  grid.material.transparent = true;
  grid.material.opacity = 0.5;
  floor.add(plane, grid);
  scene.add(floor);

  const size = new THREE.Vector3();
  const center = new THREE.Vector3();

  /** Ставит камеру на модель и подгоняет под её габариты свет, туман, пол и пределы орбиты. */
  function frame(obj) {
    const box = new THREE.Box3().setFromObject(obj);
    box.getSize(size);
    box.getCenter(center);
    const h = Math.max(size.y, size.x * 0.6, size.z * 0.6, 1);
    const k = Math.max(1, h / BASE_HEIGHT);   // у персонажа k = 1, сцена остаётся прежней

    key.position.set(3 * k, 5.5 * k, 3 * k);
    key.shadow.camera.top = key.shadow.camera.right = 3 * k;
    key.shadow.camera.bottom = key.shadow.camera.left = -3 * k;
    key.shadow.camera.far = 14 * k;
    key.shadow.camera.updateProjectionMatrix();
    scene.fog.near = 8 * k;
    scene.fog.far = 22 * k;
    floor.scale.setScalar(k);
    camera.far = 100 * k;
    controls.minDistance = 1.2 * k;
    controls.maxDistance = 12 * k;

    controls.target.set(center.x, h * 0.52, center.z);
    camera.position.set(center.x + h * 1.15, h * 0.85, center.z + h * 1.85);
    camera.updateProjectionMatrix();
    controls.update();
  }

  function resize() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width === w * renderer.getPixelRatio() && canvas.height === h * renderer.getPixelRatio()) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }

  return { renderer, scene, camera, controls, floor, frame, resize };
}
