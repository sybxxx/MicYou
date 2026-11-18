/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

//! Forwards core server events to the webview.

use micyou_core::events::{
    AecStatus, DownloadProgress, PairingRequestInfo, ServerEvents, SpectrumPayload,
};
use micyou_core::stats::AudioMetrics;
use micyou_core::transport::tcp::DeviceInfo;
use serde::Serialize;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};

const AUDIO_LEVEL_INTERVAL: Duration = Duration::from_millis(75);

pub struct TauriEventSink {
    app: AppHandle,
    last_audio_level: Mutex<Option<Instant>>,
}

fn take_audio_level_slot(last: &mut Option<Instant>, now: Instant) -> bool {
    if last.is_some_and(|previous| now.saturating_duration_since(previous) < AUDIO_LEVEL_INTERVAL) {
        return false;
    }
    *last = Some(now);
    true
}

fn visual_window_active(visible: bool, minimized: bool) -> bool {
    visible && !minimized
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
struct PairingCompletedInfo {
    device_name: String,
}

#[derive(Serialize, Clone, Debug)]
struct ServerFault {
    component: String,
    message: String,
}

impl TauriEventSink {
    pub fn new(app: AppHandle) -> Self {
        Self {
            app,
            last_audio_level: Mutex::new(None),
        }
    }

    fn emit<S: Serialize + Clone>(&self, event: &str, payload: S) {
        if let Err(e) = self.app.emit(event, payload) {
            log::warn!("failed to emit {event}: {e}");
        }
    }

    // Visual telemetry is not session state. Hidden/minimized windows do not
    // need high-rate IPC; connection, security, mute and tray events still flow.
    fn emit_main_visual<S: Serialize + Clone>(&self, event: &str, payload: S) {
        if let Some(main) = self.app.get_webview_window("main") {
            if visual_window_active(
                main.is_visible().unwrap_or(false),
                main.is_minimized().unwrap_or(true),
            ) {
                if let Err(e) = main.emit(event, payload) {
                    log::warn!("failed to emit {event}: {e}");
                }
            }
        }
    }
}

impl ServerEvents for TauriEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        self.emit("device-connected", info);
    }
    fn device_disconnected(&self) {
        self.emit("device-disconnected", ());
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        self.emit_main_visual("audio-metrics", metrics);
    }
    fn udp_audio_warning(&self) {
        self.emit("udp_audio_warning", ());
    }
    fn mute_state_changed(&self, is_muted: bool) {
        self.emit("mute-state-changed", is_muted);
    }
    fn audio_level(&self, level: u32) {
        let allowed = take_audio_level_slot(
            &mut self
                .last_audio_level
                .lock()
                .unwrap_or_else(|p| p.into_inner()),
            Instant::now(),
        );
        if allowed {
            self.emit_main_visual("audio-level", level);
        }
    }
    fn audio_spectrum(&self, spectrum: SpectrumPayload) {
        // High-rate payload: only the main window draws the spectrum.
        if let Some(main) = self.app.get_webview_window("main") {
            if let Err(e) = main.emit("audio-spectrum", spectrum) {
                log::warn!("failed to emit audio-spectrum: {e}");
            }
        }
    }
    fn server_stopped(&self) {
        self.emit("server-stopped", ());
    }
    fn web_client_count(&self, count: u32) {
        self.emit("web-client-count", count);
    }
    fn install_progress(&self, message: String) {
        self.emit("vbcable-install-progress", message);
    }
    fn aec_status_changed(&self, status: AecStatus) {
        self.emit("aec-status-changed", status);
    }
    fn monitoring_state_changed(&self, enabled: bool) {
        self.emit("monitoring-enabled-changed", enabled);
    }
    fn plugin_download_progress(&self, progress: DownloadProgress) {
        self.emit("plugin-download-progress", progress);
    }
    fn server_fault(&self, component: String, message: String) {
        self.emit("server-fault", ServerFault { component, message });
    }
    fn pairing_requested(&self, request: PairingRequestInfo) {
        self.emit("pairing-requested", request);
    }
    fn pairing_completed(&self, device_name: String) {
        self.emit("pairing-completed", PairingCompletedInfo { device_name });
    }
    fn session_security_changed(&self, encrypted: bool) {
        self.emit(
            "session-security",
            micyou_core::pairing::SessionSecurityState { encrypted },
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audio_level_ipc_is_limited_without_delaying_the_first_sample() {
        let start = Instant::now();
        let mut last = None;
        assert!(take_audio_level_slot(&mut last, start));
        assert!(!take_audio_level_slot(
            &mut last,
            start + Duration::from_millis(74)
        ));
        assert!(take_audio_level_slot(
            &mut last,
            start + AUDIO_LEVEL_INTERVAL
        ));
        assert!(!take_audio_level_slot(
            &mut last,
            start + Duration::from_millis(100)
        ));
        assert!(take_audio_level_slot(
            &mut last,
            start + Duration::from_secs(15)
        ));
    }

    #[test]
    fn visual_telemetry_requires_a_visible_non_minimized_window() {
        assert!(visual_window_active(true, false));
        assert!(!visual_window_active(false, false));
        assert!(!visual_window_active(true, true));
        assert!(!visual_window_active(false, true));
    }

    #[test]
    fn pairing_events_match_typed_frontend_keys() {
        let request = serde_json::to_value(PairingRequestInfo {
            request_id: "connection-1".into(),
            device_name: "Phone".into(),
            sas: "123456".into(),
        })
        .unwrap();
        assert_eq!(request["requestId"], "connection-1");
        assert_eq!(request["deviceName"], "Phone");
        assert!(request.get("request_id").is_none());
        let completed = serde_json::to_value(PairingCompletedInfo {
            device_name: "Phone".into(),
        })
        .unwrap();
        assert_eq!(completed["deviceName"], "Phone");
    }
}
