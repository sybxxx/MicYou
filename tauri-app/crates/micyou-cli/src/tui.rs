use crate::events::Event;
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
use tauri_app_lib::server::ServerState;
use tauri_app_lib::stats::AudioMetrics;
use tauri_app_lib::tcp_server::DeviceInfo;

const TABS: [&str; 4] = ["仪表盘", "音频参数", "处理链路", "日志"];

pub struct TuiApp {
    pub tab: usize,
    pub port: u16,
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
}

impl TuiApp {
    pub fn new(settings: AudioDspSettings, port: u16) -> Self {
        Self {
            tab: 0,
            port,
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
        }
    }

    pub fn on_event(&mut self, ev: Event) {
        match ev {
            Event::DeviceConnected(info) => {
                self.device = Some(info);
                self.log(format!("[mic] connected: {}", self.device.as_ref().unwrap().name));
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
            Event::Spectrum(_raw, _processed) => {}
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

        let title = Line::from(vec![
            Span::styled(
                " MicYou ",
                Style::default()
                    .fg(Color::Black)
                    .bg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  CLI 模式 "),
            Span::styled(
                format!("({})", std::env::consts::OS),
                Style::default().fg(Color::DarkGray),
            ),
        ]);
        frame.render_widget(Paragraph::new(title), chunks[0]);

        let tabs = Tabs::new(TABS.to_vec())
            .select(self.tab)
            .block(Block::default().borders(Borders::NONE))
            .style(Style::default().fg(Color::Cyan))
            .highlight_style(
                Style::default()
                    .fg(Color::Black)
                    .bg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            );
        frame.render_widget(tabs, chunks[1]);

        match self.tab {
            0 => self.render_dashboard(frame, chunks[2], state),
            1 => self.render_settings(frame, chunks[2]),
            2 => self.render_chain(frame, chunks[2]),
            _ => self.render_logs(frame, chunks[2]),
        }

        let footer = Line::from(vec![
            Span::styled(" q ", Style::default().fg(Color::Black).bg(Color::Red)),
            Span::raw("退出"),
            Span::raw("  "),
            Span::styled(" Tab ", Style::default().fg(Color::Black).bg(Color::Blue)),
            Span::raw("切换"),
            Span::raw("  "),
            Span::styled(" ↑↓ ", Style::default().fg(Color::Black).bg(Color::Blue)),
            Span::raw("选择"),
            Span::raw("  "),
            Span::styled(" Enter ", Style::default().fg(Color::Black).bg(Color::Green)),
            Span::raw("开关"),
            Span::raw("  "),
            Span::styled(" -/+ ", Style::default().fg(Color::Black).bg(Color::Green)),
            Span::raw("调整"),
            Span::raw("  "),
            Span::raw(&self.last_event),
        ]);
        frame.render_widget(Paragraph::new(footer), chunks[3]);
    }

    fn render_dashboard(&self, frame: &mut Frame, area: Rect, state: &ServerState) {
        let chunks = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(55), Constraint::Percentage(45)])
            .split(area);

        // Left column: status list on top, level gauge below
        let left_col = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Percentage(60), Constraint::Percentage(40)])
            .split(chunks[0]);

        // Left: server + device status
        let mut left_items = vec![
            ListItem::new(Line::from(vec![
                Span::raw("服务器: "),
                Span::styled("运行中", Style::default().fg(Color::Green)),
            ])),
            ListItem::new(Line::from(vec![
                Span::raw("监听: "),
                Span::styled(
                    format!("端口 {}", self.port),
                    Style::default().fg(Color::Cyan),
                ),
            ])),
            ListItem::new(""),
        ];
        match &self.device {
            Some(device) => {
                left_items.push(ListItem::new(Line::from(vec![
                    Span::styled("设备: ", Style::default().add_modifier(Modifier::BOLD)),
                    Span::styled(&device.name, Style::default().fg(Color::Green)),
                ])));
                left_items.push(ListItem::new(format!(
                    "  ip: {}  延迟: {}ms",
                    device.ip, device.latency
                )));
            }
            None => {
                left_items.push(ListItem::new(Line::from(vec![
                    Span::styled("设备: ", Style::default().add_modifier(Modifier::BOLD)),
                    Span::styled(
                        "未连接 - 在手机上打开 MicYou 并连接",
                        Style::default().fg(Color::Yellow),
                    ),
                ])));
            }
        }
        left_items.push(ListItem::new(format!(
            "muted: {}   web clients: {}",
            self.muted, self.web_clients
        )));

        let left = List::new(left_items).block(Block::default().borders(Borders::ALL).title("状态"));
        frame.render_widget(left, left_col[0]);

        // Level gauge
        let gauge = Gauge::default()
            .block(Block::default().borders(Borders::ALL).title("输入电平"))
            .gauge_style(
                Style::default().fg(if self.level > 80 { Color::Red } else { Color::Green }),
            )
            .ratio(f64::from(self.level.min(100)) / 100.0)
            .label(format!("{}", self.level));
        frame.render_widget(gauge, left_col[1]);

        // Right: metrics
        let mut rows = vec![
            "指标".to_string(),
            "─────".to_string(),
            "位速率:   -".to_string(),
            "采样率:   -".to_string(),
            "总延迟:   -".to_string(),
            "网络延迟: -".to_string(),
            "抖动:     -".to_string(),
            "丢包率:   -".to_string(),
            "缓冲:     -".to_string(),
        ];
        if let Some(m) = &self.metrics {
            rows[2] = format!("位速率:   {} kbps", m.bitrate / 1000);
            rows[3] = format!("采样率:   {} Hz", m.sample_rate);
            rows[4] = format!("总延迟:   {} ms", m.latency_ms);
            rows[5] = format!("网络延迟: {} ms", m.network_latency_ms);
            rows[6] = format!("抖动:     {:.1} ms", m.jitter_ms);
            rows[7] = format!("丢包率:   {:.2}%", m.packet_loss_rate * 100.0);
            rows[8] = format!("缓冲:     {} ms", m.buffer_duration_ms);
        }
        let right = List::new(rows).block(Block::default().borders(Borders::ALL).title("音频指标"));
        frame.render_widget(right, chunks[1]);

        let _ = state;
    }

    fn render_settings(&mut self, frame: &mut Frame, area: Rect) {
        let items: Vec<ListItem> = vec![
            ListItem::new(format!("增益 (Gain)                    {:.1} dB", self.settings.gain)),
            ListItem::new(format!(
                "回声消除 (AEC)                {}",
                on_off(self.settings.aec_enabled)
            )),
            ListItem::new(format!(
                "噪声抑制 (Noise Reduction)    {}",
                on_off(self.settings.ns_enabled)
            )),
            ListItem::new(format!(
                "去混响 (Dereverb)             {}",
                on_off(self.settings.dereverb_enabled)
            )),
            ListItem::new(format!(
                "自动增益 (AGC)                {}",
                on_off(self.settings.agc_enabled)
            )),
            ListItem::new(format!(
                "语音检测 (VAD)                {}",
                on_off(self.settings.vad_enabled)
            )),
            ListItem::new(format!(
                "输出缓冲区 (Output Buffer)    {} ms",
                self.settings.output_buffer_ms
            )),
        ];
        let list = List::new(items)
            .block(Block::default().borders(Borders::ALL).title("音频参数（Enter 开关，-/+ 调整增益与缓冲）"))
            .highlight_style(Style::default().fg(Color::Black).bg(Color::Cyan))
            .highlight_symbol("> ");
        frame.render_stateful_widget(list, area, &mut ratatui::widgets::ListState::default().with_selected(Some(self.selected_setting)));
    }

    fn render_chain(&mut self, frame: &mut Frame, area: Rect) {
        let items: Vec<ListItem> = self
            .settings
            .processing_chain
            .iter()
            .enumerate()
            .map(|(i, stage)| {
                let label = match stage.as_str() {
                    "AEC" => "回声消除 (AEC) 🔒",
                    "NoiseReduction" => "噪声抑制 (NoiseReduction)",
                    "Dereverb" => "去混响 (Dereverb)",
                    "Equalizer" => "均衡器 (Equalizer)",
                    "Amplifier" => "增益 (Amplifier)",
                    "AGC" => "自动增益 (AGC)",
                    "VAD" => "语音检测 (VAD)",
                    other => other,
                };
                if i == 0 && stage == "AEC" {
                    ListItem::new(format!("{i}. {label}").to_string())
                        .style(Style::default().fg(Color::Yellow))
                } else {
                    ListItem::new(format!("{i}. {label}").to_string())
                }
            })
            .collect();
        let list = List::new(items)
            .block(Block::default().borders(Borders::ALL).title("处理链路（↑↓ 选择，+/- 上下移动，AEC 固定首位）"))
            .highlight_style(Style::default().fg(Color::Black).bg(Color::Cyan))
            .highlight_symbol("> ");
        frame.render_stateful_widget(list, area, &mut ratatui::widgets::ListState::default().with_selected(Some(self.chain_index)));
    }

    fn render_logs(&self, frame: &mut Frame, area: Rect) {
        let items: Vec<ListItem> = self
            .logs
            .iter()
            .rev()
            .take(20)
            .map(|l| ListItem::new(l.clone()))
            .collect();
        let list = List::new(items).block(Block::default().borders(Borders::ALL).title("日志"));
        frame.render_widget(list, area);
    }
}

fn on_off(v: bool) -> &'static str {
    if v { "开" } else { "关" }
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
pub fn run_tui(rx: Receiver<Event>, state: Arc<ServerState>, port: u16) -> Result<(), String> {
    enter()?;

    let settings = state
        .dsp_settings
        .read()
        .map(|s| s.clone())
        .unwrap_or_default();
    let mut app = TuiApp::new(settings, port);
    let mut terminal = ratatui::Terminal::new(ratatui::backend::CrosstermBackend::new(stdout()))
        .map_err(|e| e.to_string())?;

    let result = (|| -> Result<(), String> {
        loop {
            terminal.draw(|frame| app.render(frame, &state)).map_err(|e| e.to_string())?;

            if crossterm::event::poll(std::time::Duration::from_millis(100)).map_err(|e| e.to_string())? {
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
            app.tab = (app.tab + 1) % TABS.len();
        }
        KeyCode::BackTab => {
            app.tab = (app.tab + TABS.len() - 1) % TABS.len();
        }
        KeyCode::Left => {
            app.tab = (app.tab + TABS.len() - 1) % TABS.len();
        }
        KeyCode::Right => {
            app.tab = (app.tab + 1) % TABS.len();
        }
        KeyCode::Up => match app.tab {
            1 => app.selected_setting = app.selected_setting.saturating_sub(1),
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
            }
        }
        KeyCode::Char('+') | KeyCode::Char('=')
            if app.tab == 1 => {
                match app.selected_setting {
                    0 => app.settings.gain = (app.settings.gain + 1.0).clamp(-50.0, 50.0),
                    6 => {
                        app.settings.output_buffer_ms =
                            (app.settings.output_buffer_ms + 100).clamp(100, 1200);
                    }
                    _ => {}
                }
                sync_settings(&app.settings, state);
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
