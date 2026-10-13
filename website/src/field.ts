import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

/** A perception lens: continuous optical bands, a scan arc and the original mark. */
export function mountField(canvas: HTMLCanvasElement, isPaused: () => boolean) {
  const host = canvas.parentElement!;
  let renderer: THREE.WebGLRenderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, powerPreference: 'low-power' });
  } catch { return; }
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(35, 1, .1, 30);
  camera.position.set(0, 0, 9.8);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;

  const room = new RoomEnvironment();
  const generator = new THREE.PMREMGenerator(renderer);
  const environment = generator.fromScene(room, .04);
  scene.environment = environment.texture;
  room.dispose();
  generator.dispose();

  const lens = new THREE.Group();
  lens.rotation.set(.19, -.28, -.12);
  lens.scale.setScalar(.92);
  lens.position.y = .2;
  scene.add(lens);
  const time = { value: 0 };
  const opticalMaterial = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, side: THREE.DoubleSide,
    uniforms: { uTime: time, uTeal: { value: new THREE.Color('#236874') }, uSoft: { value: new THREE.Color('#e7f2f2') }, uPeach: { value: new THREE.Color('#f0c6b2') } },
    vertexShader: `varying vec2 vUv; void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
    fragmentShader: `
      varying vec2 vUv;
      uniform float uTime;
      uniform vec3 uTeal, uSoft, uPeach;
      void main() {
        vec2 p = (vUv - .5) * 5.4;
        float r = length(p);
        float a = atan(p.y, p.x);
        float mask = smoothstep(.86, .96, r) * (1.0 - smoothstep(2.12, 2.25, r));
        float refraction = .5 + .5 * sin(r * 32.0 - uTime * 1.2 + sin(a * 3.0) * .65);
        float bands = pow(refraction, 3.0);
        float scan = pow(.5 + .5 * cos(a - uTime * .19), 8.0);
        float sound = exp(-pow((r - 1.52 - .055 * sin(a * 22.0 - uTime * 1.6)) * 25.0, 2.0));
        float phase = fract(uTime * .15);
        float touch = exp(-pow((r - .96 - phase * 1.2) * 24.0, 2.0)) * sin(phase * 3.14159);
        vec2 tileA = abs(p - vec2(-1.1, -1.2)) - .15;
        vec2 tileB = abs(p - vec2(-.72, -1.2)) - .15;
        float visual = max(1.0 - smoothstep(-.008, .012, max(tileA.x, tileA.y)), 1.0 - smoothstep(-.008, .012, max(tileB.x, tileB.y)));
        float halo = exp(-pow((r - 2.13) * 4.0, 2.0)) * .2;
        vec3 color = mix(uTeal, uSoft, .06 + bands * .24 + sound * .14);
        color = mix(color, uPeach, scan * .75 + touch * .3);
        color = mix(color, uPeach, visual * (.85 + .1 * sin(uTime * 1.4)));
        float alpha = mask * (.72 + bands * .2 + sound * .05 + touch * .08);
        gl_FragColor = vec4(color, max(alpha, halo));
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }
    `,
  });
  const opticalGlass = new THREE.Mesh(new THREE.PlaneGeometry(5.4, 5.4), opticalMaterial);
  opticalGlass.position.z = -.16;
  lens.add(opticalGlass);

  const enamel = new THREE.MeshPhysicalMaterial({ color: '#236874', emissive: '#236874', emissiveIntensity: .12, metalness: .35, roughness: .24, clearcoat: 1, envMapIntensity: 1.2 });
  const porcelain = new THREE.MeshPhysicalMaterial({ color: '#fbf7f1', metalness: .08, roughness: .3, clearcoat: 1 });
  const silver = new THREE.MeshPhysicalMaterial({ color: '#e7f2f2', metalness: .72, roughness: .18, envMapIntensity: 1.1 });
  const peach = new THREE.MeshPhysicalMaterial({ color: '#f0c6b2', metalness: .3, roughness: .24, clearcoat: 1 });
  const fineLine = new THREE.LineBasicMaterial({ color: '#236874', transparent: true, opacity: .28 });

  function ring(radius: number, tube: number, z: number, material: THREE.Material) {
    const mesh = new THREE.Mesh(new THREE.TorusGeometry(radius, tube, 16, 192), material);
    mesh.position.z = z;
    lens.add(mesh);
    return mesh;
  }
  ring(2.13, .065, -.15, enamel);
  ring(2.23, .017, -.22, silver);
  ring(1.75, .021, -.02, silver);
  ring(1.26, .018, .05, enamel);
  ring(.92, .031, .1, silver);

  const core = new THREE.Mesh(new THREE.CylinderGeometry(.86, .89, .15, 96), porcelain);
  core.rotation.x = Math.PI / 2;
  core.position.z = .13;
  lens.add(core);
  ring(.885, .021, .19, peach);

  // Etched index marks are connected geometry, not a cloud of points.
  const marks: number[] = [];
  for (let i = 0; i < 96; i++) {
    const angle = i / 96 * Math.PI * 2;
    const inner = i % 8 === 0 ? 2.36 : 2.39;
    for (const radius of [inner, 2.44]) marks.push(Math.cos(angle) * radius, Math.sin(angle) * radius, -.24);
  }
  lens.add(new THREE.LineSegments(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(marks, 3)), fineLine));
  lens.add(new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(Array.from({ length: 192 }, (_, i) => {
    const angle = i / 192 * Math.PI * 2;
    return new THREE.Vector3(Math.cos(angle) * 2.55, Math.sin(angle) * 2.55, -.26);
  })), fineLine));

  const scan = new THREE.Group();
  const arc = new THREE.Mesh(new THREE.TorusGeometry(2.27, .042, 12, 100, Math.PI * .65), peach);
  arc.position.z = -.05;
  scan.add(arc);
  const echo = new THREE.Mesh(new THREE.TorusGeometry(1.74, .03, 12, 80, Math.PI * .42), enamel);
  echo.rotation.z = Math.PI * 1.15;
  echo.position.z = .02;
  scan.add(echo);
  lens.add(scan);

  scene.add(new THREE.AmbientLight('#ffffff', .9));
  const key = new THREE.DirectionalLight('#ffffff', 3);
  key.position.set(-4, 5, 6);
  scene.add(key);
  const fill = new THREE.DirectionalLight('#f0c6b2', 1.7);
  fill.position.set(4, -3, 4);
  scene.add(fill);

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
  let disposed = false;
  function queueRender() {
    if (disposed || frame || !visible || document.hidden || contextLost || (isPaused() && pausedFrameDrawn)) return;
    frame = requestAnimationFrame(render);
  }
  function render(now: number) {
    frame = 0;
    const delta = Math.min((now - previous) / 1000, .05);
    previous = now;
    if (!visible || document.hidden || contextLost || disposed) return;
    const paused = isPaused();
    if (paused && pausedFrameDrawn) return;
    pausedFrameDrawn = paused;
    if (!paused) {
      elapsed += delta;
      const response = 1 - Math.exp(-delta * 3);
      eased.x += (pointer.x - eased.x) * response;
      eased.y += (pointer.y - eased.y) * response;
      time.value = elapsed;
      scan.rotation.z = elapsed * .19 - Math.PI * .2;
      lens.rotation.x = .19 + eased.y * .12 + Math.sin(elapsed * .22) * .035;
      lens.rotation.y = -.28 + eased.x * .17 + Math.sin(elapsed * .16) * .04;
      lens.position.y = .2 + Math.sin(elapsed * .32) * .055;
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
    // Preserve the full lens, including its outer index marks, on narrow screens.
    camera.position.z = Math.max(9.8, 9.1 / camera.aspect);
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

  const markMaterial = new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, toneMapped: false });
  const mark = new THREE.Mesh(new THREE.PlaneGeometry(1.2, 1.16), markMaterial);
  mark.position.z = .22;
  lens.add(mark);
  const markTexture = new THREE.TextureLoader().load(`${import.meta.env.BASE_URL}images/brand-mark.png`, (texture) => {
    if (disposed) return;
    texture.colorSpace = THREE.SRGBColorSpace;
    markMaterial.map = texture;
    markMaterial.needsUpdate = true;
    pausedFrameDrawn = false;
    renderer.render(scene, camera);
    host.classList.add('field-ready');
    queueRender();
  }, undefined, () => { /* Keep the static lens and original mark visible. */ });
  queueRender();

  return () => {
    disposed = true;
    cancelAnimationFrame(frame);
    observer.disconnect();
    resizeObserver.disconnect();
    motionObserver.disconnect();
    host.removeEventListener('pointermove', onPointerMove);
    host.removeEventListener('pointerleave', onPointerLeave);
    document.removeEventListener('visibilitychange', onVisibility);
    canvas.removeEventListener('webglcontextlost', onContextLost);
    canvas.removeEventListener('webglcontextrestored', onContextRestored);
    scene.traverse((object) => { if (object instanceof THREE.Mesh || object instanceof THREE.Line) object.geometry.dispose(); });
    [opticalMaterial, enamel, porcelain, silver, peach, fineLine, markMaterial].forEach((material) => material.dispose());
    markTexture.dispose();
    environment.dispose();
    renderer.dispose();
  };
}
