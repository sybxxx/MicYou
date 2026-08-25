import { ref, computed, onMounted, onUnmounted, watch, type Ref } from 'vue';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { useStorage } from '@vueuse/core';
import { useI18n } from 'vue-i18n';
import QRCode from 'qrcode';
import { sendNotification, isPermissionGranted, requestPermission } from '@tauri-apps/plugin-notification';
import { analyzeError, generateErrorDetails, type ConnectionErrorDetails } from '../utils/connectionError';

// Connection modes supported by the application
export type ConnectionMode = 'wifi' | 'usb' | 'web';

// Backend server streaming states
export type ServerState = 'idle' | 'starting' | 'connecting' | 'streaming';

const aecFailureNotificationKeys: Record<string, string> = {
  inference_failed: 'app.notify.aecDisabledInferenceFailed',
  model_load_failed: 'app.notify.aecDisabledModelLoadFailed',
  model_missing: 'app.notify.aecDisabledModelMissing',
  pipewire_unavailable: 'app.notify.aecDisabledPipeWireUnavailable',
  reference_lost: 'app.notify.aecDisabledReferenceLost',
  virtual_source_missing: 'app.notify.aecDisabledVirtualSourceMissing',
};

function aecFailureNotificationKey(reason?: string | null) {
  return (reason && aecFailureNotificationKeys[reason]) || 'app.notify.aecDisabled';
}

// Interface representing an ADB device discovered on the system
export interface AdbDevice {
  serial: string;
  state: string;
  description: string;
}

// Pending encrypted-pairing request from a Wi-Fi client awaiting user approval
export interface PairingRequest {
  requestId: string;
  deviceName: string;
  sas: string;
}

// Interface representing network details returned from the backend
export interface NetworkInfo {
  ips: string[];
  port: number;
}

// Interface representing a system network adapter interface
export interface NetworkInterfaceInfo {
  ip: string;
  interface_name: string;
}

interface ServerFaultPayload {
  component: string;
  message: string;
}

/**
 * Composable for managing the connection server, IP configurations, modes, and device scanning
 */
export function useServer(options?: { audioLevel?: Ref<number>; isMuted?: Ref<boolean> }) {
  const { t } = useI18n();

  // Current server connection state
  const serverState = ref<ServerState>('idle');
  
  // Selected connection mode persisted in storage
  const connectionMode = useStorage<ConnectionMode>('micyou_connectionMode', 'wifi');
  
  // Port for streaming under Wi-Fi or USB modes
  const serverPort = useStorage<number>('micyou_serverPort', 8554);
  
  // Port for Web RTC / HTTPS server stream
  const webPort = useStorage<number>('micyou_webPort', 8443);
  
  // Whether Wi-Fi clients must complete encrypted pairing before connecting
  // (shared server.json preference, mirrored by the CLI/TUI)
  const requireEncryption = ref(false);
  
  // Number of clients currently connected to the Web interface
  const webClientCount = ref(0);
  
  // Complete HTTPS URL for the web stream
  const webUrl = ref('');
  
  // Data URI base64 of the QR code for easy connection scanning
  const qrDataUrl = ref('');
  
  // Flag indicating if desktop OS notification alerts are enabled
  const notificationsEnabled = useStorage<boolean>('micyou_notifications', true);
  const notificationMessage = ref<string | null>(null);
  let notificationTimer: ReturnType<typeof setTimeout> | null = null;
  
  // Cache of primary network information returned by backend
  const networkInfo = ref<NetworkInfo | null>(null);
  
  // Currently selected bind IP (if isAutoBind is false)
  const selectedIp = ref<string>('0.0.0.0');
  
  // List of active network adapters and interfaces detected by the OS
  const networkInterfaces = ref<NetworkInterfaceInfo[]>([]);
  
  // UI triggers for menus, confirm popups and dialogs
  const showIpMenu = ref(false);
  const showIpSwitchConfirm = ref(false);
  const pendingIp = ref('');
  const pendingAutoSelect = ref(false);
  
  // Whether to listen on all interfaces (auto-bind) or a selected static IP interface
  const isAutoBind = ref(true);
  
  // USB mode target select overlay triggers
  const showDeviceSelector = ref(false);
  const adbDevices = ref<AdbDevice[]>([]);
  const pendingUsbPort = ref<number>(0);
  
  // Error handling triggers
  const showErrorDialog = ref(false);
  const errorDetails = ref<ConnectionErrorDetails | null>(null);
  
  // Selected audio output device target (e.g. system default or virtual sound card)
  const outputDevice = ref<string>(localStorage.getItem('micyou_output_device') || '');
  const showQrDialog = ref(false);

  // Active configurations when the server is running
  const activeConnectionMode = ref<ConnectionMode | null>(null);
  const activePort = ref<number | null>(null);

  // Encrypted transport: queued pairing requests (oldest shown first) and the
  // security state of the current client session
  const pairingQueue = ref<PairingRequest[]>([]);
  const currentPairingRequest = computed(() => pairingQueue.value[pairingQueue.value.length - 1] ?? null);
  const sessionUnencrypted = ref(false);

  // Computes the display representation of the active bind IP address
  const displayIp = computed(() => {
    if (isAutoBind.value) {
      return networkInterfaces.value.length > 0 ? networkInterfaces.value[0].ip : '...';
    }
    return selectedIp.value;
  });

  // Dynamic text description showing the status of the connection
  const statusDescription = computed(() => {
    const mode = activeConnectionMode.value || connectionMode.value;
    const port = activePort.value || (mode === 'web' ? webPort.value : serverPort.value);

    if (serverState.value === 'streaming') {
      if (mode === 'web') {
        return t('app.web.clientsConnected', { count: webClientCount.value });
      }
      return t('app.status.streamingDesc');
    }
    if (serverState.value === 'connecting') {
      return t('app.status.connectingDesc', { port: port });
    }
    if (serverState.value === 'starting') return t('app.status.startingDesc');
    return t('app.status.readyDesc');
  });

  /**
   * Checks if the server is in any active (non-idle) states
   */
  function isStreaming(v: ServerState) {
    return v === 'streaming' || v === 'connecting' || v === 'starting';
  }

  function dismissNotification() {
    if (notificationTimer) {
      clearTimeout(notificationTimer);
      notificationTimer = null;
    }
    notificationMessage.value = null;
  }

  function showInAppNotification(body: string) {
    notificationMessage.value = body;
    if (notificationTimer) clearTimeout(notificationTimer);
    notificationTimer = setTimeout(() => {
      notificationMessage.value = null;
      notificationTimer = null;
    }, 5000);
  }

  /**
   * Shows an in-app status message and best-effort desktop notification.
   * The in-app message remains visible when Windows notifications are unavailable.
   */
  async function notify(body: string) {
    if (!notificationsEnabled.value) return;
    showInAppNotification(body);
    try {
      let granted = await isPermissionGranted();
      if (!granted) {
        granted = (await requestPermission()) === 'granted';
      }
      if (!granted) {
        console.warn('[Notifications] Desktop notification permission was not granted');
        return;
      }
      sendNotification({ title: 'MicYou', body });
    } catch (error) {
      console.warn('[Notifications] Desktop notification failed; using in-app message:', error);
    }
  }

  /**
   * Generates a base64 QR code image from a given target URL
   */
  async function generateQrCode(url: string) {
    try {
      qrDataUrl.value = await QRCode.toDataURL(url, {
        width: 200,
        margin: 1,
        color: { dark: '#000000', light: '#ffffff' }
      });
    } catch (e) {
      console.error('QR generation failed:', e);
      qrDataUrl.value = '';
    }
  }

  // OS detection for macOS visual behavior
  const isMacOS = typeof navigator !== 'undefined' && /Mac/.test(navigator.platform || navigator.userAgent) && !/iPhone|iPad|iPod/.test(navigator.userAgent);

  /**
   * Toggles the server state between started and stopped
   */
  const toggleStreaming = async () => {
    // PipeWire setup and audio initialization can take a moment. Do not let a
    // second click (or an auto-start racing with a manual click) stop the
    // server that is still starting.
    if (serverState.value === 'starting') {
      console.warn('[Server] Ignoring toggle while server startup is in progress');
      return;
    }

    if (serverState.value !== 'idle') {
      try {
        await invoke('stop_server');
      } catch (e) {
        console.warn('Failed to stop server cleanly:', e);
      } finally {
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
        if (options?.audioLevel) options.audioLevel.value = 0;
        // Restore original input device on macOS when using BlackHole virtual audio
        if (isMacOS) {
          try { await invoke('restore_input_device'); } catch { /* best-effort cleanup, ignore */ }
        }
      }
      return;
    }

    const mode = connectionMode.value;
    const port = mode === 'web' ? Number(webPort.value) : Number(serverPort.value);
    try {
      serverState.value = 'starting';
      activeConnectionMode.value = mode;
      activePort.value = port;
      const bindAddress = isAutoBind.value ? null : selectedIp.value;
      await invoke('start_server', {
        port,
        mode,
        bindAddress,
        outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
      });
      // Auto-switch to BlackHole input on macOS for seamless virtual audio loopback
      if (isMacOS) {
        try { await invoke('set_blackhole_as_input'); } catch { /* best-effort cleanup, ignore */ }
      }
      if (mode === 'usb') {
        const result = await invoke<{ type: string; devices?: AdbDevice[] }>('enable_usb_mode', { port, deviceSerial: null });
        if (result.type === 'MultipleDevices') {
          try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
          adbDevices.value = result.devices ?? [];
          pendingUsbPort.value = port;
          showDeviceSelector.value = true;
          serverState.value = 'idle';
          activeConnectionMode.value = null;
          activePort.value = null;
          return;
        }
        if (result.type === 'NoDevices') {
          try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
          serverState.value = 'idle';
          activeConnectionMode.value = null;
          activePort.value = null;
          const msg = 'No USB devices found. Please connect a device and enable USB debugging.';
          const type = analyzeError(msg);
          errorDetails.value = generateErrorDetails(type, msg, mode, port, selectedIp.value, t);
          showErrorDialog.value = true;
          return;
        }
      }
      if (mode === 'web') {
        const ip = networkInfo.value?.ips[0] ?? 'localhost';
        const url = `https://${ip}:${webPort.value}`;
        webUrl.value = url;
        generateQrCode(url);
      }
      serverState.value = 'connecting';
    } catch (e: any) {
      console.error(e);
      try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
      const msg = typeof e === 'string' ? e : e?.message ?? String(e);
      const type = analyzeError(msg);
      errorDetails.value = generateErrorDetails(type, msg, mode, port, selectedIp.value, t);
      showErrorDialog.value = true;
      serverState.value = 'idle';
      activeConnectionMode.value = null;
      activePort.value = null;
    }
  };

  /**
   * Sets bind IP target or prompts user if they try to switch while server is active
   */
  const selectIp = (ip: string, autoSelect: boolean) => {
    if (autoSelect && isAutoBind.value) {
      showIpMenu.value = false;
      return;
    }
    if (!autoSelect && !isAutoBind.value && selectedIp.value === ip) {
      showIpMenu.value = false;
      return;
    }
    if (serverState.value === 'streaming' || serverState.value === 'connecting') {
      pendingIp.value = ip;
      pendingAutoSelect.value = autoSelect;
      showIpSwitchConfirm.value = true;
      showIpMenu.value = false;
    } else {
      applyIpSelection(ip, autoSelect);
      showIpMenu.value = false;
    }
  };

  /**
   * Sets active bind variables directly
   */
  const applyIpSelection = (ip: string, autoSelect: boolean) => {
    if (autoSelect) {
      isAutoBind.value = true;
      selectedIp.value = '0.0.0.0';
    } else {
      isAutoBind.value = false;
      selectedIp.value = ip;
    }
  };

  /**
   * Switches IP and restarts the server with the new bind target
   */
  const confirmIpSwitch = async () => {
    applyIpSelection(pendingIp.value, pendingAutoSelect.value);
    showIpSwitchConfirm.value = false;
    if (serverState.value === 'streaming' || serverState.value === 'connecting') {
      try {
        try { await invoke('stop_server'); } catch (e) { console.warn('stop_server during IP switch:', e); }
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
        if (options?.audioLevel) options.audioLevel.value = 0;
        const bindAddress = isAutoBind.value ? null : selectedIp.value;
        activeConnectionMode.value = connectionMode.value;
        activePort.value = connectionMode.value === 'web' ? Number(webPort.value) : Number(serverPort.value);
        await invoke('start_server', {
          port: activePort.value,
          mode: activeConnectionMode.value,
          bindAddress: bindAddress,
          outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
        });
        serverState.value = 'connecting';
        if (activeConnectionMode.value === 'usb') {
          const result = await invoke<{ type: string; devices?: AdbDevice[] }>('enable_usb_mode', { port: activePort.value, deviceSerial: null });
          if (result.type === 'MultipleDevices') {
            try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
            adbDevices.value = result.devices || [];
            pendingUsbPort.value = activePort.value || Number(serverPort.value);
            showDeviceSelector.value = true;
            serverState.value = 'idle';
            activeConnectionMode.value = null;
            activePort.value = null;
            return;
          } else if (result.type === 'NoDevices') {
            try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
            serverState.value = 'idle';
            activeConnectionMode.value = null;
            activePort.value = null;
            const msg = 'No USB devices found. Please connect a device and enable USB debugging.';
            const type = analyzeError(msg);
            errorDetails.value = generateErrorDetails(type, msg, activeConnectionMode.value || connectionMode.value, activePort.value || Number(serverPort.value), selectedIp.value, t);
            showErrorDialog.value = true;
            return;
          }
        }
      } catch (e: any) {
        console.error(e);
        try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
        const msg = typeof e === 'string' ? e : e?.message ?? String(e);
        const type = analyzeError(msg);
        errorDetails.value = generateErrorDetails(type, msg, activeConnectionMode.value || connectionMode.value, activePort.value || Number(serverPort.value), selectedIp.value, t);
        showErrorDialog.value = true;
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
      }
    }
  };

  /**
   * Targets a specific discovered USB/ADB device
   */
  const selectAdbDevice = async (serial: string) => {
    showDeviceSelector.value = false;
    try {
      serverState.value = 'starting';
      activeConnectionMode.value = 'usb';
      activePort.value = pendingUsbPort.value;
      const bindAddress = isAutoBind.value ? null : selectedIp.value;
      await invoke('start_server', {
        port: activePort.value,
        mode: activeConnectionMode.value,
        bindAddress: bindAddress,
        outputDevice: (outputDevice.value && outputDevice.value !== 'auto' && outputDevice.value !== 'default') ? outputDevice.value : null
      });
      await invoke('enable_usb_mode', { port: pendingUsbPort.value, deviceSerial: serial });
      serverState.value = 'connecting';
    } catch (e: any) {
      console.error(e);
      try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
      const msg = typeof e === 'string' ? e : e?.message ?? String(e);
      const type = analyzeError(msg);
      errorDetails.value = generateErrorDetails(type, msg, 'usb', pendingUsbPort.value, selectedIp.value, t);
      showErrorDialog.value = true;
      serverState.value = 'idle';
      activeConnectionMode.value = null;
      activePort.value = null;
    }
  };

  /**
   * Cancels ongoing ADB device selection flow
   */
  const cancelDeviceSelection = () => {
    showDeviceSelector.value = false;
    adbDevices.value = [];
    pendingUsbPort.value = 0;
  };

  /**
   * Approves or denies the oldest pending pairing request and advances the queue
   */
  const resolvePairing = async (accept: boolean) => {
    // The dialog displays the newest request: that is the connection the phone
    // is actually waiting on; older entries are stale retries.
    const request = pairingQueue.value.pop();
    if (!request) return;
    try {
      await invoke('resolve_pairing', { requestId: request.requestId, accept });
    } catch (e) {
      console.error('resolve_pairing failed:', e);
    }
  };

  let unlistenDeviceConnected: UnlistenFn | null = null;
  let unlistenDeviceDisconnected: UnlistenFn | null = null;
  let unlistenServerStopped: UnlistenFn | null = null;
  let unlistenServerFault: UnlistenFn | null = null;
  let unlistenUdpWarning: UnlistenFn | null = null;
  let unlistenWebClients: UnlistenFn | null = null;
  let unlistenAecStatus: UnlistenFn | null = null;
  let unlistenPairingRequested: UnlistenFn | null = null;
  let unlistenPairingCompleted: UnlistenFn | null = null;
  let unlistenSessionSecurity: UnlistenFn | null = null;

  // ---- Shared server prefs (server.json, also read/written by the CLI) ----
  interface ServerPrefsBackend {
    port?: number;
    webPort?: number;
    mode?: string;
    bindAddress?: string;
    autoBind?: boolean;
    outputDevice?: string;
    requireEncryption?: boolean;
  }

  async function loadServerPrefs() {
    try {
      // First run of a synced version: migrate localStorage values
      // (written by older builds) into the shared server.json.
      const exists = await invoke<boolean>('server_prefs_exists');
      if (!exists) {
        persistServerPrefs();
        return;
      }
      const prefs = await invoke<ServerPrefsBackend>('get_server_prefs');
      if (!prefs) return;
      if (prefs.port) serverPort.value = prefs.port;
      if (prefs.webPort) webPort.value = prefs.webPort;
      if (prefs.mode && ['wifi', 'usb', 'web'].includes(prefs.mode)) {
        connectionMode.value = prefs.mode as ConnectionMode;
      }
      if (prefs.autoBind !== undefined) isAutoBind.value = prefs.autoBind;
      if (prefs.bindAddress && prefs.bindAddress !== '0.0.0.0') {
        selectedIp.value = prefs.bindAddress;
      }
      if (prefs.outputDevice) {
        outputDevice.value = prefs.outputDevice;
        localStorage.setItem('micyou_output_device', prefs.outputDevice);
      }
      if (prefs.requireEncryption !== undefined) {
        requireEncryption.value = prefs.requireEncryption;
      }
    } catch (e) {
      console.error('Failed to load server prefs:', e);
    }
  }

  let prefsSaveTimer: ReturnType<typeof setTimeout> | null = null;
  function persistServerPrefs() {
    if (prefsSaveTimer) clearTimeout(prefsSaveTimer);
    prefsSaveTimer = setTimeout(() => {
      void invoke('save_server_prefs', {
        prefs: {
          port: Number(serverPort.value),
          webPort: Number(webPort.value),
          mode: connectionMode.value,
          bindAddress: isAutoBind.value ? '0.0.0.0' : selectedIp.value,
          autoBind: isAutoBind.value,
          outputDevice: outputDevice.value || '',
          requireEncryption: requireEncryption.value,
        },
      }).catch((e) => console.error('Failed to save server prefs:', e));
    }, 500);
  }
  watch(
    [connectionMode, serverPort, webPort, isAutoBind, selectedIp, outputDevice, requireEncryption],
    persistServerPrefs,
  );

  // A session cannot outlive its server; drop the stale security flag whenever
  // the server returns to idle (covers stop, faults and restart paths)
  watch(serverState, (state) => {
    if (state === 'idle') sessionUnencrypted.value = false;
  });

  onMounted(async () => {
    try {
      await loadServerPrefs();
    } catch (e) {
      console.error('Failed to sync server prefs:', e);
    }

    try {
      networkInfo.value = await invoke<NetworkInfo>('get_network_info');
      if (networkInfo.value && networkInfo.value.ips.length > 0) {
        selectedIp.value = networkInfo.value.ips[0];
      }
    } catch (e) {
      console.error("Failed to get network info:", e);
    }

    try {
      networkInterfaces.value = await invoke<NetworkInterfaceInfo[]>('get_network_interfaces');
    } catch (e) {
      console.error("Failed to get network interfaces:", e);
    }

    // Listen for client connection successful event
    unlistenDeviceConnected = await listen('device-connected', () => {
      const hasActiveServer = serverState.value !== 'idle' || activeConnectionMode.value !== null;
      if (!hasActiveServer) return;
      serverState.value = 'streaming';
      void notify(t('app.notify.connected'));
    });

    // Listen for client disconnect events
    unlistenDeviceDisconnected = await listen('device-disconnected', async () => {
      const hadActiveServer = serverState.value !== 'idle' || activeConnectionMode.value !== null;
      if (!hadActiveServer) return;

      const mode = activeConnectionMode.value || connectionMode.value;
      if (mode === 'usb') {
        try { await invoke('stop_server'); } catch { /* best-effort cleanup, ignore */ }
        serverState.value = 'idle';
        activeConnectionMode.value = null;
        activePort.value = null;
        if (options?.audioLevel) options.audioLevel.value = 0;
        if (options?.isMuted) options.isMuted.value = false;
        void notify(t('app.notify.usbDisconnected'));
      } else {
        serverState.value = 'connecting';
        if (options?.audioLevel) options.audioLevel.value = 0;
        if (options?.isMuted) options.isMuted.value = false;
        sessionUnencrypted.value = false;
        void notify(t('app.notify.disconnected'));
      }
    });

    // Listen for general server stops triggered elsewhere
    unlistenServerStopped = await listen('server-stopped', () => {
      serverState.value = 'idle';
      activeConnectionMode.value = null;
      activePort.value = null;
      if (options?.audioLevel) options.audioLevel.value = 0;
      if (options?.isMuted) options.isMuted.value = false;
    });

    // Listen for a network task that failed unexpectedly. This is distinct from
    // a normal client disconnect so the user knows the desktop service itself
    // needs to be restarted.
    unlistenServerFault = await listen<ServerFaultPayload>('server-fault', (event) => {
      console.error('[Server] background task failed:', event.payload);
      // A failed network task leaves the shared cancellation token alive. Stop
      // the remaining tasks so the next manual start can create a clean session.
      void invoke('stop_server').catch((error) => {
        console.warn('[Server] failed to clean up after background task failure:', error);
      });
      serverState.value = 'idle';
      activeConnectionMode.value = null;
      activePort.value = null;
      if (options?.audioLevel) options.audioLevel.value = 0;
      if (options?.isMuted) options.isMuted.value = false;
      void notify(t('app.notify.serverFault', { component: event.payload.component }));
    });

    // A UDP stall can leave the TCP control connection alive. Keep the existing
    // detailed dialog and also surface a notification that works while hidden.
    unlistenUdpWarning = await listen('udp_audio_warning', () => {
      void notify(t('app.notify.udpAudioWarning'));
    });

    // Listen for clients joining/leaving the local web server
    unlistenWebClients = await listen<number>('web-client-count', (event) => {
      webClientCount.value = event.payload;
    });

    unlistenAecStatus = await listen<{ available: boolean; enabled: boolean; reason?: string | null }>('aec-status-changed', (event) => {
      if (!event.payload.available && notificationsEnabled.value) {
        void notify(t(aecFailureNotificationKey(event.payload.reason)));
      }
    });

    // Encrypted transport: a new phone is requesting to pair; the SAS code is
    // shown on both screens and must match before the user approves
    unlistenPairingRequested = await listen<PairingRequest>('pairing-requested', (event) => {
      pairingQueue.value.push(event.payload);
      void notify(t('app.notify.pairingRequested', { device: event.payload.deviceName }));
      // The dialog lives in the main window; surface it immediately even when
      // the app is minimized or buried, since approval gates the connection.
      void getCurrentWindow().setFocus().catch(() => {});
      void getCurrentWindow().requestUserAttention(1).catch(() => {});
    });

    // The backend may serialize the completed-pairing info with either key
    // casing depending on the struct's serde attributes
    unlistenPairingCompleted = await listen<{ deviceName?: string; device_name?: string }>('pairing-completed', (event) => {
      const device = event.payload.deviceName ?? event.payload.device_name;
      if (device) {
        void notify(t('app.notify.paired', { device }));
      }
    });

    // Emitted whenever a client session starts/stops; encrypted=false means a
    // legacy plaintext session is streaming
    unlistenSessionSecurity = await listen<{ encrypted: boolean }>('session-security', (event) => {
      sessionUnencrypted.value = event.payload.encrypted === false;
    });

    // Start streaming automatically if user configuration allows it
    if (localStorage.getItem('micyou_auto_stream') === 'true') {
      toggleStreaming();
    }
  });

  onUnmounted(() => {
    if (unlistenDeviceConnected) unlistenDeviceConnected();
    if (unlistenDeviceDisconnected) unlistenDeviceDisconnected();
    if (unlistenServerStopped) unlistenServerStopped();
    if (unlistenServerFault) unlistenServerFault();
    if (unlistenUdpWarning) unlistenUdpWarning();
    if (unlistenWebClients) unlistenWebClients();
    if (unlistenAecStatus) unlistenAecStatus();
    if (unlistenPairingRequested) unlistenPairingRequested();
    if (unlistenPairingCompleted) unlistenPairingCompleted();
    if (unlistenSessionSecurity) unlistenSessionSecurity();
    dismissNotification();
  });

  return {
    serverState,
    connectionMode,
    serverPort,
    webPort,
    webClientCount,
    webUrl,
    qrDataUrl,
    networkInfo,
    selectedIp,
    networkInterfaces,
    showIpMenu,
    isAutoBind,
    displayIp,
    statusDescription,
    showDeviceSelector,
    adbDevices,
    pendingUsbPort,
    showErrorDialog,
    errorDetails,
    outputDevice,
    showQrDialog,
    notificationsEnabled,
    notificationMessage,
    requireEncryption,
    pairingQueue,
    currentPairingRequest,
    sessionUnencrypted,
    dismissNotification,
    showIpSwitchConfirm,
    pendingIp,
    pendingAutoSelect,
    isStreaming,
    toggleStreaming,
    selectIp,
    applyIpSelection,
    confirmIpSwitch,
    selectAdbDevice,
    cancelDeviceSelection,
    resolvePairing,
  };
}
