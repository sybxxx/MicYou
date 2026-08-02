use std::sync::mpsc::Sender;
use tauri_app_lib::events::ServerEvents;
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

/// Log-mode events: print a compact line per event.
pub struct CliEventSink;

impl ServerEvents for CliEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        println!("[mic] connected: {} ({})", info.name, info.ip);
    }
    fn device_disconnected(&self) {
        println!("[mic] disconnected");
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        println!(
            "[stats] latency {} ms (network {} ms) buffer {} ms jitter {:.1} ms loss {:.2}%",
            metrics.latency_ms,
            metrics.network_latency_ms,
            metrics.buffer_duration_ms,
            metrics.jitter_ms,
            metrics.packet_loss_rate * 100.0
        );
    }
    fn udp_audio_warning(&self) {
        println!("[warn] no UDP audio for a while - check network connection");
    }
    fn mute_state_changed(&self, is_muted: bool) {
        println!("[mic] muted: {is_muted}");
    }
    fn audio_level(&self, level: u32) {
        println!("[level] {level}");
    }
    fn audio_spectrum(&self, _raw: Vec<f32>, _processed: Vec<f32>) {}
    fn server_stopped(&self) {
        println!("[server] stopped");
    }
    fn web_client_count(&self, count: u32) {
        println!("[web] clients: {count}");
    }
    fn install_progress(&self, message: String) {
        println!("[install] {message}");
    }
}

/// TUI-mode events: forward to the TUI channel (Phase 3).
pub struct TuiEventSink(pub Sender<Event>);

#[derive(Debug, Clone)]
pub enum Event {
    DeviceConnected(DeviceInfo),
    DeviceDisconnected,
    Metrics(AudioMetrics),
    UdpWarning,
    MuteChanged(bool),
    Level(u32),
    Spectrum(Vec<f32>, Vec<f32>),
    Stopped,
    WebClientCount(u32),
    InstallProgress(String),
}

impl ServerEvents for TuiEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        let _ = self.0.send(Event::DeviceConnected(info));
    }
    fn device_disconnected(&self) {
        let _ = self.0.send(Event::DeviceDisconnected);
    }
    fn audio_metrics(&self, metrics: AudioMetrics) {
        let _ = self.0.send(Event::Metrics(metrics));
    }
    fn udp_audio_warning(&self) {
        let _ = self.0.send(Event::UdpWarning);
    }
    fn mute_state_changed(&self, is_muted: bool) {
        let _ = self.0.send(Event::MuteChanged(is_muted));
    }
    fn audio_level(&self, level: u32) {
        let _ = self.0.send(Event::Level(level));
    }
    fn audio_spectrum(&self, raw: Vec<f32>, processed: Vec<f32>) {
        let _ = self.0.send(Event::Spectrum(raw, processed));
    }
    fn server_stopped(&self) {
        let _ = self.0.send(Event::Stopped);
    }
    fn web_client_count(&self, count: u32) {
        let _ = self.0.send(Event::WebClientCount(count));
    }
    fn install_progress(&self, message: String) {
        let _ = self.0.send(Event::InstallProgress(message));
    }
}
