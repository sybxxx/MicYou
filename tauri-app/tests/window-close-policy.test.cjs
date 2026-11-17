const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

function load(relative, imports, globals = {}) {
  const source = readFileSync(join(__dirname, '../src', relative), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  const module = { exports: {} };
  const context = vm.createContext({
    exports: module.exports, module, console,
    require(name) {
      assert.ok(Object.hasOwn(imports, name), `unexpected import: ${name}`);
      return imports[name];
    },
    ...globals,
  });
  vm.runInContext(code, context);
  return module.exports;
}

function windowPolicy(remembered) {
  const mounted = [];
  const handlers = {};
  const calls = [];
  const values = new Map(remembered ? [['micyou_remember_close_action', remembered]] : []);
  const { useWindow } = load('features/window/composables/useWindow.ts', {
    vue: { ref: (value) => ({ value }), onMounted: (callback) => mounted.push(callback) },
    '@/platform': {
      appWindow: { onCloseRequested: async (callback) => { handlers.native = callback; return () => {}; } },
      command: async (name) => { calls.push(name); },
      onEvent: async (name, callback) => { handlers[name] = callback; return () => {}; },
    },
    '@/shared/lib/listeners': { useListeners: () => async (pending) => { await pending; } },
  }, {
    localStorage: { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) },
  });
  const state = useWindow();
  mounted.forEach((callback) => callback());
  return { state, handlers, calls };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('native close with no remembered choice prompts instead of exiting', async () => {
  const { state, handlers, calls } = windowPolicy();
  handlers.native();
  await settle();
  assert.equal(state.showCloseConfirm.value, true);
  assert.deepEqual(calls, []);
});

test('native close preserves hide-to-tray and never stops streaming', async () => {
  const { state, handlers, calls } = windowPolicy('hide');
  handlers.native();
  handlers['main-window-close-requested']();
  await settle();
  assert.deepEqual(calls, ['hide_main_window']);
  assert.equal(state.isHidden.value, true);
});

test('remembered exit collapses duplicate native/frontend close requests', async () => {
  const { handlers, calls } = windowPolicy('exit');
  handlers.native();
  handlers['main-window-close-requested']();
  await settle();
  assert.deepEqual(calls, ['exit_app']);
});

test('platform close callback prevents native destruction before forwarding', async () => {
  let nativeClose;
  let prevented = false;
  let forwarded = 0;
  const api = load('platform/window.ts', {
    '@tauri-apps/api/window': { getCurrentWindow: () => ({ onCloseRequested: async (callback) => { nativeClose = callback; return () => {}; } }), LogicalSize: class {} },
    '@tauri-apps/api/webviewWindow': { getCurrentWebviewWindow: () => ({}) },
  });
  await api.onCloseRequested(() => { forwarded++; });
  nativeClose({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(forwarded, 1);
});

test('full-mode resizing and pocket bounds can round-trip at non-default DPI', async () => {
  const calls = [];
  const native = {
    innerSize: async () => ({ width: 1920, height: 1440 }),
    scaleFactor: async () => 2,
    setMinSize: async (size) => calls.push(['min', size && [size.width, size.height]]),
    setMaxSize: async (size) => calls.push(['max', size && [size.width, size.height]]),
    setResizable: async (enabled) => calls.push(['resizable', enabled]),
    setSize: async (size) => calls.push(['size', [size.width, size.height]]),
  };
  const api = load('platform/window.ts', {
    '@tauri-apps/api/window': { getCurrentWindow: () => native, LogicalSize: class { constructor(width, height) { this.width = width; this.height = height; } } },
    '@tauri-apps/api/webviewWindow': { getCurrentWebviewWindow: () => ({}) },
  });
  const full = await api.logicalSize();
  assert.equal(full.width, 960);
  assert.equal(full.height, 720);
  await api.setFixedSize(400, 52);
  await api.setResizableSize(full.width, full.height);
  assert.deepEqual(calls.slice(-5), [['min', null], ['max', null], ['resizable', true], ['size', [960, 720]], ['min', [720, 560]]]);
});
