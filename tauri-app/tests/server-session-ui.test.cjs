const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

function loadSource(relative, imports) {
  const source = readFileSync(join(__dirname, '../src', relative), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  const module = { exports: {} };
  vm.runInContext(code, vm.createContext({
    exports: module.exports, module, console, setTimeout, clearTimeout,
    localStorage: { getItem: () => null },
    require: (name) => {
      assert.ok(Object.hasOwn(imports, name), `unexpected import: ${name}`);
      return imports[name];
    },
  }));
  return module.exports;
}

async function serverHarness(t, connected = false) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const mounted = [];
  const unmounted = [];
  const watchers = [];
  const handlers = new Map();
  const calls = [];
  const ref = (value) => ({ value });
  const required = ref(false);
  let pairingResult = true;
  const prefs = { port: 8554, webPort: 8443, webAllowedOrigins: ['https://trusted.example'], mode: 'wifi', bindAddress: '0.0.0.0', autoBind: true, outputDevice: '', muteSync: true, requireEncryption: true, listenerWatchdog: false };
  const { useServer } = loadSource('features/connection/composables/useServer.ts', {
    vue: { ref, computed: (getter) => ({ get value() { return getter(); } }), onMounted: (fn) => mounted.push(fn), onUnmounted: (fn) => unmounted.push(fn), watch: (_source, fn) => watchers.push(fn) },
    '@vueuse/core': { useStorage: (_key, value) => ref(value) },
    'vue-i18n': { useI18n: () => ({ t: (key) => key }) },
    qrcode: { default: { toDataURL: async () => 'data:test' } },
    '../utils/connectionError': { analyzeError: () => 'unknown', generateErrorDetails: () => ({}) },
    './useMuteSync': { muteSyncEnabled: ref(true) },
    './useEncryption': { requireEncryption: required },
    '../utils/connectionNotifications': loadSource('features/connection/utils/connectionNotifications.ts', {}),
    '@/shared/lib/os': { isMacOS: false },
    '@/shared/lib/listeners': { useListeners: () => async (pending) => { await pending; } },
    '@/platform': {
      appWindow: { requestPairingAttention: async () => {} },
      notify: async (body) => calls.push(['notify', body]),
      onEvent: async (name, handler) => { handlers.set(name, handler); return () => {}; },
      command: async (name, args) => {
        calls.push([name, args]);
        if (name === 'get_server_prefs') return prefs;
        if (name === 'get_streaming_status') return { isServerRunning: true, isConnected: connected, isMuted: false, platform: 'android' };
        if (name === 'get_session_security') return connected ? { encrypted: false } : null;
        if (name === 'get_network_info') return { ips: ['192.0.2.10'], port: 8554 };
        if (name === 'get_network_interfaces') return [];
        if (name === 'resolve_pairing') return pairingResult;
        return null;
      },
    },
  });
  const server = useServer();
  for (const callback of mounted) await callback();
  t.after(() => unmounted.forEach((callback) => callback()));
  return { server, handlers, calls, required, watchers, setPairingResult: (value) => { pairingResult = value; } };
}

test('control metrics cannot mark a silent Wi-Fi session connected', async (t) => {
  const { server, handlers } = await serverHarness(t);
  assert.equal(server.serverState.value, 'connecting');
  handlers.get('audio-metrics')?.({ latencyMs: 1 });
  handlers.get('audio-level')?.(80);
  assert.equal(server.serverState.value, 'connecting');
  handlers.get('device-connected')({ name: 'Phone', ip: '192.168.0.2', latency: 1, platform: 'android' });
  assert.equal(server.serverState.value, 'streaming');
  handlers.get('session-security')({ encrypted: false });
  assert.equal(server.sessionUnencrypted.value, true);
  handlers.get('session-security')({ encrypted: true });
  assert.equal(server.sessionUnencrypted.value, false);
});

test('GUI preference saves retain encryption, listener policy and advanced web origins', async (t) => {
  const { required, watchers, calls } = await serverHarness(t);
  assert.equal(required.value, true);
  required.value = false;
  watchers[0]();
  t.mock.timers.tick(500);
  const saved = calls.find(([name]) => name === 'save_server_prefs')[1].prefs;
  assert.equal(saved.requireEncryption, false);
  assert.equal(saved.listenerWatchdog, false);
  assert.equal(saved.webAllowedOrigins[0], 'https://trusted.example');
});

test('pairing retries resolve the displayed request id and report an expired request', async (t) => {
  const { server, handlers, calls, setPairingResult } = await serverHarness(t);
  handlers.get('pairing-requested')({ requestId: 'old', deviceName: 'Phone', sas: '111111' });
  handlers.get('pairing-requested')({ requestId: 'new', deviceName: 'Phone', sas: '222222' });
  assert.equal(server.currentPairingRequest.value.sas, '222222');
  setPairingResult(false);
  await server.resolvePairing(true);
  const submitted = calls.find(([name]) => name === 'resolve_pairing')[1];
  assert.equal(submitted.requestId, 'new');
  assert.equal(submitted.accept, true);
  assert.equal(server.currentPairingRequest.value, null);
  assert.equal(server.notificationMessage.value, 'app.pairing.expired');
});

test('reloading an existing plaintext session restores its warning without a new connection toast', async (t) => {
  const { server, calls } = await serverHarness(t, true);
  assert.equal(server.serverState.value, 'streaming');
  assert.equal(server.sessionUnencrypted.value, true);
  assert.equal(calls.some(([name]) => name === 'notify'), false);
});
