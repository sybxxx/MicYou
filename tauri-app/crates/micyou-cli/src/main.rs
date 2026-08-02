mod commands;
mod config;
mod events;
mod i18n;
mod serve;
mod theme;
mod tui;

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(
    name = "micyou",
    version,
    about = "MicYou CLI - turn your Android device into a PC microphone",
    long_about = "MicYou CLI runs the audio server with minimal memory footprint.\n\
                  Use `micyou serve` to start the server, or `micyou --help` for all commands."
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// 启动音频服务（默认 TUI 仪表盘，--no-tui 纯日志）
    Serve {
        /// 音频服务器端口（UDP 端口自动 +1）
        #[arg(long, default_value_t = 4750)]
        port: u16,
        /// 服务模式：wifi | usb | web
        #[arg(long, default_value = "wifi", value_parser = ["wifi", "usb", "web"])]
        mode: String,
        /// 指定输出音频设备名称
        #[arg(long)]
        device: Option<String>,
        /// 绑定地址
        #[arg(long)]
        bind: Option<String>,
        /// 纯日志模式（无 TUI），适合 systemd / 脚本
        #[arg(long)]
        no_tui: bool,
    },
    /// 显示当前服务状态
    Status,
    /// 停止服务
    Stop,
    /// 列出音频输出设备
    Devices,
    /// 读取或修改 DSP 设置
    Settings {
        #[command(subcommand)]
        action: SettingsAction,
    },
    /// 处理链路管理
    Chain {
        #[command(subcommand)]
        action: ChainAction,
    },
    /// 平台虚拟麦克风状态（PipeWire / BlackHole / VB-Cable）
    Mics {
        /// 安装虚拟麦克风驱动（仅 Windows）
        #[arg(long)]
        install: bool,
    },
    /// 列出 ADB 设备
    AdbDevices,
    /// 显示配置文件路径
    Config,
}

#[derive(Subcommand)]
enum SettingsAction {
    /// 读取设置（不指定 key 时输出全部）
    Get {
        /// 设置键名，如 gain / nsEnabled / outputBufferMs
        key: Option<String>,
    },
    /// 修改设置，如 `micyou settings set gain 10`
    Set {
        /// 设置键名
        key: String,
        /// 值（数字 / 布尔 / 字符串）
        value: String,
    },
    /// 输出当前设置的 JSON 路径
    Path,
}

#[derive(Subcommand)]
enum ChainAction {
    /// 显示当前处理链路
    List,
    /// 设置处理链路顺序，如 `micyou chain set AEC,NoiseReduction,Dereverb`
    Set {
        /// 逗号分隔的链路项
        chain: String,
    },
}

#[tokio::main]
async fn main() {
    let cli = Cli::parse();
    let result = match cli.command {
        Commands::Serve {
            port,
            mode,
            device,
            bind,
            no_tui,
        } => {
            let args = serve::ServeArgs {
                port,
                mode,
                device,
                bind,
                no_tui,
            };
            serve::run(args).await
        }
        Commands::Status => {
            commands::cmd_status();
            Ok(())
        }
        Commands::Stop => {
            commands::cmd_stop();
            Ok(())
        }
        Commands::Devices => {
            commands::cmd_devices();
            Ok(())
        }
        Commands::Settings { action } => match action {
            SettingsAction::Get { key } => commands::cmd_settings_get(key),
            SettingsAction::Set { key, value } => commands::cmd_settings_set(key, value),
            SettingsAction::Path => {
                println!("{}", config::settings_path().display());
                Ok(())
            }
        },
        Commands::Chain { action } => match action {
            ChainAction::List => {
                commands::cmd_chain_list();
                Ok(())
            }
            ChainAction::Set { chain } => {
                let items: Vec<String> = chain
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect();
                if items.is_empty() {
                    Err("empty chain".to_string())
                } else {
                    commands::cmd_chain_set(items)
                }
            }
        },
        Commands::Mics { install } => {
            if install {
                #[cfg(target_os = "windows")]
                {
                    commands::cmd_mics_install().await
                }
                #[cfg(not(target_os = "windows"))]
                {
                    Err("install is only supported on Windows (VB-CABLE)".to_string())
                }
            } else {
                commands::cmd_mics();
                Ok(())
            }
        }
        Commands::AdbDevices => {
            commands::cmd_adb_devices();
            Ok(())
        }
        Commands::Config => {
            commands::cmd_config_path();
            Ok(())
        }
    };

    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}
