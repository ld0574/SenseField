import * as THREE from 'three';
import { createFieldParticles } from './particles';

/** A ribbed sensory sculpture, rendered locally without model downloads. */
export function mountField(canvas: HTMLCanvasElement, isPaused: () => boolean) {
  const host = canvas.parentElement!;
  let renderer: THREE.WebGLRenderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, powerPreference: 'low-power' });
  } catch {
    return; // The CSS sculpture remains visible when WebGL is unavailable.
  }
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 60);
  camera.position.set(0, 0, 13.8);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, window.innerWidth < 760 ? 1.5 : 1.8));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.28;

  const sculpture = new THREE.Group();
  sculpture.rotation.set(0.54, -0.38, -0.35);
  scene.add(sculpture);

  const geometry = new THREE.TorusGeometry(2.18, 0.65, 64, 640);
  const positions = geometry.attributes.position;
  const uv = geometry.attributes.uv;
  for (let i = 0; i < positions.count; i++) {
    const theta = uv.getX(i) * Math.PI * 2;
    const phi = uv.getY(i) * Math.PI * 2;
    const major = 2.12 + 0.21 * Math.sin(theta * 3 + 0.6);
    const tube = 0.67 + 0.105 * Math.cos(theta * 2 + 1.1) + 0.022 * Math.sin(theta * 180);
    positions.setXYZ(i,
      (major + tube * Math.cos(phi)) * Math.cos(theta),
      (major + tube * Math.cos(phi)) * Math.sin(theta) * 1.04,
      tube * Math.sin(phi) + 0.28 * Math.sin(theta * 2 - 0.5),
    );
  }
  geometry.computeVertexNormals();
  const material = new THREE.MeshStandardMaterial({ color: '#236874', roughness: 0.42, metalness: 0.24, transparent: true, opacity: 0.12, depthWrite: false });
  material.onBeforeCompile = (shader) => {
    shader.vertexShader = shader.vertexShader.replace('#include <common>', '#include <common>\nvarying vec2 vFieldUv;');
    shader.vertexShader = shader.vertexShader.replace('#include <uv_vertex>', '#include <uv_vertex>\nvFieldUv = uv;');
    shader.fragmentShader = shader.fragmentShader.replace('#include <common>', '#include <common>\nvarying vec2 vFieldUv;');
    shader.fragmentShader = shader.fragmentShader.replace('#include <color_fragment>', `
      #include <color_fragment>
      float rib = sin(vFieldUv.x * 1130.973);
      float groove = smoothstep(-0.85, -0.3, rib);
      diffuseColor.rgb *= mix(0.45, 1.1, groove);
      float grain = fract(sin(dot(vFieldUv, vec2(123.34, 456.21))) * 45634.12);
      diffuseColor.rgb *= 0.97 + grain * 0.06;
    `);
  };
  const mainRing = new THREE.Mesh(geometry, material);
  sculpture.add(mainRing);
  const particles = createFieldParticles(window.innerWidth < 760 ? 22000 : 42000, renderer.getPixelRatio());
  sculpture.add(particles.points);

  const innerRingGeo = new THREE.TorusGeometry(1.29, 0.036, 16, 220);
  const peachMaterial = new THREE.MeshStandardMaterial({ color: '#f0c6b2', roughness: 0.3, metalness: 0.35 });
  const innerRing = new THREE.Mesh(innerRingGeo, peachMaterial);
  innerRing.rotation.set(0.6, -0.55, 0.2);
  innerRing.position.z = 0.35;
  sculpture.add(innerRing);

  const outerRingGeo = new THREE.TorusGeometry(3.16, 0.012, 8, 180);
  const outerRing = new THREE.Mesh(outerRingGeo, peachMaterial);
  outerRing.rotation.set(1.15, 0.25, -0.5);
  scene.add(outerRing);

  const satelliteGeo = new THREE.SphereGeometry(0.12, 24, 20);
  const satellite = new THREE.Mesh(satelliteGeo, peachMaterial);
  satellite.position.set(2.9, 1.1, 0.7);
  scene.add(satellite);
  const satelliteSmall = new THREE.Mesh(new THREE.SphereGeometry(0.055, 16, 12), material);
  satelliteSmall.position.set(-2.65, -1.7, 0.4);
  scene.add(satelliteSmall);

  scene.add(new THREE.AmbientLight('#e7f2f2', 2.2));
  const key = new THREE.DirectionalLight('#ffffff', 5.5);
  key.position.set(-3, 5, 6);
  scene.add(key);
  const fill = new THREE.DirectionalLight('#f0c6b2', 2.4);
  fill.position.set(4, -2, 3);
  scene.add(fill);
  const rim = new THREE.DirectionalLight('#c3e5e9', 3.5);
  rim.position.set(2, 4, -4);
  scene.add(rim);

  const shadowCanvas = document.createElement('canvas');
  shadowCanvas.width = 128;
  shadowCanvas.height = 128;
  const ctx = shadowCanvas.getContext('2d')!;
  const gradient = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  gradient.addColorStop(0, 'rgba(25,49,57,.16)');
  gradient.addColorStop(1, 'rgba(25,49,57,0)');
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, 128, 128);
  const shadowTexture = new THREE.CanvasTexture(shadowCanvas);
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(7, 2), new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, depthWrite: false }));
  shadow.position.set(0, -3.35, -1);
  scene.add(shadow);

  const pointer = { x: 0, y: 0, active: 0 };
  const smoothed = { x: 0, y: 0, active: 0 };
  let burst = 0;
  let burstVelocity = 0;
  let burstUntil = 0;
  const burstButton = document.querySelector<HTMLButtonElement>('.field-interact');
  const releaseField = () => {
    if (isPaused()) return;
    burstUntil = performance.now() + 520;
    host.classList.remove('is-bursting');
    host.classList.add('is-bursting');
    if (burstButton) burstButton.setAttribute('aria-label', '再次释放感知场');
  };
  const onPointerMove = (event: PointerEvent) => {
    if (event.pointerType === 'touch') return;
    const bounds = host.getBoundingClientRect();
    pointer.x = Math.max(-1, Math.min(1, ((event.clientX - bounds.left) / bounds.width - 0.5) * 2));
    pointer.y = Math.max(-1, Math.min(1, ((event.clientY - bounds.top) / bounds.height - 0.5) * 2));
    pointer.active = 1;
  };
  const onLeave = () => { pointer.x = 0; pointer.y = 0; pointer.active = 0; };
  host.addEventListener('pointermove', onPointerMove);
  host.addEventListener('pointerleave', onLeave);
  host.addEventListener('click', releaseField);
  burstButton?.addEventListener('click', releaseField);
  let visible = true;
  let frame = 0;
  let elapsed = 0;
  let lastTime = performance.now();
  let drawnPaused = false;
  function queueRender() {
    if (frame || !visible || document.hidden) return;
    if (isPaused() && drawnPaused) return;
    frame = requestAnimationFrame(render);
  }
  const resize = () => {
    const { width, height } = host.getBoundingClientRect();
    if (!width || !height) return;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, window.innerWidth < 760 ? 1.5 : 1.8));
    particles.uniforms.uPixelRatio.value = renderer.getPixelRatio();
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    drawnPaused = false;
    queueRender();
  };
  const resizeObserver = new ResizeObserver(resize);
  resizeObserver.observe(host);
  resize();

  const observer = new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting;
    if (!visible) { cancelAnimationFrame(frame); frame = 0; }
    else { lastTime = performance.now(); drawnPaused = false; queueRender(); }
  }, { rootMargin: '80px' });
  observer.observe(host);

  const motionObserver = new MutationObserver(() => { drawnPaused = false; queueRender(); });
  motionObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  const onVisibility = () => {
    if (document.hidden) { cancelAnimationFrame(frame); frame = 0; }
    else { lastTime = performance.now(); drawnPaused = false; queueRender(); }
  };
  document.addEventListener('visibilitychange', onVisibility);
  function render(time: number) {
    frame = 0;
    const delta = Math.min((time - lastTime) / 1000, 0.05);
    lastTime = time;
    if (!visible || document.hidden) return;
    const paused = isPaused();
    if (paused && drawnPaused) return;
    drawnPaused = paused;
    if (!paused) {
      elapsed += delta;
      smoothed.x += (pointer.x - smoothed.x) * 0.045;
      smoothed.y += (pointer.y - smoothed.y) * 0.045;
      smoothed.active += (pointer.active - smoothed.active) * 0.05;
      const targetBurst = time < burstUntil ? 1 : 0;
      burstVelocity += (targetBurst - burst) * 28 * delta;
      burstVelocity *= Math.exp(-6.2 * delta);
      burst += burstVelocity * delta;
      particles.uniforms.uBurst.value = Math.max(0, burst);
      particles.uniforms.uTime.value = elapsed;
      particles.uniforms.uPointer.value.set(smoothed.x, -smoothed.y);
      particles.uniforms.uPointerStrength.value = smoothed.active;
      material.opacity = Math.max(0, 0.12 * (1 - burst));
      sculpture.rotation.y = -0.38 + smoothed.x * 0.2 + Math.sin(elapsed * 0.2) * 0.08;
      sculpture.rotation.x = 0.54 + smoothed.y * 0.13;
      sculpture.rotation.z = -0.35 + Math.sin(elapsed * 0.15) * 0.035;
      sculpture.position.y = Math.sin(elapsed * 0.55) * 0.08;
      innerRing.rotation.z = elapsed * 0.08;
      outerRing.rotation.z = -0.5 + elapsed * 0.018;
      satellite.position.y = 1.1 + Math.sin(elapsed * 0.5) * 0.16;
    }
    renderer.render(scene, camera);
    queueRender();
  }
  renderer.render(scene, camera);
  host.classList.add('field-ready');
  queueRender();
  return () => {
    cancelAnimationFrame(frame);
    observer.disconnect();
    resizeObserver.disconnect();
    motionObserver.disconnect();
    document.removeEventListener('visibilitychange', onVisibility);
    host.removeEventListener('pointermove', onPointerMove);
    host.removeEventListener('pointerleave', onLeave);
    host.removeEventListener('click', releaseField);
    burstButton?.removeEventListener('click', releaseField);
    scene.traverse((object) => {
      if (object instanceof THREE.Mesh) object.geometry.dispose();
    });
    material.dispose();
    peachMaterial.dispose();
    (shadow.material as THREE.Material).dispose();
    shadowTexture.dispose();
    particles.dispose();
    renderer.dispose();
  };
}
