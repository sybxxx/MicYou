use std::sync::mpsc::Sender;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri_app_lib::events::{AecStatus, ServerEvents, ServerFault};
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

/// Server events consumed by the interactive terminal UI.
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
    AecStatus(AecStatus),
    ServerFault(ServerFault),
}

/// Forward server events to the TUI channel while throttling high-frequency
/// audio visualization updates to the terminal's useful refresh rate.
pub struct TuiEventSink {
    tx: Sender<Event>,
    last_level: Mutex<Instant>,
    last_spectrum: Mutex<Instant>,
}

impl TuiEventSink {
    pub fn new(tx: Sender<Event>) -> Self {
        let past = Instant::now() - Duration::from_secs(10);
        Self {
            tx,
            last_level: Mutex::new(past),
            last_spectrum: Mutex::new(past),
        }
    }

    fn send_event(&self, event: Event) {
        if let Err(error) = self.tx.send(event) {
            eprintln!("[events] failed to forward event to TUI: {error}");
        }
    }
}

impl ServerEvents for TuiEventSink {
    fn device_connected(&self, info: DeviceInfo) {
        self.send_event(Event::DeviceConnected(info));
    }

    fn device_disconnected(&self) {
        self.send_event(Event::DeviceDisconnected);
    }

    fn audio_metrics(&self, metrics: AudioMetrics) {
        self.send_event(Event::Metrics(metrics));
    }

    fn udp_audio_warning(&self) {
        self.send_event(Event::UdpWarning);
    }

    fn mute_state_changed(&self, is_muted: bool) {
        self.send_event(Event::MuteChanged(is_muted));
    }

    fn audio_level(&self, level: u32) {
        let mut last = self.last_level.lock().unwrap();
        if last.elapsed() >= Duration::from_millis(70) {
            *last = Instant::now();
            drop(last);
            self.send_event(Event::Level(level));
        }
    }

    fn audio_spectrum(&self, raw: Vec<f32>, processed: Vec<f32>) {
        let mut last = self.last_spectrum.lock().unwrap();
        if last.elapsed() >= Duration::from_millis(70) {
            *last = Instant::now();
            drop(last);
            self.send_event(Event::Spectrum(raw, processed));
        }
    }

    fn server_stopped(&self) {
        self.send_event(Event::Stopped);
    }

    fn server_fault(&self, component: String, message: String) {
        self.send_event(Event::ServerFault(ServerFault { component, message }));
    }

    fn web_client_count(&self, count: u32) {
        self.send_event(Event::WebClientCount(count));
    }

    fn install_progress(&self, message: String) {
        self.send_event(Event::InstallProgress(message));
    }

    fn aec_status_changed(&self, status: AecStatus) {
        self.send_event(Event::AecStatus(status));
    }
}
