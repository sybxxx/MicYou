# MicYou CLI 模式实现计划

## 目标

为 MicYou 桌面端增加 CLI/TUI 模式，保留现有核心功能（音频服务、DSP 全链路、设备配置、mDNS 发现），适配 Windows / macOS / Linux 三平台

核心目标：让用户选择一种**占用更低**的运行模式（CLI 不跑 WebView，内存占用远低于 GUI），支持 GUI ↔ CLI 双向切换

## 现状分析

- 核心网络层（`tcp_server` `udp_server` `jitter_buffer` `audio_stream` `stats` `server`）不依赖 Tauri，可直接复用
- `AudioOutputManager`、`DspProcessor`、`LoopbackCapture` 已在 `micyou-audio` crate，无 Tauri 依赖
- Tauri 耦合点仅两处：`start_server` 内用 `AppHandle` 发射 `audio-level` / `audio-spectrum` / `server-stopped` 事件，`web_server` 用 `AppHandle` 发 WebSocket 事件
- `ServerState` 是纯 Rust 结构，CLI 可直接构造

## 架构

单实例互斥模式下 GUI 与 CLI 不同时运行，因此**后端代码不迁移**，复用 `tauri_app_lib`（rlib）

```
src-tauri (现有，最小适配)
  events.rs          新增 ServerEvents trait（音频电平/频谱/连接/进度等事件抽象）
  tcp_server.rs      AppHandle 参数 → Arc<dyn ServerEvents>（3 处）
  web_server.rs      AppHandle 参数 → Arc<dyn ServerEvents>（2 处）
  vbcable.rs         进度事件 → ServerEvents
  commands/system.rs 提取 start_server_inner / stop_server_inner（纯逻辑，pub 导出）
  lib.rs             组装 TauriEventSink（AppHandle 实现 ServerEvents，行为不变）

crates/micyou-cli (新增)            CLI/TUI 前端，直接 use tauri_app_lib
  main.rs            clap 子命令分发
  serve.rs           前台服务（日志模式 / TUI 模式）
  status.rs          状态查询
  devices.rs         音频设备管理
  settings.rs        DSP 设置读写（~/.config/micyou/settings.json）
  chain.rs           处理链路管理
  mics.rs            平台虚拟麦克风（复用 pipewire/blackhole/vbcable）
  adb.rs             USB 模式（复用 adb_manager）
  config.rs          配置文件
  events.rs          CliEventSink（ServerEvents 实现：TUI 状态 / 日志）
  lock.rs            单实例锁（mode.lock）
  tui/
    app.rs           应用状态机
    home.rs          状态仪表盘
    audio.rs         音频参数页
    chain.rs         链路编辑页
    log.rs           日志滚动页
    events.rs        crossterm 输入循环
```

技术要点：CLI 二进制链接 tauri 库但不调用 `Builder::run`，不创建 WebView，内存占用最低；`tauri_app_lib` 的 `generate_context!` 在自身 crate 编译时展开，CLI 编译不受影响

## 运行模式与切换

两种模式：`gui`（默认，Tauri 桌面应用）、`cli`（终端前台服务 + TUI）

### 切换入口

1. **命令行**：终端直接运行 `micyou serve` 启动 CLI 模式
2. **通用设置**：GUI 设置对话框新增“运行模式”选项，选择 CLI 时弹出确认 → 保存偏好 → 退出 GUI → 自动打开终端窗口运行 CLI
3. **托盘图标**：托盘菜单新增“切换到 CLI 模式”，点击后同样退出 GUI 并打开终端
4. **CLI 切回 GUI**：TUI 内快捷键或 `micyou gui` 命令，退出 CLI 并启动 GUI 可执行文件

### 各平台打开终端窗口的逻辑

- **Windows**：`cmd /c start` 启动新控制台窗口运行 `micyou serve`（检测 Windows Terminal 优先，回退 cmd）
- **macOS**：`osascript` 调 `tell application "Terminal" to do script "micyou serve"`（或 iTerm2 若存在）
- **Linux**：按顺序探测已安装终端模拟器（kitty / alacritty / gnome-terminal / konsole / xterm），用 `-e` 参数启动；均无则提示安装终端

### 单实例锁

- 全局锁文件：`~/.local/share/micyou/mode.lock`，内容为 JSON `{ mode: "gui"|"cli", pid, started_at }`
- GUI 启动时检查锁：若 `cli` 且 PID 存活 → 设置/托盘显示“CLI 模式运行中”，禁用服务启动按钮
- CLI `serve` 启动前检查锁：若 `gui` 且 PID 存活 → 提示“GUI 正在运行”，提供 `--force` 或提示从 GUI 切换
- 进程异常退出时锁文件残留 → PID 存活检测自动清理
- 服务层另有端口占用探测（TCP/UDP bind 失败即报错）兜底

## 技术选型

- TUI 库：`ratatui`（Rust TUI 事实标准，维护活跃）+ `crossterm`（终端跨平台，Windows/macOS/Linux 原生支持）
- CLI 框架：`clap` v4 derive 模式
- 配置序列化：`serde` + `serde_json`（与现有 `AudioDspSettings` schema 完全一致）
- 平台设备配置复用现有 `pipewire.rs`（Linux）、`blackhole.rs`（macOS）、`vbcable.rs`（Windows）

## 子命令设计

```
micyou serve [--port N] [--mode tcp|web] [--device NAME] [--no-tui]   启动服务
micyou status                                                           查询服务状态、延迟、设备
micyou stop                                                             停止服务
micyou devices                                                          列出音频输出设备
micyou settings get [KEY]                                              读取 DSP 设置
micyou settings set <KEY> <VALUE>                                      修改 DSP 设置（增益/AEC/NS/AGC/VAD/EQ/缓冲）
micyou chain list                                                       显示处理链路
micyou chain set <A,B,C>                                               设置处理链路顺序
micyou config path                                                      显示配置文件路径
micyou mics                                                             平台虚拟麦克风配置（VB-Cable / BlackHole / PipeWire）
micyou tui                                                              交互式 TUI 模式
```

`serve` 默认进入 TUI 仪表盘，`--no-tui` 时为纯日志模式（适合 systemd / 脚本）

## TUI 页面

1. 状态仪表盘：连接状态、Android 设备、输出设备、当前延迟（ms）、缓冲占用、实时电平条、频谱
2. 音频参数页：增益、AEC、降噪、去混响、AGC、VAD、EQ、缓冲区大小（与 GUI 设置项一一对应）
3. 链路编辑页：处理链路增删排序（AEC 规则与 GUI 一致）
4. 日志页：滚动查看服务日志与网络统计

## 实施阶段

### Phase 1: 后端最小适配

- `src-tauri` 新增 `events.rs`，定义 `ServerEvents` trait（device-connected / audio-metrics / audio-level / audio-spectrum / server-stopped / install-progress 等）
- `tcp_server.rs` `web_server.rs` `vbcable.rs` 的 `AppHandle` 参数替换为 `Arc<dyn ServerEvents>`
- `commands/system.rs` 提取 `start_server_inner` / `stop_server_inner`（参数化 ServerEvents），tauri command 薄包装
- `lib.rs` 实现 `TauriEventSink`（发 Tauri 事件，行为与现在一致）
- GUI 回归验证（`cargo check` + `npm run build` + 手动运行）

### Phase 2: CLI 骨架与无 TUI 子命令

- 新建 `micyou-cli` crate，接入 clap，依赖 `tauri_app_lib`
- 实现 `serve`（日志模式）、`status`、`stop`、`devices`、`settings`、`chain`、`mics`、`adb`、`config`
- 配置持久化到 `~/.config/micyou/`（schema 与 `AudioDspSettings` 一致）
- 单实例锁（mode.lock）与 PID 检测

### Phase 3: TUI 界面

- ratatui 应用框架（布局、事件循环、主题）
- 实现仪表盘、音频参数、链路、日志四页
- 电平与频谱数据流（复用 EventSink）
- TUI 内模式切换快捷键（切回 GUI）

### Phase 4: 平台适配与集成

- Linux PipeWire 虚拟设备创建命令（`mics`）
- macOS BlackHole 安装/切换命令
- Windows VB-Cable 检查/安装命令
- 各平台“打开终端窗口”实现（设置页与托盘共用）
- GUI 设置新增“运行模式”选项 + 托盘菜单“切换到 CLI 模式”
- 文档（README 补充 CLI 用法）

### Phase 5: 测试与打包

- 单元测试（设置解析、链路规则、配置迁移、锁文件）
- 三平台 `cargo build` 验证
- 可选：发布二进制分发

## 已确认决策

1. Web 模式纳入 CLI（`serve --mode web`）
2. ADB/USB 模式纳入 CLI（`adb devices`、`mics` 相关命令）
3. 单实例：GUI 与 CLI 通过 mode.lock + 端口探测互斥
4. 模式切换入口：命令行 / 通用设置 / 托盘图标 三处，双向切换
