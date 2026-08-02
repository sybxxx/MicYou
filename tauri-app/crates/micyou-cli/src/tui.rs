use crate::events::Event;
use crate::i18n;
use crate::theme::{self, Rgba, Theme};
use crossterm::event::{self, Event as CrosstermEvent, KeyCode, KeyEvent, KeyModifiers};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use micyou_audio::dsp::AudioDspSettings;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Gauge, List, ListItem, Paragraph, Tabs};
use ratatui::Frame;
use std::collections::VecDeque;
use std::io::stdout;
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use tauri_app_lib::app_config::ServerPrefs;
use tauri_app_lib::server::ServerState;
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

pub struct TuiApp {
    pub tab: usize,
    pub port: u16,
    pub mode: String,
    pub device: Option<DeviceInfo>,
    pub metrics: Option<AudioMetrics>,
    pub level: u32,
    pub muted: bool,
    pub web_clients: u32,
    pub settings: AudioDspSettings,
    pub selected_setting: usize,
    pub chain_index: usize,
    pub logs: VecDeque<String>,
    pub last_event: String,
    /// Processed spectrum (64 bands, 0..1) for the cava-style visualizer.
    pub spectrum: Vec<f32>,
    pub lang: String,
    pub theme: Theme,
    /// Local IPs (sorted, virtual adapters filtered) shown as connect hints.
    pub ips: Vec<String>,
    /// Shared connection settings (server.json), editable on the Connection tab.
    pub prefs: ServerPrefs,
    /// Selected row on the Connection tab.
    pub selected_conn: usize,
}

impl TuiApp {
    pub fn new(settings: AudioDspSettings, port: u16, mode: String) -> Self {
        let lang = i18n::detect_lang();
        let ips = tauri_app_lib::server::query_network_interfaces()
            .into_iter()
            .map(|i| i.ip)
            .filter(|ip| !ip.is_empty())
            .take(4)
            .collect();
        Self {
            tab: 0,
            port,
            mode,
            device: None,
            metrics: None,
            level: 0,
            muted: false,
            web_clients: 0,
            settings,
            selected_setting: 0,
            chain_index: 0,
            logs: VecDeque::from(["[tui] started".to_string()]),
            last_event: String::new(),
            spectrum: vec![0.0; 64],
            lang,
            theme: theme::load(),
            ips,
            prefs: tauri_app_lib::app_config::load_server_prefs(),
            selected_conn: 0,
        }
    }

    fn t(&self, key: &str) -> String {
        i18n::tr(&self.lang, key)
    }

    pub fn on_event(&mut self, ev: Event) {
        match ev {
            Event::DeviceConnected(info) => {
                let name = info.name.clone();
                self.device = Some(info);
                self.log(format!("[mic] {}: {name}", self.t("connected")));
            }
            Event::DeviceDisconnected => {
                self.device = None;
                self.log("[mic] disconnected".to_string());
            }
            Event::Metrics(m) => self.metrics = Some(m),
            Event::UdpWarning => self.log("[warn] UDP audio stalled".to_string()),
            Event::MuteChanged(muted) => {
                self.muted = muted;
                self.log(format!("[mic] muted: {muted}"));
            }
            Event::Level(level) => self.level = level,
            Event::Spectrum(_raw, processed) => {
                if processed.len() >= 64 {
                    self.spectrum.clone_from(&processed);
                }
            }
            Event::Stopped => {
                self.log("[server] stopped".to_string());
            }
            Event::WebClientCount(count) => {
                self.web_clients = count;
                self.log(format!("[web] clients: {count}"));
            }
            Event::InstallProgress(msg) => self.log(format!("[install] {msg}")),
        }
    }

    fn log(&mut self, line: String) {
        self.last_event.clone_from(&line);
        self.logs.push_back(line);
        while self.logs.len() > 200 {
            self.logs.pop_front();
        }
    }

    fn tabs(&self) -> Vec<String> {
        vec![
            self.t("tab_dashboard"),
            self.t("tab_audio"),
            self.t("tab_chain"),
            self.t("tab_conn"),
            self.t("tab_logs"),
        ]
    }

    pub fn render(&mut self, frame: &mut Frame, state: &ServerState) {
        let area = frame.area();
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(3),
                Constraint::Length(1),
                Constraint::Min(3),
                Constraint::Length(1),
            ])
            .split(area);

        let theme = self.theme;
        let title = Line::from(vec![
            Span::styled(
                " MicYou ",
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color())
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw(format!("  {} ", self.t("app_title"))),
            Span::styled(
                format!("({})", std::env::consts::OS),
                Style::default().fg(theme.secondary.to_color()),
            ),
        ]);
        frame.render_widget(Paragraph::new(title), chunks[0]);

        let tabs = Tabs::new(self.tabs())
            .select(self.tab)
            .block(Block::default().borders(Borders::NONE))
            .style(Style::default().fg(theme.primary.to_color()))
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color())
                    .add_modifier(Modifier::BOLD),
            );
        frame.render_widget(tabs, chunks[1]);

        match self.tab {
            0 => self.render_dashboard(frame, chunks[2], state),
            1 => self.render_settings(frame, chunks[2]),
            2 => self.render_chain(frame, chunks[2]),
            3 => self.render_connection(frame, chunks[2]),
            _ => self.render_logs(frame, chunks[2]),
        }

        let footer = Line::from(vec![
            Span::styled(" q ", Style::default().fg(Color::Black).bg(theme.error.to_color())),
            Span::raw(self.t("quit_hint")),
            Span::raw("  "),
            Span::styled(" Tab ", Style::default().fg(Color::Black).bg(theme.secondary.to_color())),
            Span::raw(self.t("tab_switch")),
            Span::raw("  "),
            Span::styled(" ↑↓ ", Style::default().fg(Color::Black).bg(theme.secondary.to_color())),
            Span::raw(self.t("nav")),
            Span::raw("  "),
            Span::styled(" Enter ", Style::default().fg(Color::Black).bg(theme.primary.to_color())),
            Span::raw(self.t("toggle")),
            Span::raw("  "),
            Span::styled(" -/+ ", Style::default().fg(Color::Black).bg(theme.primary.to_color())),
            Span::raw(self.t("adjust")),
            Span::raw("  "),
            Span::raw(&self.last_event),
        ]);
        frame.render_widget(Paragraph::new(footer), chunks[3]);
    }

    fn mode_label(&self) -> String {
        match self.mode.as_str() {
            "wifi" => self.t("mode_wifi"),
            "usb" => self.t("mode_usb"),
            "web" => self.t("mode_web"),
            _ => self.t("mode_unknown"),
        }
    }

    fn render_dashboard(&self, frame: &mut Frame, area: Rect, state: &ServerState) {
        let theme = self.theme;
        let chunks = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(55), Constraint::Percentage(45)])
            .split(area);

        // Left column: status list on top, spectrum below (length sized so the
        // IP rows are never clipped; spectrum takes the rest)
        let left_col = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(11), Constraint::Min(3)])
            .split(chunks[0]);

        // Left: server + device status
        let mut left_items = vec![
            ListItem::new(Line::from(vec![
                Span::raw(format!("{}: ", self.t("state"))),
                Span::styled(
                    self.t("server_running"),
                    Style::default().fg(theme.primary.to_color()),
                ),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!("{}: ", self.t("mode"))),
                Span::styled(self.mode_label(), Style::default().fg(theme.tertiary.to_color())),
                Span::raw(format!("  {}: ", self.t("listening"))),
                Span::styled(
                    format!("{} {}", self.t("port"), self.port),
                    Style::default().fg(theme.secondary.to_color()),
                ),
            ])),
            ListItem::new(""),
        ];
        match &self.device {
            Some(device) => {
                left_items.push(ListItem::new(Line::from(vec![
                    Span::styled(
                        format!("{}: ", self.t("device")),
                        Style::default().add_modifier(Modifier::BOLD),
                    ),
                    Span::styled(&device.name, Style::default().fg(theme.primary.to_color())),
                ])));
                left_items.push(ListItem::new(format!(
                    "  ip: {}  {}: {}ms",
                    device.ip,
                    self.t("latency"),
                    device.latency
                )));
            }
            None => {
                left_items.push(ListItem::new(Line::from(vec![
                    Span::styled(
                        format!("{}: ", self.t("device")),
                        Style::default().add_modifier(Modifier::BOLD),
                    ),
                    Span::styled(
                        self.t("device_not_connected"),
                        Style::default().fg(theme.error.to_color()),
                    ),
                ])));
            }
        }
        left_items.push(ListItem::new(format!(
            "{}: {}  {}: {}",
            self.t("muted"),
            self.muted,
            self.t("web_clients"),
            self.web_clients
        )));

        // Local IPs as connect hints
        if !self.ips.is_empty() {
            left_items.push(ListItem::new(""));
            left_items.push(ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{}: ", self.t("local_ips")),
                    Style::default().add_modifier(Modifier::BOLD),
                ),
                Span::styled(
                    self.ips.join(", "),
                    Style::default().fg(theme.tertiary.to_color()),
                ),
            ])));
        }

        let left = List::new(left_items)
            .block(Block::default().borders(Borders::ALL).title(self.t("state")));
        frame.render_widget(left, left_col[0]);

        // Cava-style spectrum
        self.render_spectrum(frame, left_col[1]);

        // Right column: level gauge on top, metrics below
        let right_col = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(4), Constraint::Min(3)])
            .split(chunks[1]);

        let gauge = Gauge::default()
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(self.t("input_level")),
            )
            .gauge_style(Style::default().fg(if self.level > 80 {
                theme.error.to_color()
            } else {
                theme.primary.to_color()
            }))
            .ratio(f64::from(self.level.min(100)) / 100.0)
            .label(format!("{}", self.level));
        frame.render_widget(gauge, right_col[0]);

        // Metrics
        let mut rows = vec![
            self.t("metric"),
            "─────".to_string(),
            format!("{}:   -", self.t("bitrate")),
            format!("{}:   -", self.t("sample_rate")),
            format!("{}:   -", self.t("latency")),
            format!("{}: -", self.t("network_latency")),
            format!("{}:     -", self.t("jitter")),
            format!("{}:   -", self.t("packet_loss")),
            format!("{}:     -", self.t("buffer")),
        ];
        if let Some(m) = &self.metrics {
            rows[2] = format!("{}:   {} kbps", self.t("bitrate"), m.bitrate / 1000);
            rows[3] = format!("{}:   {} Hz", self.t("sample_rate"), m.sample_rate);
            rows[4] = format!("{}:   {} ms", self.t("latency"), m.latency_ms);
            rows[5] = format!("{}: {} ms", self.t("network_latency"), m.network_latency_ms);
            rows[6] = format!("{}:     {:.1} ms", self.t("jitter"), m.jitter_ms);
            rows[7] = format!("{}:   {:.2}%", self.t("packet_loss"), m.packet_loss_rate * 100.0);
            rows[8] = format!("{}:     {} ms", self.t("buffer"), m.buffer_duration_ms);
        }
        let right = List::new(rows)
            .block(Block::default().borders(Borders::ALL).title(self.t("audio_metrics")));
        frame.render_widget(right, right_col[1]);

        let _ = state;
    }

    /// Cava-style vertical bars: each column's height = spectrum band value.
    /// Color follows the theme gradient bottom (cool) to top (warm).
    fn render_spectrum(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let width = area.width as usize;
        let height = area.height as usize;
        if width == 0 || height == 0 {
            return;
        }
        let n_cols = width.min(64).max(4);
        // Downsample the 64 bands to the available column count
        let bands: Vec<f32> = (0..n_cols)
            .map(|i| {
                let src = i * 64 / n_cols;
                self.spectrum.get(src).copied().unwrap_or(0.0).min(1.0)
            })
            .collect();

        let block = Block::default().borders(Borders::ALL).title(self.t("spectrum"));
        let idle = bands.iter().all(|v| *v < 0.01);
        if idle && height >= 3 {
            // Placeholder so the block does not look broken while idle
            let mut lines: Vec<Line> = vec![Line::from("")];
            let text = format!("  {} ", self.t("spectrum_wait"));
            lines.push(Line::from(Span::styled(
                text,
                Style::default().fg(theme.secondary.to_color()),
            )));
            while lines.len() < height {
                lines.push(Line::from(""));
            }
            frame.render_widget(Paragraph::new(lines).block(block), area);
            return;
        }

        let mut lines: Vec<Line> = Vec::with_capacity(height);
        for row in 0..height {
            // row 0 = top; bars grow from the bottom
            let threshold = (height - 1 - row) as f32 / (height - 1).max(1) as f32;
            let t = (height - 1 - row) as f32 / (height - 1).max(1) as f32;
            let color = gradient_at(self.theme.gradient, t).to_color();
            let mut text = String::with_capacity(width);
            for v in &bands {
                text.push(if *v >= threshold { '█' } else { ' ' });
            }
            lines.push(Line::from(Span::styled(
                text,
                Style::default().fg(color),
            )));
        }
        frame.render_widget(Paragraph::new(lines).block(block), area);
    }

    fn render_settings(&mut self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let items: Vec<ListItem> = vec![
            ListItem::new(format!(
                "{} (Gain)                    {:.1} dB",
                self.t("gain"),
                self.settings.gain
            )),
            ListItem::new(format!(
                "{} (AEC)                {}",
                self.t("aec"),
                on_off(&self.lang, self.settings.aec_enabled)
            )),
            ListItem::new(format!(
                "{} (Noise Reduction)    {}",
                self.t("noise_reduction"),
                on_off(&self.lang, self.settings.ns_enabled)
            )),
            ListItem::new(format!(
                "{} (Dereverb)             {}",
                self.t("dereverb"),
                on_off(&self.lang, self.settings.dereverb_enabled)
            )),
            ListItem::new(format!(
                "{} (AGC)                {}",
                self.t("agc"),
                on_off(&self.lang, self.settings.agc_enabled)
            )),
            ListItem::new(format!(
                "{} (VAD)                {}",
                self.t("vad"),
                on_off(&self.lang, self.settings.vad_enabled)
            )),
            ListItem::new(format!(
                "{} (Output Buffer)    {} {}",
                self.t("output_buffer"),
                self.settings.output_buffer_ms,
                self.t("ms")
            )),
        ];
        let list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(self.t("audio_params_title")),
            )
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color()),
            )
            .highlight_symbol("> ");
        frame.render_stateful_widget(
            list,
            area,
            &mut ratatui::widgets::ListState::default().with_selected(Some(self.selected_setting)),
        );
    }

    fn render_chain(&mut self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let items: Vec<ListItem> = self
            .settings
            .processing_chain
            .iter()
            .enumerate()
            .map(|(i, stage)| {
                let label = match stage.as_str() {
                    "AEC" => format!("{} (AEC) 🔒", self.t("aec")),
                    "NoiseReduction" => format!("{} (NoiseReduction)", self.t("noise_reduction")),
                    "Dereverb" => format!("{} (Dereverb)", self.t("dereverb")),
                    "Equalizer" => "Equalizer".to_string(),
                    "Amplifier" => format!("{} (Amplifier)", self.t("gain")),
                    "AGC" => format!("{} (AGC)", self.t("agc")),
                    "VAD" => format!("{} (VAD)", self.t("vad")),
                    other => other.to_string(),
                };
                if i == 0 && stage == "AEC" {
                    ListItem::new(format!("{i}. {label}"))
                        .style(Style::default().fg(theme.tertiary.to_color()))
                } else {
                    ListItem::new(format!("{i}. {label}"))
                }
            })
            .collect();
        let list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(self.t("chain_title")),
            )
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color()),
            )
            .highlight_symbol("> ");
        frame.render_stateful_widget(
            list,
            area,
            &mut ratatui::widgets::ListState::default().with_selected(Some(self.chain_index)),
        );
    }

    /// Connection settings tab: edit shared server.json prefs.
    fn render_connection(&mut self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let mode_label = match self.prefs.mode.as_str() {
            "wifi" => self.t("mode_wifi"),
            "usb" => self.t("mode_usb"),
            "web" => self.t("mode_web"),
            _ => self.t("mode_unknown"),
        };
        let bind_label = if self.prefs.auto_bind {
            self.t("conn_auto")
        } else {
            format!("{} ({})", self.t("conn_manual"), self.prefs.bind_address)
        };
        let items: Vec<ListItem> = vec![
            ListItem::new(format!(
                "{}:  {}",
                self.t("conn_mode"),
                mode_label
            )),
            ListItem::new(format!(
                "{}:  {}",
                self.t("conn_port"),
                self.prefs.port
            )),
            ListItem::new(format!(
                "{}:  {}",
                self.t("conn_web_port"),
                self.prefs.web_port
            )),
            ListItem::new(format!("{}:  {}", self.t("conn_bind"), bind_label)),
            ListItem::new(format!(
                "{}:  {}",
                self.t("conn_device"),
                if self.prefs.output_device.is_empty() {
                    self.t("none")
                } else {
                    self.prefs.output_device.clone()
                }
            )),
            ListItem::new(""),
            ListItem::new(Line::from(Span::styled(
                self.t("conn_hint"),
                Style::default().fg(theme.secondary.to_color()),
            ))),
        ];
        let list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(self.t("tab_conn")),
            )
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color()),
            )
            .highlight_symbol("> ");
        frame.render_stateful_widget(
            list,
            area,
            &mut ratatui::widgets::ListState::default().with_selected(Some(self.selected_conn)),
        );
    }

    fn render_logs(&self, frame: &mut Frame, area: Rect) {
        let items: Vec<ListItem> = self
            .logs
            .iter()
            .rev()
            .take(20)
            .map(|l| ListItem::new(l.clone()))
            .collect();
        let list = List::new(items)
            .block(Block::default().borders(Borders::ALL).title(self.t("logs")));
        frame.render_widget(list, area);
    }
}

/// Pick a gradient stop for normalized position t in [0, 1].
fn gradient_at(gradient: [Rgba; 8], t: f32) -> Rgba {
    let idx = ((t.clamp(0.0, 1.0)) * 7.0).round() as usize;
    gradient[idx.min(7)]
}

fn on_off(lang: &str, v: bool) -> String {
    if v {
        i18n::tr(lang, "enabled")
    } else {
        i18n::tr(lang, "disabled")
    }
}

/// Enter raw terminal mode; returns a guard that restores it on drop.
pub fn enter() -> Result<(), String> {
    enable_raw_mode().map_err(|e| e.to_string())?;
    execute!(stdout(), EnterAlternateScreen).map_err(|e| e.to_string())?;
    Ok(())
}

pub fn leave() -> Result<(), String> {
    execute!(stdout(), LeaveAlternateScreen).map_err(|e| e.to_string())?;
    disable_raw_mode().map_err(|e| e.to_string())
}

/// Run the TUI dashboard until the user quits (q / Ctrl+C).
/// `state` gives live access to the DSP settings and spectrum flag.
pub fn run_tui(
    rx: Receiver<Event>,
    state: Arc<ServerState>,
    port: u16,
    mode: String,
) -> Result<(), String> {
    enter()?;

    let settings = state
        .dsp_settings
        .read()
        .map(|s| s.clone())
        .unwrap_or_default();
    let mut app = TuiApp::new(settings, port, mode);
    let mut terminal = ratatui::Terminal::new(ratatui::backend::CrosstermBackend::new(stdout()))
        .map_err(|e| e.to_string())?;

    let result = (|| -> Result<(), String> {
        loop {
            terminal
                .draw(|frame| app.render(frame, &state))
                .map_err(|e| e.to_string())?;

            if crossterm::event::poll(std::time::Duration::from_millis(100))
                .map_err(|e| e.to_string())?
            {
                if let CrosstermEvent::Key(key) = event::read().map_err(|e| e.to_string())? {
                    if handle_key(&mut app, key, &state) {
                        break;
                    }
                }
            }

            // Drain incoming server events
            while let Ok(ev) = rx.try_recv() {
                app.on_event(ev);
            }
        }
        Ok(())
    })();

    leave()?;
    result
}

fn handle_key(app: &mut TuiApp, key: KeyEvent, state: &ServerState) -> bool {
    match key.code {
        KeyCode::Char('q') | KeyCode::Char('Q') => return true,
        KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => return true,
        KeyCode::Tab => {
            app.tab = (app.tab + 1) % 4;
        }
        KeyCode::BackTab => {
            app.tab = (app.tab + 3) % 4;
        }
        KeyCode::Left => {
            app.tab = (app.tab + 3) % 4;
        }
        KeyCode::Right => {
            app.tab = (app.tab + 1) % 4;
        }
        KeyCode::Up => match app.tab {
            1 => app.selected_setting = app.selected_setting.saturating_sub(1),
            3 => app.selected_conn = app.selected_conn.saturating_sub(1),
            2 => {
                let chain = &mut app.settings.processing_chain;
                if app.chain_index > 1 {
                    chain.swap(app.chain_index, app.chain_index - 1);
                    app.chain_index -= 1;
                    sync_chain(&app.settings, state);
                }
            }
            _ => {}
        },
        KeyCode::Down => match app.tab {
            1 => {
                app.selected_setting = (app.selected_setting + 1).min(6);
            }
            3 => {
                app.selected_conn = (app.selected_conn + 1).min(4);
            }
            2 => {
                let chain = &mut app.settings.processing_chain;
                if app.chain_index + 1 < chain.len() && app.chain_index >= 1 {
                    chain.swap(app.chain_index, app.chain_index + 1);
                    app.chain_index += 1;
                    sync_chain(&app.settings, state);
                }
            }
            _ => {}
        },
        KeyCode::Enter => {
            if app.tab == 1 {
                match app.selected_setting {
                    0 => {}
                    1 => app.settings.aec_enabled = !app.settings.aec_enabled,
                    2 => app.settings.ns_enabled = !app.settings.ns_enabled,
                    3 => app.settings.dereverb_enabled = !app.settings.dereverb_enabled,
                    4 => app.settings.agc_enabled = !app.settings.agc_enabled,
                    5 => app.settings.vad_enabled = !app.settings.vad_enabled,
                    _ => {}
                }
                sync_settings(&app.settings, state);
            } else if app.tab == 3 {
                match app.selected_conn {
                    // Mode: cycle wifi -> usb -> web -> wifi
                    0 => {
                        app.prefs.mode = match app.prefs.mode.as_str() {
                            "wifi" => "usb".to_string(),
                            "usb" => "web".to_string(),
                            _ => "wifi".to_string(),
                        };
                        app.mode.clone_from(&app.prefs.mode);
                        sync_server_prefs(app);
                    }
                    // Bind: toggle auto/manual
                    3 => {
                        app.prefs.auto_bind = !app.prefs.auto_bind;
                        if !app.prefs.auto_bind && app.prefs.bind_address.is_empty() {
                            app.prefs.bind_address = "0.0.0.0".to_string();
                        }
                        sync_server_prefs(app);
                    }
                    _ => {}
                }
            }
        }
        KeyCode::Char('-') | KeyCode::Char('_') => {
            if app.tab == 1 {
                match app.selected_setting {
                    0 => app.settings.gain = (app.settings.gain - 1.0).clamp(-50.0, 50.0),
                    6 => {
                        app.settings.output_buffer_ms =
                            (app.settings.output_buffer_ms.saturating_sub(100)).clamp(100, 1200);
                    }
                    _ => {}
                }
                sync_settings(&app.settings, state);
            } else if app.tab == 3 {
                match app.selected_conn {
                    1 => {
                        app.prefs.port = app.prefs.port.saturating_sub(10).max(1024);
                        app.port = app.prefs.port;
                        sync_server_prefs(app);
                    }
                    2 => {
                        app.prefs.web_port = app.prefs.web_port.saturating_sub(10).max(1024);
                        sync_server_prefs(app);
                    }
                    _ => {}
                }
            }
        }
        KeyCode::Char('+') | KeyCode::Char('=') => {
            if app.tab == 1 {
                match app.selected_setting {
                    0 => app.settings.gain = (app.settings.gain + 1.0).clamp(-50.0, 50.0),
                    6 => {
                        app.settings.output_buffer_ms =
                            (app.settings.output_buffer_ms + 100).clamp(100, 1200);
                    }
                    _ => {}
                }
                sync_settings(&app.settings, state);
            } else if app.tab == 3 {
                match app.selected_conn {
                    1 => {
                        app.prefs.port = app.prefs.port.saturating_add(10);
                        app.port = app.prefs.port;
                        sync_server_prefs(app);
                    }
                    2 => {
                        app.prefs.web_port = app.prefs.web_port.saturating_add(10);
                        sync_server_prefs(app);
                    }
                    _ => {}
                }
            }
        }
        _ => {}
    }
    false
}

/// Persist settings to the CLI config file and apply to the running DSP.
fn sync_settings(settings: &AudioDspSettings, state: &ServerState) {
    if let Ok(mut lock) = state.dsp_settings.write() {
        *lock = settings.clone();
    }
    let _ = crate::config::save_settings(settings);
}

fn sync_chain(settings: &AudioDspSettings, state: &ServerState) {
    sync_settings(settings, state);
}

/// Persist the edited connection settings to the shared server.json.
fn sync_server_prefs(app: &TuiApp) {
    let _ = tauri_app_lib::app_config::save_server_prefs(&app.prefs);
}
