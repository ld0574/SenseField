import * as THREE from 'three';

/** GPU-driven particles follow the torus, then spring into an orbital cloud. */
export function createFieldParticles(count: number, pixelRatio: number) {
  const coordinates = new Float32Array(count * 4);
  const randoms = new Float32Array(count * 3);
  const positions = new Float32Array(count * 3);
  let seed = 173;
  const random = () => {
    seed = (seed * 16807) % 2147483647;
    return (seed - 1) / 2147483646;
  };
  for (let i = 0; i < count; i++) {
    const kind = i < count * 0.72 ? 0 : i < count * 0.91 ? 1 : 2;
    coordinates.set([random() * Math.PI * 2, random() * Math.PI * 2, random(), kind], i * 4);
    randoms.set([random(), 1 + random() * 1.7, random()], i * 3);
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute('aField', new THREE.BufferAttribute(coordinates, 4));
  geometry.setAttribute('aRandom', new THREE.BufferAttribute(randoms, 3));
  const uniforms = {
    uTime: { value: 0 },
    uBurst: { value: 0 },
    uPointer: { value: new THREE.Vector2(0, 0) },
    uPointerStrength: { value: 0 },
    uPixelRatio: { value: pixelRatio },
    uPrimary: { value: new THREE.Color('#236874') },
    uInk: { value: new THREE.Color('#193139') },
    uPeach: { value: new THREE.Color('#f0c6b2') },
  };
  const material = new THREE.ShaderMaterial({
    uniforms,
    transparent: true,
    depthWrite: false,
    vertexShader: `
      attribute vec4 aField;
      attribute vec3 aRandom;
      uniform float uTime;
      uniform float uBurst;
      uniform vec2 uPointer;
      uniform float uPointerStrength;
      uniform float uPixelRatio;
      uniform vec3 uPrimary;
      uniform vec3 uInk;
      uniform vec3 uPeach;
      varying vec3 vColor;
      varying float vAlpha;
      void main() {
        float kind = aField.w;
        float theta = aField.x + uTime * (0.14 + kind * 0.075);
        float phi = aField.y + uTime * 0.12 + sin(theta * 3.0 + uTime * 0.6) * 0.14;
        float cloud = kind < 0.5 ? 0.045 : 0.15 + aField.z * 0.58;
        float radius = 2.12 + 0.21 * sin(theta * 3.0 + 0.6);
        float tube = 0.69 + 0.105 * cos(theta * 2.0 + 1.1) + cloud * (aRandom.x * 2.0 - 1.0);
        tube += sin(theta * 7.0 - uTime * 1.6 + phi) * 0.035;
        vec3 normal = vec3(cos(phi) * cos(theta), cos(phi) * sin(theta), sin(phi));
        vec3 p = vec3((radius + tube * cos(phi)) * cos(theta), (radius + tube * cos(phi)) * sin(theta) * 1.04, tube * sin(phi) + 0.28 * sin(theta * 2.0 - 0.5));
        if (kind > 1.5) {
          float orbitRadius = 2.9 + aField.z * 0.7;
          p = vec3(cos(theta) * orbitRadius, sin(theta) * orbitRadius * 0.68, sin(theta + 0.8) * 1.1);
          p += normal * (aRandom.x - 0.5) * 0.08;
        }
        float scatter = uBurst * (0.5 + aRandom.x * 1.4);
        p += normalize(p) * scatter + normal * scatter * 0.7;
        float swirl = uBurst * (aRandom.z - 0.5) * 1.7;
        p.xy = mat2(cos(swirl), -sin(swirl), sin(swirl), cos(swirl)) * p.xy;
        vec4 view = modelViewMatrix * vec4(p, 1.0);
        vec4 clip = projectionMatrix * view;
        vec2 screen = clip.xy / clip.w;
        vec2 away = screen - uPointer;
        float influence = exp(-dot(away, away) * 18.0) * uPointerStrength;
        view.xy += normalize(away + vec2(0.0001)) * influence * 0.5;
        view.z += influence * aRandom.x * 0.75;
        gl_Position = projectionMatrix * view;
        float light = clamp(dot(normalize(normalMatrix * normal), normalize(vec3(-0.5, 0.7, 1.0))), 0.0, 1.0);
        vColor = mix(uInk, uPrimary, 0.28 + light * 0.72);
        float signal = pow(max(0.0, sin(theta * 2.0 - uTime * 1.2 + phi * 0.35)), 14.0);
        vColor = mix(vColor, uPeach, aRandom.z > 0.95 ? 0.9 : signal * 0.38);
        vAlpha = kind < 0.5 ? 0.8 : kind < 1.5 ? 0.25 + aRandom.x * 0.3 : 0.35;
        gl_PointSize = clamp(aRandom.y * uPixelRatio * (13.0 / -view.z), 1.0, 7.0);
      }
    `,
    fragmentShader: `
      varying vec3 vColor;
      varying float vAlpha;
      void main() {
        float distanceToCenter = length(gl_PointCoord - 0.5);
        if (distanceToCenter > 0.5) discard;
        float alpha = (1.0 - smoothstep(0.14, 0.5, distanceToCenter)) * vAlpha;
        gl_FragColor = vec4(vColor, alpha);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }
    `,
  });
  const points = new THREE.Points(geometry, material);
  points.frustumCulled = false;
  points.renderOrder = 2;
  return { points, uniforms, dispose: () => { geometry.dispose(); material.dispose(); } };
}
