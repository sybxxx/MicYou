use micyou_audio::dsp::AudioDspSettings;
use std::fs;
use std::path::PathBuf;

/// CLI settings file, same schema as `AudioDspSettings` used by the GUI.
pub fn config_dir() -> PathBuf {
    #[cfg(target_os = "windows")]
    {
        std::env::var("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("."))
            .join("micyou")
    }
    #[cfg(not(target_os = "windows"))]
    {
        std::env::var("XDG_CONFIG_HOME")
            .ok()
            .filter(|p| !p.is_empty())
            .map(PathBuf::from)
            .or_else(|| std::env::var("HOME").ok().map(|h| PathBuf::from(h).join(".config")))
            .unwrap_or_else(|| PathBuf::from("."))
            .join("micyou")
    }
}

pub fn settings_path() -> PathBuf {
    config_dir().join("settings.json")
}

pub fn load_settings() -> AudioDspSettings {
    let path = settings_path();
    match fs::read_to_string(&path) {
        Ok(raw) => match serde_json::from_str::<AudioDspSettings>(&raw) {
            Ok(settings) => settings,
            Err(e) => {
                eprintln!("warning: failed to parse {}: {e}", path.display());
                AudioDspSettings::default()
            }
        },
        Err(_) => AudioDspSettings::default(),
    }
}

pub fn save_settings(settings: &AudioDspSettings) -> Result<(), String> {
    let dir = config_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("cannot create config dir: {e}"))?;
    let raw = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(settings_path(), raw).map_err(|e| format!("cannot write settings: {e}"))
}

