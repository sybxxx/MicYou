use crate::commands::system::SpectrumPayload;
use crate::stats::AudioMetrics;
use crate::tcp_server::DeviceInfo;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::Emitter;
use tauri::Manager;

const AUDIO_LEVEL_EVENT_INTERVAL_MS: u64 = 75;

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
    fn web_client_count(&self, count: u32);
    fn install_progress(&self, message: String);
    fn aec_status_changed(&self, status: AecStatus);
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

impl TauriEventSink {
    pub fn new(app: tauri::AppHandle) -> Self {
        Self {
            app,
            last_audio_level_emit: Mutex::new(None),
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
        let _ = self.app.emit("device-connected", info);
    }
    fn device_disconnected(&self) {
        let _ = self.app.emit("device-disconnected", ());
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        let _ = self.app.emit("audio-metrics", metrics);
    }
    fn udp_audio_warning(&self) {
        let _ = self.app.emit("udp_audio_warning", ());
    }
    fn mute_state_changed(&self, is_muted: bool) {
        let _ = self.app.emit("mute-state-changed", is_muted);
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
        if let Some(main_window) = self.app.get_webview_window("main") {
            let _ = main_window.emit("audio-level", level);
        }
    }
    fn audio_spectrum(&self, raw: Vec<f32>, processed: Vec<f32>) {
        if let Some(main_window) = self.app.get_webview_window("main") {
            let _ = main_window.emit("audio-spectrum", SpectrumPayload { raw, processed });
        }
    }
    fn server_stopped(&self) {
        let _ = self.app.emit("server-stopped", ());
    }
    fn web_client_count(&self, count: u32) {
        let _ = self.app.emit("web-client-count", count);
    }
    fn install_progress(&self, message: String) {
        let _ = self.app.emit("vbcable-install-progress", message);
    }

    fn aec_status_changed(&self, status: AecStatus) {
        let _ = self.app.emit("aec-status-changed", status);
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
}
