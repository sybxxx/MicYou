use crate::commands::system::SpectrumPayload;
use crate::pairing::SessionSecurityState;
use crate::stats::AudioMetrics;
use crate::tcp_server::DeviceInfo;
use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::Emitter;
use tauri::Manager;

const AUDIO_LEVEL_EVENT_INTERVAL_MS: u64 = 75;

#[derive(serde::Serialize, Clone, Debug)]
pub struct PairingRequestInfo {
    pub request_id: String,
    pub device_name: String,
    pub sas: String,
}

/// Events emitted by the audio server core, decoupled from Tauri.
///
/// The GUI implements this via `TauriEventSink` (wraps `AppHandle.emit`); CLI/TUI
/// implement it by updating TUI state or writing log lines. This keeps the server
/// core callable without a running Tauri runtime.
pub trait ServerEvents: Send + Sync + 'static {
    fn device_connected(&self, info: DeviceInfo);
    fn device_disconnected(&self);
    fn audio_metrics(&self, metrics: AudioMetrics);
    fn udp_audio_warning(&self);
    fn mute_state_changed(&self, is_muted: bool);
    fn audio_level(&self, level: u32);
    fn audio_spectrum(&self, raw: Vec<f32>, processed: Vec<f32>);
    fn server_stopped(&self);
    fn server_fault(&self, component: String, message: String);
    fn web_client_count(&self, count: u32);
    fn install_progress(&self, message: String);
    fn aec_status_changed(&self, status: AecStatus);
    fn pairing_requested(&self, request: PairingRequestInfo);
    fn pairing_completed(&self, device_name: String);
    fn session_security_changed(&self, encrypted: bool);
}

#[derive(serde::Serialize, Clone, Debug)]
pub struct AecStatus {
    pub available: bool,
    pub enabled: bool,
    pub reason: Option<micyou_audio::AecFailure>,
}

pub type SharedEvents = Arc<dyn ServerEvents>;

/// Tauri adapter: forwards server events to the webview as Tauri events.
pub struct TauriEventSink {
    app: tauri::AppHandle,
    last_audio_level_emit: Mutex<Option<Instant>>,
}

#[derive(Serialize, Clone, Debug)]
pub struct ServerFault {
    pub component: String,
    pub message: String,
}

#[derive(serde::Serialize, Clone, Debug)]
pub struct PairingCompletedInfo {
    pub device_name: String,
}

impl TauriEventSink {
    pub fn new(app: tauri::AppHandle) -> Self {
        Self {
            app,
            last_audio_level_emit: Mutex::new(None),
        }
    }
}

impl TauriEventSink {
    fn emit_app_event<T: Serialize + Clone>(&self, name: &str, payload: T) {
        if let Err(error) = self.app.emit(name, payload) {
            log::warn!(target: "events", "failed to emit {name}: {error}");
        }
    }

    fn emit_main_window_event<T: Serialize + Clone>(&self, name: &str, payload: T) {
        if let Some(main_window) = self.app.get_webview_window("main") {
            if let Err(error) = main_window.emit(name, payload) {
                log::warn!(target: "events", "failed to emit {name}: {error}");
            }
        } else {
            log::debug!(target: "events", "skipped {name}: main window is unavailable");
        }
    }
}

fn should_emit_audio_level(last_emit: &mut Option<Instant>, now: Instant) -> bool {
    let should_emit = last_emit
        .map(|previous| {
            now.saturating_duration_since(previous)
                >= Duration::from_millis(AUDIO_LEVEL_EVENT_INTERVAL_MS)
        })
        .unwrap_or(true);
    if should_emit {
        *last_emit = Some(now);
    }
    should_emit
}

impl ServerEvents for TauriEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        self.emit_app_event("device-connected", info);
    }
    fn device_disconnected(&self) {
        self.emit_app_event("device-disconnected", ());
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        self.emit_app_event("audio-metrics", metrics);
    }
    fn udp_audio_warning(&self) {
        self.emit_app_event("udp_audio_warning", ());
    }
    fn mute_state_changed(&self, is_muted: bool) {
        self.emit_app_event("mute-state-changed", is_muted);
    }
    fn audio_level(&self, level: u32) {
        let should_emit = self
            .last_audio_level_emit
            .lock()
            .map(|mut last_emit| should_emit_audio_level(&mut last_emit, Instant::now()))
            .unwrap_or(true);
        if !should_emit {
            return;
        }
        self.emit_main_window_event("audio-level", level);
    }
    fn audio_spectrum(&self, raw: Vec<f32>, processed: Vec<f32>) {
        self.emit_main_window_event("audio-spectrum", SpectrumPayload { raw, processed });
    }
    fn server_stopped(&self) {
        self.emit_app_event("server-stopped", ());
    }
    fn server_fault(&self, component: String, message: String) {
        self.emit_app_event("server-fault", ServerFault { component, message });
    }
    fn web_client_count(&self, count: u32) {
        self.emit_app_event("web-client-count", count);
    }
    fn install_progress(&self, message: String) {
        self.emit_app_event("vbcable-install-progress", message);
    }

    fn aec_status_changed(&self, status: AecStatus) {
        self.emit_app_event("aec-status-changed", status);
    }

    fn pairing_requested(&self, request: PairingRequestInfo) {
        self.emit_app_event("pairing-requested", request);
    }

    fn pairing_completed(&self, device_name: String) {
        self.emit_app_event(
            "pairing-completed",
            PairingCompletedInfo {
                device_name,
            },
        );
    }

    fn session_security_changed(&self, encrypted: bool) {
        self.emit_app_event(
            "session-security",
            SessionSecurityState { encrypted },
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audio_level_events_are_limited_to_ui_rate() {
        let start = Instant::now();
        let mut last_emit = None;

        assert!(should_emit_audio_level(&mut last_emit, start));
        assert!(!should_emit_audio_level(
            &mut last_emit,
            start + Duration::from_millis(AUDIO_LEVEL_EVENT_INTERVAL_MS - 1)
        ));
        assert!(should_emit_audio_level(
            &mut last_emit,
            start + Duration::from_millis(AUDIO_LEVEL_EVENT_INTERVAL_MS)
        ));
    }

    #[test]
    fn server_fault_payload_matches_frontend_event_shape() {
        let payload = ServerFault {
            component: "udp".to_string(),
            message: "socket failed".to_string(),
        };
        let json = serde_json::to_value(payload).expect("server fault should serialize");

        assert_eq!(json["component"], "udp");
        assert_eq!(json["message"], "socket failed");
    }
}
