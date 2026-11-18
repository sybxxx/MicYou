// Browser-only desktop UI fixture. Inject before loading the built page; every
// IPC operation stays in this page, including preferences and notifications.
(() => {
  const callbacks = new Map();
  const listeners = new Map();
  let nextId = 1;
  const state = {
    calls: [],
    unhandled: [],
    notifications: [],
    visible: true,
    minimized: false,
    encrypted: false,
    resolvePairingResult: true,
    size: { width: 800, height: 600 },
    status: { isServerRunning: true, isConnected: false, isMuted: false, platform: 'android' },
    prefs: { port: 8554, webPort: 8443, webAllowedOrigins: [], mode: 'wifi', bindAddress: '0.0.0.0', autoBind: true, outputDevice: '', muteSync: true, requireEncryption: true, listenerWatchdog: true },
    emit(event, payload) {
      if (event === 'device-connected') state.status.isConnected = true;
      if (event === 'device-disconnected') state.status.isConnected = false;
      if (event === 'session-security') state.encrypted = payload.encrypted;
      for (const [id, listener] of listeners) {
        if (listener.event === event) callbacks.get(listener.handler)?.({ event, id, payload });
      }
    },
  };
  window.__MICYOU_MOCK__ = state;
  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener: (_event, id) => listeners.delete(id) };
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: location.hash === '#/settings' ? 'settings' : 'main' }, currentWebview: { label: location.hash === '#/settings' ? 'settings' : 'main' } },
    transformCallback(callback) { const id = nextId++; callbacks.set(id, callback); return id; },
    unregisterCallback: (id) => callbacks.delete(id),
    convertFileSrc: (path) => path,
    async invoke(command, args = {}) {
      state.calls.push({ command, args });
      if (command === 'plugin:event|listen') { const id = nextId++; listeners.set(id, args); return id; }
      if (command === 'plugin:event|unlisten') { listeners.delete(args.eventId); return; }
      if (command === 'plugin:event|emit' || command === 'plugin:event|emit_to') { state.emit(args.event, args.payload); return; }
      if (command === 'get_server_prefs') return { ...state.prefs };
      if (command === 'save_server_prefs') { state.prefs = { ...args.prefs }; return 'saved'; }
      if (command === 'get_streaming_status') return { ...state.status };
      if (command === 'get_session_security') return state.status.isConnected ? { encrypted: state.encrypted } : null;
      if (command === 'resolve_pairing') return state.resolvePairingResult;
      if (command === 'start_server') { state.status.isServerRunning = true; return 'started'; }
      if (command === 'stop_server') { state.status.isServerRunning = false; state.status.isConnected = false; return 'stopped'; }
      if (command === 'get_network_info') return { ips: ['192.0.2.10'], port: 8554 };
      if (command === 'get_network_interfaces') return [{ ip: '192.0.2.10', interface_name: 'Ethernet' }];
      if (command === 'get_audio_devices') return ['CABLE Input (VB-Audio Virtual Cable)'];
      if (command === 'get_audio_settings') return { gain: 0, nsEnabled: false, nsType: 'PureVox', nsIntensity: 100, dereverbEnabled: false, dereverbLevel: 50, agcEnabled: false, agcTarget: 16000, agcAttack: 50, agcDecay: 50, vadEnabled: false, vadThreshold: -40, aecEnabled: false, outputBufferMs: 100, processingChain: ['AEC', 'NoiseReduction', 'Dereverb', 'Equalizer', 'Amplifier', 'AGC', 'VAD'], equalizer: { enabled: false, preAmp: 0, gains: Array(10).fill(0) } };
      if (command === 'get_aec_status') return { available: true, enabled: false, reason: null };
      if (command === 'get_system_accent_color') return { hex: '#4179bd', source: 'test', supported: false };
      if (command === 'get_mode_status') return { mode: 'gui', pid: null, running: true };
      if (command === 'get_app_version') return '2.2.0+local.1';
      if (command === 'check_vbcable') return true;
      if (command === 'check_blackhole') return { installed: false, switch_audio_source: false, device_name: null };
      if (command === 'check_pipewire') return { available: false, setup: false, device_exists: false };
      if (['get_cached_plugin_updates', 'list_plugins', 'list_installed_themes'].includes(command)) return [];
      if (command === 'plugin:autostart|is_enabled') return false;
      if (command === 'plugin:window|scale_factor') return 1;
      if (command === 'plugin:window|inner_size') return { ...state.size };
      if (command === 'plugin:window|set_size') { state.size = { width: args.size.width, height: args.size.height }; return; }
      if (command === 'plugin:window|minimize' || command === 'minimize_main_window') {
        state.minimized = true;
        state.emit('tauri://blur');
        state.emit('tauri://resize', { width: 0, height: 0 });
        return;
      }
      if (command === 'plugin:window|is_minimized') return state.minimized;
      if (command === 'plugin:window|is_focused') return true;
      if (command === 'plugin:window|is_visible') return state.visible;
      if (command === 'show_main_window') { state.visible = true; state.minimized = false; state.emit('tauri://focus'); return; }
      if (command === 'hide_main_window') { state.visible = false; state.emit('tauri://blur'); return; }
      if (command.startsWith('plugin:window|') || command.startsWith('set_') || command.startsWith('save_') || command.startsWith('update_')) return;
      state.unhandled.push(command);
      return null;
    },
  };
  class FakeNotification {
    static permission = 'granted';
    static async requestPermission() { return 'granted'; }
    constructor(title, options) { state.notifications.push({ title, ...options }); }
  }
  Object.defineProperty(window, 'Notification', { value: FakeNotification, configurable: true });
  localStorage.setItem('micyou_onboarding_completed', 'true');
  if (localStorage.getItem('micyou_language') === null) localStorage.setItem('micyou_language', 'zh');
})();
