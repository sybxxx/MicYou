use crate::config;
use crate::events::{CliEventSink, Event, TuiEventSink};
use tauri_app_lib::mode_lock::RunMode;
use std::sync::mpsc::channel;
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
/// With `--tui` this runs the ratatui dashboard; otherwise it prints plain logs.
pub async fn run(args: ServeArgs) -> Result<(), String> {
    tauri_app_lib::mode_lock::acquire(RunMode::Cli)?;

    let state = build_state();
    let (tx, rx) = channel::<Event>();
    let events: Arc<dyn tauri_app_lib::events::ServerEvents> = if args.no_tui {
        Arc::new(CliEventSink)
    } else {
        Arc::new(TuiEventSink(tx))
    };

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
            tauri_app_lib::mode_lock::release();
            return Err(e);
        }
    }

    if args.no_tui {
        println!("Press Ctrl+C to stop");
        let _ = tokio::signal::ctrl_c().await;
        println!("Stopping server...");
        let _ = stop_server_inner(&state, events).await;
    } else {
        let tui_result = crate::tui::run_tui(rx, state.clone(), args.port);
        let _ = stop_server_inner(&state, events).await;
        tui_result?;
    }
    tauri_app_lib::mode_lock::release();
    Ok(())
}

/// Build a ServerState from the CLI settings file.
pub fn build_state() -> Arc<ServerState> {
    let settings = config::load_settings();
    Arc::new(ServerState {
        dsp_settings: Arc::new(std::sync::RwLock::new(settings)),
        is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        spectrum_streaming_enabled: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        ..ServerState::default()
    })
}
