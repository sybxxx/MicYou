use crate::config;
use crate::events::CliEventSink;
use crate::lock::{self, RunMode};
use std::sync::Arc;
use tauri_app_lib::commands::system::{start_server_inner, stop_server_inner};
use tauri_app_lib::server::ServerState;

pub struct ServeArgs {
    pub port: u16,
    pub mode: String,
    pub device: Option<String>,
    pub bind: Option<String>,
    pub no_tui: bool,
}

/// Run the audio server in the foreground.
/// Phase 2: plain log output. Phase 3 will switch to the ratatui dashboard
/// unless `--no-tui` is passed.
pub async fn run(args: ServeArgs) -> Result<(), String> {
    lock::acquire(RunMode::Cli)?;

    let state = build_state();
    let events: Arc<dyn tauri_app_lib::events::ServerEvents> = Arc::new(CliEventSink);

    let result = start_server_inner(
        &state,
        args.port,
        args.mode,
        args.bind,
        args.device,
        events.clone(),
    )
    .await;

    match result {
        Ok(message) => println!("{message}"),
        Err(e) => {
            lock::release();
            return Err(e);
        }
    }

    println!("Press Ctrl+C to stop");
    let _ = tokio::signal::ctrl_c().await;
    println!("Stopping server...");
    let _ = stop_server_inner(&state, events).await;
    lock::release();
    Ok(())
}

/// Build a ServerState from the CLI settings file.
pub fn build_state() -> ServerState {
    let settings = config::load_settings();
    ServerState {
        dsp_settings: Arc::new(std::sync::RwLock::new(settings)),
        is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        spectrum_streaming_enabled: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        ..ServerState::default()
    }
}
