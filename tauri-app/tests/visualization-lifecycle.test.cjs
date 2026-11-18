const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

function load(relative, imports, globals = {}, inspect = '') {
  let source = readFileSync(join(__dirname, '../src', relative), 'utf8');
  if (relative.endsWith('.vue')) source = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  const module = { exports: {} };
  vm.runInContext(code + '\n' + inspect, vm.createContext({
    module, exports: module.exports, console, setTimeout, clearTimeout, setInterval, clearInterval,
    require(name) {
      assert.ok(Object.hasOwn(imports, name), `unexpected import: ${name}`);
      return imports[name];
    },
    ...globals,
  }));
  return module.exports;
}

function lifecycle() {
  const mounted = [], disposed = [], watchers = [];
  const vue = {
    ref: (value) => ({ value }),
    computed: (getter) => ({ get value() { return getter(); } }),
    onMounted: (fn) => mounted.push(fn),
    onUnmounted: (fn) => disposed.push(fn),
    onScopeDispose: (fn) => disposed.push(fn),
    watch(source, callback, options) {
      const run = () => callback(Array.isArray(source) ? source.map((get) => get()) : source());
      watchers.push(run);
      if (options?.immediate) run();
    },
  };
  return { vue, mount: () => mounted.forEach((fn) => fn()), change: () => watchers.forEach((fn) => fn()), dispose: () => disposed.forEach((fn) => fn()) };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('visual activity follows native hide/minimize and document visibility without polling', async () => {
  const life = lifecycle();
  const native = { visible: true, minimized: false };
  const handlers = new Map(), documentHandlers = new Map();
  const hidden = life.vue.ref(false);
  let queries = 0;
  const doc = {
    visibilityState: 'visible',
    addEventListener: (name, fn) => documentHandlers.set(name, fn),
    removeEventListener: (name) => documentHandlers.delete(name),
  };
  const { useListeners } = load('shared/lib/listeners.ts', { vue: life.vue });
  const { useVisualActivity } = load('features/window/composables/useVisualActivity.ts', {
    vue: life.vue,
    '@/shared/lib/listeners': { useListeners },
    '@/platform': { appWindow: {
      isVisible: async () => { queries++; return native.visible; },
      isMinimized: async () => native.minimized,
      onFocusChanged: async (fn) => { handlers.set('focus', fn); return () => handlers.delete('focus'); },
      onResized: async (fn) => { handlers.set('resize', fn); return () => handlers.delete('resize'); },
    } },
  }, { document: doc });
  const active = useVisualActivity(hidden);
  life.mount();
  await settle();
  assert.equal(active.value, true);
  assert.equal(queries, 1);
  hidden.value = true;
  assert.equal(active.value, false);
  hidden.value = false;
  native.minimized = true;
  handlers.get('resize')();
  await settle();
  assert.equal(active.value, false);
  native.minimized = false;
  handlers.get('focus')(true);
  await settle();
  assert.equal(active.value, true);
  native.visible = false;
  handlers.get('focus')(false);
  await settle();
  assert.equal(active.value, false);
  native.visible = true;
  doc.visibilityState = 'hidden';
  documentHandlers.get('visibilitychange')();
  await settle();
  assert.equal(active.value, false);
  doc.visibilityState = 'visible';
  documentHandlers.get('visibilitychange')();
  await settle();
  assert.equal(active.value, true);
  life.dispose();
  assert.equal(handlers.size, 0);
  assert.equal(documentHandlers.size, 0);
});

test('hidden audio UI drops only visual updates, not mute or monitoring changes', async () => {
  const life = lifecycle();
  const handlers = new Map();
  const { useListeners } = load('shared/lib/listeners.ts', { vue: life.vue });
  let active = true;
  const { useAudio } = load('features/audio/composables/useAudio.ts', {
    vue: life.vue,
    '@vueuse/core': { useStorage: (_key, value) => life.vue.ref(value) },
    '@/shared/lib/listeners': { useListeners },
    '@/platform': {
      command: async () => {},
      onEvent: async (name, fn) => { handlers.set(name, fn); return () => handlers.delete(name); },
    },
  });
  const audio = useAudio(() => active);
  life.mount();
  await settle();
  handlers.get('audio-level')(20);
  handlers.get('audio-metrics')({ latencyMs: 10, bitrate: 32000 });
  active = false;
  handlers.get('audio-level')(90);
  handlers.get('audio-metrics')({ latencyMs: 50, bitrate: 32000 });
  assert.equal(audio.audioLevel.value, 20);
  assert.equal(audio.audioMetrics.value.latencyMs, 10);
  handlers.get('mute-state-changed')(true);
  handlers.get('monitoring-enabled-changed')(true);
  assert.equal(audio.isMuted.value, true);
  assert.equal(audio.isMonitoringEnabled.value, true);
  active = true;
  handlers.get('audio-level')(40);
  assert.equal(audio.audioLevel.value, 40);
  life.dispose();
  assert.equal(handlers.size, 0);
});

test('audio ring is capped at 33ms and cancels its timer while hidden and on disposal', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const life = lifecycle();
  const props = { level: 100, active: true };
  const ring = load('features/audio/components/AudioRing.vue', { vue: life.vue }, { defineProps: () => props },
    'module.exports.level = () => smoothedLevel.value;');
  t.mock.timers.tick(32);
  assert.equal(ring.level(), 0);
  t.mock.timers.tick(1);
  assert.equal(ring.level(), 0.12);
  props.active = false;
  life.change();
  t.mock.timers.tick(1000);
  assert.equal(ring.level(), 0.12);
  props.level = 10;
  props.active = true;
  life.change();
  t.mock.timers.tick(33);
  assert.ok(ring.level() < 0.12);
  const beforeDispose = ring.level();
  life.dispose();
  t.mock.timers.tick(1000);
  assert.equal(ring.level(), beforeDispose);
});

test('monitor waveform pauses without erasing history, resumes, and stops on unmount', (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const life = lifecycle();
  const props = { serverState: 'streaming', audioLevel: 80, metrics: null, active: true };
  const panel = load('features/audio/components/MonitoringPanel.vue', { vue: life.vue, '@lucide/vue': {} },
    { defineProps: () => props }, 'module.exports.wave = () => Array.from(waveform.value);');
  life.mount();
  t.mock.timers.tick(100);
  assert.equal(panel.wave().at(-1), 80);
  props.active = false;
  life.change();
  const paused = panel.wave();
  props.audioLevel = 20;
  t.mock.timers.tick(1000);
  assert.deepEqual(panel.wave(), paused);
  props.active = true;
  life.change();
  t.mock.timers.tick(100);
  assert.equal(panel.wave().at(-1), 20);
  const beforeDispose = panel.wave();
  life.dispose();
  t.mock.timers.tick(1000);
  assert.deepEqual(panel.wave(), beforeDispose);
});
