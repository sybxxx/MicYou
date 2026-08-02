use crate::events::Event;
use crate::i18n;
use crate::theme::{self, Rgba, Theme};
use crossterm::event::{
    self, Event as CrosstermEvent, KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEvent,
    MouseEventKind as K,
};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use micyou_audio::dsp::AudioDspSettings;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, List, ListItem, Paragraph};
use ratatui::Frame;
use std::collections::VecDeque;
use std::io::stdout;
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use tauri_app_lib::app_config::ServerPrefs;
use tauri_app_lib::server::ServerState;
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

/// Vertical split of the whole screen: [title, tabs, body, footer].
fn split_layout(area: Rect) -> std::rc::Rc<[Rect]> {
    Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1),
            Constraint::Length(1),
            Constraint::Min(3),
            Constraint::Length(1),
        ])
        .split(area)
}

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
    /// Scroll offset for the logs page (0 = newest visible).
    pub log_offset: usize,
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
            logs: VecDeque::from(["[inf] tui started".to_string()]),
            last_event: String::new(),
            log_offset: 0,
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
                self.log(format!("[ok] {}: {name}", self.t("connected")));
            }
            Event::DeviceDisconnected => {
                self.device = None;
                self.log("[warn] mic disconnected".to_string());
            }
            Event::Metrics(m) => self.metrics = Some(m),
            Event::UdpWarning => self.log("[warn] UDP audio stalled".to_string()),
            Event::MuteChanged(muted) => {
                self.muted = muted;
                self.log(format!("[inf] muted: {muted}"));
            }
            Event::Level(level) => self.level = level,
            Event::Spectrum(_raw, processed) => {
                if processed.len() >= 64 {
                    self.spectrum.clone_from(&processed);
                }
            }
            Event::Stopped => {
                self.log("[err] server stopped".to_string());
            }
            Event::WebClientCount(count) => {
                self.web_clients = count;
                self.log(format!("[inf] web clients: {count}"));
            }
            Event::InstallProgress(msg) => self.log(format!("[inf] install: {msg}")),
        }
    }

    fn log(&mut self, line: String) {
        self.last_event.clone_from(&line);
        self.logs.push_back(line);
        while self.logs.len() > 500 {
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

    fn mode_label(&self) -> String {
        match self.mode.as_str() {
            "wifi" => self.t("mode_wifi"),
            "usb" => self.t("mode_usb"),
            "web" => self.t("mode_web"),
            _ => self.t("mode_unknown"),
        }
    }

    /// Hit-test the tab bar row: returns the tab index under (row, col), if any.
    /// The tab bar is rendered as "label │ label │ ..." starting at column 0.
    fn tab_hit(&self, col: u16) -> Option<usize> {
        // Rendering is " label " with a " │ " separator; account for the padding
        let mut x: u16 = 0;
        for (i, label) in self.tabs().iter().enumerate() {
            let w = label.chars().count() as u16 + 2;
            let end = x.saturating_add(w);
            if col >= x && col < end {
                return Some(i);
            }
            x = end.saturating_add(3); // " │ " separator
        }
        None
    }

    pub fn render(&mut self, frame: &mut Frame, state: &ServerState) {
        let chunks = split_layout(frame.area());
        self.render_title(frame, chunks[0]);
        self.render_tabs(frame, chunks[1]);
        match self.tab {
            0 => self.render_dashboard(frame, chunks[2], state),
            1 => self.render_settings(frame, chunks[2]),
            2 => self.render_chain(frame, chunks[2]),
            3 => self.render_connection(frame, chunks[2]),
            _ => self.render_logs(frame, chunks[2]),
        }
        self.render_footer(frame, chunks[3]);
    }

    /// Top bar: logo + title on the left, server status badge on the right.
    fn render_title(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let w = area.width as usize;
        let logo = Span::styled(
            " ● MicYou ",
            Style::default()
                .fg(Color::Black)
                .bg(theme.primary.to_color())
                .add_modifier(Modifier::BOLD),
        );
        let title = Span::raw(format!(" {} ", self.t("app_title")));
        let os = Span::styled(
            format!("({})", std::env::consts::OS),
            Style::default().fg(theme.secondary.to_color()),
        );
        let running = self.device.is_some() || self.level > 0;
        let badge = if running {
            Span::styled(
                format!(" ● {} ", self.t("server_running")),
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color())
                    .add_modifier(Modifier::BOLD),
            )
        } else {
            Span::styled(
                format!(" ○ {} ", self.t("server_running")),
                Style::default()
                    .fg(theme.on_surface.to_color())
                    .bg(theme.surface_variant.to_color()),
            )
        };
        let mode = Span::styled(
            format!(" {} {} ", self.mode_label(), self.port),
            Style::default().fg(theme.tertiary.to_color()),
        );
        let mut line = vec![logo, title, os];
        let used = 3 + self.t("app_title").chars().count() + 3 + 3 + 3;
        let badge_w = 4 + self.t("server_running").chars().count();
        let mode_w = 2 + self.mode_label().chars().count() + 1 + self.port.to_string().len() + 1;
        if used + badge_w + mode_w + 2 <= w {
            line.push(Span::raw(" ".repeat(w.saturating_sub(used + badge_w + mode_w))));
            line.push(badge);
            line.push(mode);
        }
        frame.render_widget(Paragraph::new(Line::from(line)), area);
    }

    /// Tab bar: each tab is a solid block when selected, plain text otherwise.
    fn render_tabs(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let mut spans: Vec<Span> = Vec::new();
        for (i, label) in self.tabs().iter().enumerate() {
            if i > 0 {
                spans.push(Span::raw(" │ "));
            }
            if i == self.tab {
                spans.push(Span::styled(
                    format!(" {} ", label),
                    Style::default()
                        .fg(Color::Black)
                        .bg(theme.primary.to_color())
                        .add_modifier(Modifier::BOLD),
                ));
            } else {
                spans.push(Span::styled(
                    format!(" {} ", label),
                    Style::default().fg(theme.secondary.to_color()),
                ));
            }
        }
        frame.render_widget(Paragraph::new(Line::from(spans)), area);
    }

    fn render_footer(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let key = |label: &str, bg: Color| {
            Span::styled(
                format!(" {label} "),
                Style::default().fg(Color::Black).bg(bg).add_modifier(Modifier::BOLD),
            )
        };
        let mut spans = vec![
            key("q", theme.error.to_color()),
            Span::raw(format!(" {}", self.t("quit_hint"))),
            Span::raw("  "),
            key("Tab", theme.secondary.to_color()),
            Span::raw(format!(" {}", self.t("tab_switch"))),
            Span::raw("  "),
            key("↑↓", theme.secondary.to_color()),
            Span::raw(format!(" {}", self.t("nav"))),
            Span::raw("  "),
            key("Enter", theme.primary.to_color()),
            Span::raw(format!(" {}", self.t("toggle"))),
            Span::raw("  "),
            key("-/+", theme.primary.to_color()),
            Span::raw(format!(" {}", self.t("adjust"))),
        ];
        if !self.last_event.is_empty() {
            spans.push(Span::raw("  ·  "));
            spans.push(Span::styled(
                self.last_event.clone(),
                Style::default().fg(theme.secondary.to_color()),
            ));
        }
        frame.render_widget(Paragraph::new(Line::from(spans)), area);
    }

    fn render_dashboard(&self, frame: &mut Frame, area: Rect, state: &ServerState) {
        let theme = self.theme;
        let chunks = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(55), Constraint::Percentage(45)])
            .split(area);

        let left_col = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Percentage(44), Constraint::Percentage(56)])
            .split(chunks[0]);

        // ---- Status panel ----
        let mut left_items: Vec<ListItem> = Vec::new();
        // Server row with a status dot
        let dot = Span::styled("●", Style::default().fg(theme.primary.to_color()));
        left_items.push(ListItem::new(Line::from(vec![
            dot,
            Span::raw(format!(" {}: ", self.t("state"))),
            Span::styled(
                self.t("server_running"),
                Style::default().fg(theme.primary.to_color()).add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(
                format!("{} {}", self.mode_label(), self.port),
                Style::default().fg(theme.tertiary.to_color()),
            ),
        ])));
        left_items.push(ListItem::new(""));
        // Device row
        match &self.device {
            Some(device) => {
                left_items.push(ListItem::new(Line::from(vec![
                    Span::styled("●", Style::default().fg(theme.primary.to_color())),
                    Span::raw(format!(" {}: ", self.t("device"))),
                    Span::styled(
                        device.name.clone(),
                        Style::default().fg(theme.primary.to_color()).add_modifier(Modifier::BOLD),
                    ),
                ])));
                left_items.push(ListItem::new(Line::from(vec![
                    Span::raw("   "),
                    Span::styled(
                        format!("ip {}", device.ip),
                        Style::default().fg(theme.secondary.to_color()),
                    ),
                    Span::raw("  ·  "),
                    Span::styled(
                        format!("{} {}ms", self.t("latency"), device.latency),
                        Style::default().fg(theme.tertiary.to_color()),
                    ),
                ])));
            }
            None => {
                left_items.push(ListItem::new(Line::from(vec![
                    Span::styled("○", Style::default().fg(theme.error.to_color())),
                    Span::raw(format!(" {}: ", self.t("device"))),
                    Span::styled(
                        self.t("device_not_connected"),
                        Style::default().fg(theme.error.to_color()),
                    ),
                ])));
            }
        }
        left_items.push(ListItem::new(""));
        // Muted / web clients
        let muted_label = if self.muted {
            Span::styled(
                format!(" {} ", self.t("muted")),
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.error.to_color())
                    .add_modifier(Modifier::BOLD),
            )
        } else {
            Span::styled(
                format!(" {} ", self.t("muted")),
                Style::default()
                    .fg(theme.on_surface.to_color())
                    .bg(theme.surface_variant.to_color()),
            )
        };
        left_items.push(ListItem::new(Line::from(vec![
            muted_label,
            Span::raw(format!("  {}: {}", self.t("web_clients"), self.web_clients)),
        ])));
        // Local IPs
        if !self.ips.is_empty() {
            left_items.push(ListItem::new(""));
            left_items.push(ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{}:", self.t("local_ips")),
                    Style::default().add_modifier(Modifier::BOLD),
                ),
                Span::raw(" "),
                Span::styled(
                    self.ips.join("  "),
                    Style::default().fg(theme.tertiary.to_color()),
                ),
            ])));
        }
        let left = List::new(left_items)
            .block(self.panel(self.t("state")));
        frame.render_widget(left, left_col[0]);

        // ---- Spectrum ----
        self.render_spectrum(frame, left_col[1]);

        // ---- Right: level + metrics ----
        let right_col = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(5), Constraint::Min(3)])
            .split(chunks[1]);

        self.render_level(frame, right_col[0]);
        self.render_metrics(frame, right_col[1]);
        let _ = state;
    }

    /// Panel block with a colored title and soft border.
    fn panel(&self, title: String) -> Block<'static> {
        Block::default()
            .borders(Borders::ALL)
            .border_style(Style::default().fg(self.theme.surface_variant.to_color()))
            .title(Span::styled(
                format!(" {title} "),
                Style::default().fg(self.theme.primary.to_color()).add_modifier(Modifier::BOLD),
            ))
    }

    /// Vertical input-level meter with a big percentage readout.
    fn render_level(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let block = self.panel(self.t("input_level"));
        let inner = block.inner(area);
        let h = inner.height as usize;
        if h == 0 {
            return;
        }
        let level = self.level.min(100);
        let color = if level > 80 {
            theme.error.to_color()
        } else if level > 60 {
            theme.tertiary.to_color()
        } else {
            theme.primary.to_color()
        };
        let mut lines: Vec<Line> = Vec::with_capacity(h);
        lines.push(Line::from(vec![
            Span::raw(" "),
            Span::styled(
                format!("{level}%"),
                Style::default().fg(color).add_modifier(Modifier::BOLD),
            ),
        ]));
        // Fill a few bars on top of a faint track
        let fill_rows = ((h - 1) as f32 * level as f32 / 100.0).round() as usize;
        for row in 0..h - 1 {
            let is_fill = (h - 2 - row) < fill_rows;
            let bar = "█".repeat(inner.width as usize);
            if is_fill {
                lines.push(Line::from(Span::styled(
                    bar,
                    Style::default().fg(color),
                )));
            } else if row % 2 == 0 {
                lines.push(Line::from(Span::styled(
                    bar,
                    Style::default().fg(theme.surface_variant.to_color()),
                )));
            } else {
                lines.push(Line::from(""));
            }
        }
        frame.render_widget(Paragraph::new(lines).block(block), area);
    }

    /// Metrics table: two-column label/value rows with colored values.
    fn render_metrics(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let mut rows: Vec<Line> = Vec::new();
        if let Some(m) = &self.metrics {
            let latency_color = if m.latency_ms > 200 {
                theme.error.to_color()
            } else {
                theme.primary.to_color()
            };
            let loss_color = if m.packet_loss_rate > 0.05 {
                theme.error.to_color()
            } else {
                theme.primary.to_color()
            };
            let row = |label: &str, value: String, color: Color| {
                Line::from(vec![
                    Span::raw(format!("  {label:<16}")),
                    Span::styled(value, Style::default().fg(color).add_modifier(Modifier::BOLD)),
                ])
            };
            rows.push(row(
                self.t("bitrate").as_str(),
                format!("{} kbps", m.bitrate / 1000),
                theme.tertiary.to_color(),
            ));
            rows.push(row(
                self.t("sample_rate").as_str(),
                format!("{} Hz", m.sample_rate),
                theme.tertiary.to_color(),
            ));
            rows.push(row(
                self.t("latency").as_str(),
                format!("{} ms", m.latency_ms),
                latency_color,
            ));
            rows.push(row(
                self.t("network_latency").as_str(),
                format!("{} ms", m.network_latency_ms),
                theme.secondary.to_color(),
            ));
            rows.push(row(
                self.t("jitter").as_str(),
                format!("{:.1} ms", m.jitter_ms),
                theme.secondary.to_color(),
            ));
            rows.push(row(
                self.t("packet_loss").as_str(),
                format!("{:.2}%", m.packet_loss_rate * 100.0),
                loss_color,
            ));
            rows.push(row(
                self.t("buffer").as_str(),
                format!("{} ms", m.buffer_duration_ms),
                theme.secondary.to_color(),
            ));
        } else {
            let placeholder = Line::from(Span::styled(
                format!("  {}", self.t("server_addr_hint")),
                Style::default().fg(theme.secondary.to_color()),
            ));
            rows.push(placeholder);
            rows.push(Line::from(""));
            rows.push(Line::from(Span::styled(
                "  ──────────────────────",
                Style::default().fg(theme.surface_variant.to_color()),
            )));
        }
        frame.render_widget(Paragraph::new(rows).block(self.panel(self.t("audio_metrics"))), area);
    }

    /// Cava-style vertical bars: each column's height = spectrum band value.
    /// Color follows the theme gradient bottom (cool) to top (warm), with a
    /// bright peak row on top.
    fn render_spectrum(&self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let block = self.panel(self.t("spectrum"));
        let inner = block.inner(area);
        let width = inner.width as usize;
        let height = inner.height as usize;
        if width == 0 || height == 0 {
            return;
        }
        let n_cols = width.min(64).max(4);
        let bands: Vec<f32> = (0..n_cols)
            .map(|i| {
                let src = i * 64 / n_cols;
                self.spectrum.get(src).copied().unwrap_or(0.0).min(1.0)
            })
            .collect();

        let idle = bands.iter().all(|v| *v < 0.01);
        if idle {
            // Faint track rows + centered hint
            let mut lines: Vec<Line> = Vec::with_capacity(height);
            for row in 0..height {
                if row % 2 == 0 {
                    lines.push(Line::from(Span::styled(
                        "░".repeat(width),
                        Style::default().fg(theme.surface_variant.to_color()),
                    )));
                } else {
                    lines.push(Line::from(""));
                }
            }
            let hint = format!("  {} ", self.t("spectrum_wait"));
            let top = (height / 2).saturating_sub(1);
            if let Some(l) = lines.get_mut(top) {
                let pad = width.saturating_sub(hint.chars().count()) / 2;
                *l = Line::from(vec![
                    Span::raw(" ".repeat(pad)),
                    Span::styled(
                        hint.trim().to_string(),
                        Style::default().fg(theme.secondary.to_color()),
                    ),
                ]);
            }
            frame.render_widget(Paragraph::new(lines).block(block), area);
            return;
        }

        let mut lines: Vec<Line> = Vec::with_capacity(height);
        // Peak row: highest band value rendered bright
        let peak = bands.iter().cloned().fold(0.0f32, f32::max);
        lines.push(Line::from(Span::styled(
            "█".repeat((n_cols as f32 * peak) as usize)
                + &" ".repeat(width.saturating_sub((n_cols as f32 * peak) as usize)),
            Style::default().fg(theme.on_surface.to_color()).add_modifier(Modifier::BOLD),
        )));
        for row in 1..height {
            let threshold = (height - 1 - row) as f32 / (height - 1).max(1) as f32;
            let t = (height - 1 - row) as f32 / (height - 1).max(1) as f32;
            let color = gradient_at(self.theme.gradient, t).to_color();
            let mut text = String::with_capacity(width);
            for v in &bands {
                text.push(if *v >= threshold { '█' } else { ' ' });
            }
            lines.push(Line::from(Span::styled(text, Style::default().fg(color))));
        }
        frame.render_widget(Paragraph::new(lines).block(block), area);
    }

    /// Audio parameters: rows with a mini progress bar for gain / buffer,
    /// and [ON]/[OFF] badges for toggles.
    fn render_settings(&mut self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let w = area.width as usize;
        let bar = |val: f32, min: f32, max: f32| -> String {
            if w < 40 {
                return String::new();
            }
            let bw = (w - 34).max(8).min(24);
            let filled = ((val - min) / (max - min)).clamp(0.0, 1.0) * bw as f32;
            let mut s = String::with_capacity(bw);
            for i in 0..bw {
                s.push(if (i as f32) < filled { '█' } else { '░' });
            }
            format!(" [{s}]")
        };
        let badge = |v: bool| -> Span<'static> {
            if v {
                Span::styled(
                    format!(" {} ", self.t("enabled")),
                    Style::default()
                        .fg(Color::Black)
                        .bg(theme.primary.to_color())
                        .add_modifier(Modifier::BOLD),
                )
            } else {
                Span::styled(
                    format!(" {} ", self.t("disabled")),
                    Style::default()
                        .fg(theme.on_surface.to_color())
                        .bg(theme.surface_variant.to_color()),
                )
            }
        };
        let items: Vec<ListItem> = vec![
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("gain"))),
                Span::styled(
                    format!("  {:.1} dB", self.settings.gain),
                    Style::default().fg(theme.tertiary.to_color()).add_modifier(Modifier::BOLD),
                ),
                Span::raw(bar(self.settings.gain, -50.0, 50.0)),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("aec"))),
                Span::raw("  "),
                badge(self.settings.aec_enabled),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("noise_reduction"))),
                Span::raw("  "),
                badge(self.settings.ns_enabled),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("dereverb"))),
                Span::raw("  "),
                badge(self.settings.dereverb_enabled),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("agc"))),
                Span::raw("  "),
                badge(self.settings.agc_enabled),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("vad"))),
                Span::raw("  "),
                badge(self.settings.vad_enabled),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("output_buffer"))),
                Span::styled(
                    format!("  {} {}", self.settings.output_buffer_ms, self.t("ms")),
                    Style::default().fg(theme.tertiary.to_color()).add_modifier(Modifier::BOLD),
                ),
                Span::raw(bar(
                    self.settings.output_buffer_ms as f32,
                    100.0,
                    1200.0,
                )),
            ])),
        ];
        let list = List::new(items)
            .block(self.panel(self.t("audio_params_title")))
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color()),
            )
            .highlight_symbol(">");
        frame.render_stateful_widget(
            list,
            area,
            &mut ratatui::widgets::ListState::default().with_selected(Some(self.selected_setting)),
        );
    }

    /// Processing chain: numbered stages with the pinned AEC lock badge.
    fn render_chain(&mut self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let items: Vec<ListItem> = self
            .settings
            .processing_chain
            .iter()
            .enumerate()
            .map(|(i, stage)| {
                let (label, is_aec) = match stage.as_str() {
                    "AEC" => (format!("{} (AEC)", self.t("aec")), true),
                    "NoiseReduction" => (format!("{} (NR)", self.t("noise_reduction")), false),
                    "Dereverb" => (format!("{}", self.t("dereverb")), false),
                    "Equalizer" => ("Equalizer".to_string(), false),
                    "Amplifier" => (format!("{} (AMP)", self.t("gain")), false),
                    "AGC" => (format!("{} (AGC)", self.t("agc")), false),
                    "VAD" => (format!("{} (VAD)", self.t("vad")), false),
                    other => (other.to_string(), false),
                };
                let mut spans = vec![
                    Span::styled(
                        format!("{:>2}", i + 1),
                        Style::default().fg(theme.secondary.to_color()),
                    ),
                    Span::raw("  "),
                ];
                if is_aec {
                    spans.push(Span::styled(
                        format!("🔒 {label}"),
                        Style::default().fg(theme.tertiary.to_color()).add_modifier(Modifier::BOLD),
                    ));
                    spans.push(Span::styled(
                        format!("  {}", self.t("pinned")),
                        Style::default().fg(theme.secondary.to_color()),
                    ));
                } else {
                    spans.push(Span::styled(label, Style::default()));
                }
                ListItem::new(Line::from(spans))
            })
            .collect();
        let list = List::new(items)
            .block(self.panel(self.t("chain_title")))
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color()),
            )
            .highlight_symbol(">");
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
        let val = |v: String| {
            Span::styled(
                v,
                Style::default().fg(theme.tertiary.to_color()).add_modifier(Modifier::BOLD),
            )
        };
        let items: Vec<ListItem> = vec![
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("conn_mode"))),
                Span::raw("  "),
                val(mode_label),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("conn_port"))),
                Span::raw("  "),
                val(self.prefs.port.to_string()),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("conn_web_port"))),
                Span::raw("  "),
                val(self.prefs.web_port.to_string()),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("conn_bind"))),
                Span::raw("  "),
                val(bind_label),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw(format!(" {}", self.t("conn_device"))),
                Span::raw("  "),
                val(if self.prefs.output_device.is_empty() {
                    self.t("none")
                } else {
                    self.prefs.output_device.clone()
                }),
            ])),
            ListItem::new(""),
            ListItem::new(Line::from(Span::styled(
                format!("  {}", self.t("conn_hint")),
                Style::default().fg(theme.secondary.to_color()),
            ))),
        ];
        let list = List::new(items)
            .block(self.panel(self.t("tab_conn")))
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(theme.primary.to_color()),
            )
            .highlight_symbol(">");
        frame.render_stateful_widget(
            list,
            area,
            &mut ratatui::widgets::ListState::default().with_selected(Some(self.selected_conn)),
        );
    }

    fn render_logs(&mut self, frame: &mut Frame, area: Rect) {
        let theme = self.theme;
        let inner_h = area.height.saturating_sub(2) as usize;
        let total = self.logs.len();
        let max_offset = total.saturating_sub(inner_h.max(1));
        if self.log_offset > max_offset {
            self.log_offset = max_offset;
        }
        let start = total.saturating_sub(self.log_offset + inner_h.max(1));
        let items: Vec<ListItem> = self
            .logs
            .iter()
            .skip(start)
            .map(|l| {
                let (prefix, rest) = log_level_split(l);
                let mut spans: Vec<Span> = Vec::new();
                if let Some(p) = prefix {
                    let color = match p {
                        "[ok]" => theme.primary.to_color(),
                        "[warn]" => theme.tertiary.to_color(),
                        "[err]" => theme.error.to_color(),
                        _ => theme.secondary.to_color(),
                    };
                    spans.push(Span::styled(
                        p.to_string(),
                        Style::default().fg(color).add_modifier(Modifier::BOLD),
                    ));
                }
                spans.push(Span::raw(rest.to_string()));
                ListItem::new(Line::from(spans))
            })
            .collect();
        let list = List::new(items).block(self.panel(self.t("logs")));
        frame.render_widget(list, area);
    }
}

/// Split a log line into its level tag (e.g. "[ok]") and the rest.
fn log_level_split(line: &str) -> (Option<&str>, &str) {
    if line.starts_with('[') {
        if let Some(end) = line.find(']') {
            if end + 1 < line.len() {
                let tag = &line[..=end];
                if matches!(tag, "[ok]" | "[warn]" | "[err]" | "[inf]") {
                    return (Some(tag), line[end + 1..].trim_start());
                }
            }
        }
    }
    (None, line)
}

/// Pick a gradient stop for normalized position t in [0, 1].
fn gradient_at(gradient: [Rgba; 8], t: f32) -> Rgba {
    let idx = ((t.clamp(0.0, 1.0)) * 7.0).round() as usize;
    gradient[idx.min(7)]
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

/// Run the TUI dashboard until the user quits (q / Ctrl+C / mouse click).
/// `state` gives live access to the DSP settings and spectrum flag.
pub fn run_tui(
    rx: Receiver<Event>,
    state: Arc<ServerState>,
    port: u16,
    mode: String,
) -> Result<(), String> {
    enter()?;
    execute!(stdout(), crossterm::event::EnableMouseCapture).map_err(|e| e.to_string())?;

    let settings = state
        .dsp_settings
        .read()
        .map(|s| s.clone())
        .unwrap_or_default();
    let mut app = TuiApp::new(settings, port, mode);
    let mut terminal = ratatui::Terminal::new(ratatui::backend::CrosstermBackend::new(stdout()))
        .map_err(|e| e.to_string())?;

    // Catch panics so `leave()` always restores the terminal (a panicking draw
    // would otherwise leave the alternate screen active and "break" the terminal)
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<(), String> {
        loop {
            terminal
                .draw(|frame| app.render(frame, &state))
                .map_err(|e| e.to_string())?;

            if crossterm::event::poll(std::time::Duration::from_millis(80))
                .map_err(|e| e.to_string())?
            {
                match event::read().map_err(|e| e.to_string())? {
                    CrosstermEvent::Key(key) => {
                        if handle_key(&mut app, key, &state) {
                            break;
                        }
                    }
                    CrosstermEvent::Mouse(mouse) => {
                        let size = terminal.size().map_err(|e| e.to_string())?;
                        let area = Rect::new(0, 0, size.width, size.height);
                        if handle_mouse(&mut app, mouse, area, &state) {
                            break;
                        }
                    }
                    _ => {}
                }
            }

            // Drain incoming server events
            while let Ok(ev) = rx.try_recv() {
                app.on_event(ev);
            }
        }
        Ok(())
    }));

    let _ = execute!(stdout(), crossterm::event::DisableMouseCapture);
    leave()?;
    match result {
        Ok(r) => r,
        Err(_) => Err("TUI render panicked; terminal restored".to_string()),
    }
}

/// Handle mouse events: click tabs to switch, click list rows to select,
/// scroll wheel to navigate lists and logs.
fn handle_mouse(app: &mut TuiApp, mouse: MouseEvent, area: Rect, state: &ServerState) -> bool {
    let chunks = split_layout(area);
    let (col, row) = (mouse.column, mouse.row);
    match mouse.kind {
        // Left click
        K::Down(MouseButton::Left) | K::Up(MouseButton::Left) => {
            let is_up = matches!(mouse.kind, K::Up(_));
            // Tab bar row
            if row == chunks[1].y {
                if let Some(tab) = app.tab_hit(col) {
                    app.tab = tab;
                }
                return false;
            }
            // List pages: select the row under the cursor
            let body = chunks[2];
            if row >= body.y + 1 && row < body.y + body.height - 1 {
                let row_in = (row - body.y - 1) as usize;
                match app.tab {
                    1 if row_in <= 6 => {
                        app.selected_setting = row_in;
                        if is_up {
                            toggle_setting(app, row_in, state);
                        }
                    }
                    2 => {
                        let len = app.settings.processing_chain.len();
                        if row_in < len {
                            app.chain_index = row_in;
                        }
                    }
                    3 if row_in <= 4 => {
                        app.selected_conn = row_in;
                        if is_up {
                            act_conn(app, row_in);
                        }
                    }
                    _ => {}
                }
            }
            false
        }
        K::ScrollDown => {
            match app.tab {
                1 => {
                    app.selected_setting = (app.selected_setting + 1).min(6);
                }
                2 => {
                    let len = app.settings.processing_chain.len();
                    if app.chain_index + 1 < len {
                        app.chain_index += 1;
                    }
                }
                3 => {
                    app.selected_conn = (app.selected_conn + 1).min(4);
                }
                4 => app.log_offset = app.log_offset.saturating_add(1),
                _ => {}
            }
            false
        }
        K::ScrollUp => {
            match app.tab {
                1 => app.selected_setting = app.selected_setting.saturating_sub(1),
                2 => app.chain_index = app.chain_index.saturating_sub(1),
                3 => app.selected_conn = app.selected_conn.saturating_sub(1),
                4 => app.log_offset = app.log_offset.saturating_sub(1),
                _ => {}
            }
            false
        }
        _ => false,
    }
}

fn toggle_setting(app: &mut TuiApp, idx: usize, state: &ServerState) {
    match idx {
        1 => app.settings.aec_enabled = !app.settings.aec_enabled,
        2 => app.settings.ns_enabled = !app.settings.ns_enabled,
        3 => app.settings.dereverb_enabled = !app.settings.dereverb_enabled,
        4 => app.settings.agc_enabled = !app.settings.agc_enabled,
        5 => app.settings.vad_enabled = !app.settings.vad_enabled,
        _ => {}
    }
    sync_settings(&app.settings, state);
}

fn act_conn(app: &mut TuiApp, idx: usize) {
    match idx {
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

fn handle_key(app: &mut TuiApp, key: KeyEvent, state: &ServerState) -> bool {
    match key.code {
        KeyCode::Char('q') | KeyCode::Char('Q') => return true,
        KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => return true,
        KeyCode::Esc => return true,
        KeyCode::Tab => {
            app.tab = (app.tab + 1) % 5;
        }
        KeyCode::BackTab => {
            app.tab = (app.tab + 4) % 5;
        }
        KeyCode::Left => {
            app.tab = (app.tab + 4) % 5;
        }
        KeyCode::Right => {
            app.tab = (app.tab + 1) % 5;
        }
        KeyCode::Up => match app.tab {
            1 => app.selected_setting = app.selected_setting.saturating_sub(1),
            3 => app.selected_conn = app.selected_conn.saturating_sub(1),
            4 => app.log_offset = app.log_offset.saturating_add(1),
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
            4 => app.log_offset = app.log_offset.saturating_sub(1),
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
                toggle_setting(app, app.selected_setting, state);
            } else if app.tab == 3 {
                act_conn(app, app.selected_conn);
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
                        app.prefs.port = app.prefs.port.saturating_add(10).min(65535);
                        app.port = app.prefs.port;
                        sync_server_prefs(app);
                    }
                    2 => {
                        app.prefs.web_port = app.prefs.web_port.saturating_add(10).min(65535);
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
