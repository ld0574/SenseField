import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

/** Sweep a rounded, solid ribbon along a smooth wave. No points or particles. */
function ribbonGeometry(phase: number, width: number, amplitude: number, offset: number) {
  const steps = 192;
  const sides = 16;
  const vertices: number[] = [];
  const indices: number[] = [];
  function center(u: number) {
    const angle = u * Math.PI * 2 + phase;
    return new THREE.Vector3((u - .5) * 6.3, Math.sin(angle) * amplitude + offset, Math.cos(angle) * .85);
  }
  for (let step = 0; step <= steps; step++) {
    const u = step / steps;
    const point = center(u);
    const tangent = center(u + .001).sub(center(u - .001)).normalize();
    const roll = -.28 + Math.sin(u * Math.PI * 2 + phase) * .55;
    const across = new THREE.Vector3(0, Math.cos(roll), Math.sin(roll));
    across.addScaledVector(tangent, -across.dot(tangent)).normalize();
    const normal = new THREE.Vector3().crossVectors(tangent, across).normalize();
    const halfWidth = width * (.3 + .7 * Math.sin(Math.PI * u) ** .7) / 2;
    for (let side = 0; side < sides; side++) {
      const angle = side / sides * Math.PI * 2;
      const cos = Math.cos(angle);
      const sin = Math.sin(angle);
      const acrossDistance = Math.sign(cos) * Math.abs(cos) ** .4 * halfWidth;
      const depth = Math.sign(sin) * Math.abs(sin) ** .4 * .035;
      const vertex = point.clone().addScaledVector(across, acrossDistance).addScaledVector(normal, depth);
      vertices.push(vertex.x, vertex.y, vertex.z);
      if (step < steps) {
        const a = step * sides + side;
        const b = step * sides + (side + 1) % sides;
        indices.push(a, b, b + sides, a, b + sides, a + sides);
      }
    }
  }
  const start = vertices.length / 3;
  const end = start + 1;
  vertices.push(...center(0).toArray(), ...center(1).toArray());
  for (let side = 0; side < sides; side++) {
    indices.push(start, (side + 1) % sides, side);
    indices.push(end, steps * sides + side, steps * sides + (side + 1) % sides);
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
  geometry.setIndex(indices);
  geometry.computeVertexNormals();
  return geometry;
}

/** Continuous acoustic ribbons, with gentle breathing and damped pointer response. */
export function mountField(canvas: HTMLCanvasElement, isPaused: () => boolean) {
  const host = canvas.parentElement!;
  let renderer: THREE.WebGLRenderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, powerPreference: 'low-power' });
  } catch { return; }
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(34, 1, .1, 40);
  camera.position.set(0, 0, 11.7);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;

  const environment = new RoomEnvironment();
  const generator = new THREE.PMREMGenerator(renderer);
  const environmentTarget = generator.fromScene(environment, .04);
  scene.environment = environmentTarget.texture;
  environment.dispose();
  generator.dispose();

  const sculpture = new THREE.Group();
  sculpture.rotation.set(.22, -.28, -.48);
  scene.add(sculpture);
  const timeUniform = { value: 0 };
  const teal = new THREE.MeshPhysicalMaterial({
    color: '#236874', metalness: .22, roughness: .24, clearcoat: 1, clearcoatRoughness: .16,
    envMapIntensity: .9, side: THREE.DoubleSide,
  });
  const peach = new THREE.MeshPhysicalMaterial({
    color: '#f0c6b2', metalness: .18, roughness: .27, clearcoat: 1, clearcoatRoughness: .2,
    envMapIntensity: .8, side: THREE.DoubleSide,
  });
  for (const material of [teal, peach]) {
    material.onBeforeCompile = (shader) => {
      shader.uniforms.uRibbonTime = timeUniform;
      shader.vertexShader = shader.vertexShader.replace('#include <common>', '#include <common>\nuniform float uRibbonTime;');
      shader.vertexShader = shader.vertexShader.replace('#include <begin_vertex>', `
        #include <begin_vertex>
        transformed.y += sin(position.x * 1.1 + uRibbonTime * .4) * .045;
        transformed.z += cos(position.x * .8 + uRibbonTime * .3) * .045;
      `);
    };
  }
  sculpture.add(new THREE.Mesh(ribbonGeometry(0, 1.13, 1.25, .16), teal));
  sculpture.add(new THREE.Mesh(ribbonGeometry(Math.PI, .96, 1.2, -.16), teal));
  sculpture.add(new THREE.Mesh(ribbonGeometry(Math.PI * .5, .38, 1.52, 0), peach));

  scene.add(new THREE.AmbientLight('#ffffff', .65));
  const key = new THREE.DirectionalLight('#ffffff', 3.2);
  key.position.set(-3, 5, 6);
  scene.add(key);
  const fill = new THREE.DirectionalLight('#f0c6b2', 1.7);
  fill.position.set(4, -2, 3);
  scene.add(fill);
  const rim = new THREE.DirectionalLight('#e7f2f2', 2.4);
  rim.position.set(2, 4, -3);
  scene.add(rim);

  const shadowCanvas = document.createElement('canvas');
  shadowCanvas.width = shadowCanvas.height = 128;
  const context = shadowCanvas.getContext('2d')!;
  const gradient = context.createRadialGradient(64, 64, 0, 64, 64, 64);
  gradient.addColorStop(0, 'rgba(25,49,57,.14)');
  gradient.addColorStop(1, 'rgba(25,49,57,0)');
  context.fillStyle = gradient;
  context.fillRect(0, 0, 128, 128);
  const shadowTexture = new THREE.CanvasTexture(shadowCanvas);
  const shadowMaterial = new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, depthWrite: false });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(6.7, 1.25), shadowMaterial);
  shadow.position.set(.25, -2.9, -1.7);
  scene.add(shadow);

  const pointer = { x: 0, y: 0 };
  const eased = { x: 0, y: 0 };
  const onPointerMove = (event: PointerEvent) => {
    if (event.pointerType !== 'mouse') return;
    const bounds = host.getBoundingClientRect();
    pointer.x = THREE.MathUtils.clamp((event.clientX - bounds.left) / bounds.width * 2 - 1, -1, 1);
    pointer.y = THREE.MathUtils.clamp((event.clientY - bounds.top) / bounds.height * 2 - 1, -1, 1);
  };
  const onPointerLeave = () => { pointer.x = pointer.y = 0; };
  host.addEventListener('pointermove', onPointerMove);
  host.addEventListener('pointerleave', onPointerLeave);

  let visible = true;
  let frame = 0;
  let elapsed = 0;
  let previous = performance.now();
  let pausedFrameDrawn = false;
  let contextLost = false;
  function queueRender() {
    if (frame || !visible || document.hidden || contextLost || (isPaused() && pausedFrameDrawn)) return;
    frame = requestAnimationFrame(render);
  }
  function render(now: number) {
    frame = 0;
    const delta = Math.min((now - previous) / 1000, .05);
    previous = now;
    if (!visible || document.hidden || contextLost) return;
    const paused = isPaused();
    if (paused && pausedFrameDrawn) return;
    pausedFrameDrawn = paused;
    if (!paused) {
      elapsed += delta;
      const response = 1 - Math.exp(-delta * 3.5);
      eased.x += (pointer.x - eased.x) * response;
      eased.y += (pointer.y - eased.y) * response;
      timeUniform.value = elapsed;
      sculpture.rotation.x = .22 + eased.y * .08;
      sculpture.rotation.y = -.28 + Math.sin(elapsed * .19) * .14 + eased.x * .15;
      sculpture.rotation.z = -.48 + Math.sin(elapsed * .14) * .035;
      sculpture.position.y = Math.sin(elapsed * .42) * .095;
    }
    renderer.render(scene, camera);
    queueRender();
  }
  const resize = () => {
    const bounds = host.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, window.innerWidth < 760 ? 1.5 : 1.75));
    renderer.setSize(bounds.width, bounds.height, false);
    camera.aspect = bounds.width / bounds.height;
    camera.updateProjectionMatrix();
    pausedFrameDrawn = false;
    queueRender();
  };
  const resizeObserver = new ResizeObserver(resize);
  resizeObserver.observe(host);
  resize();
  const observer = new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting;
    if (!visible) { cancelAnimationFrame(frame); frame = 0; }
    else { previous = performance.now(); pausedFrameDrawn = false; queueRender(); }
  });
  observer.observe(host);
  const motionObserver = new MutationObserver(() => { pausedFrameDrawn = false; queueRender(); });
  motionObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  const onVisibility = () => {
    if (document.hidden) { cancelAnimationFrame(frame); frame = 0; }
    else { previous = performance.now(); pausedFrameDrawn = false; queueRender(); }
  };
  const onContextLost = (event: Event) => { event.preventDefault(); contextLost = true; host.classList.remove('field-ready'); cancelAnimationFrame(frame); frame = 0; };
  const onContextRestored = () => { contextLost = false; pausedFrameDrawn = false; host.classList.add('field-ready'); queueRender(); };
  document.addEventListener('visibilitychange', onVisibility);
  canvas.addEventListener('webglcontextlost', onContextLost);
  canvas.addEventListener('webglcontextrestored', onContextRestored);
  renderer.render(scene, camera);
  host.classList.add('field-ready');
  queueRender();
  return () => {
    cancelAnimationFrame(frame);
    observer.disconnect();
    resizeObserver.disconnect();
    motionObserver.disconnect();
    host.removeEventListener('pointermove', onPointerMove);
    host.removeEventListener('pointerleave', onPointerLeave);
    document.removeEventListener('visibilitychange', onVisibility);
    canvas.removeEventListener('webglcontextlost', onContextLost);
    canvas.removeEventListener('webglcontextrestored', onContextRestored);
    scene.traverse((object) => { if (object instanceof THREE.Mesh) object.geometry.dispose(); });
    teal.dispose();
    peach.dispose();
    shadowMaterial.dispose();
    shadowTexture.dispose();
    environmentTarget.dispose();
    renderer.dispose();
  };
}
