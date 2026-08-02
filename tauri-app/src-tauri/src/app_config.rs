use micyou_audio::dsp::AudioDspSettings;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// Shared config directory (Windows: %APPDATA%\micyou, unix: XDG_CONFIG_HOME or ~/.config + micyou).
pub fn config_dir() -> PathBuf {
    #[cfg(target_os = "windows")]
    {
        if let Some(appdata) = std::env::var_os("APPDATA") {
            return PathBuf::from(appdata).join("micyou");
        }
    }
    if let Some(xdg) = std::env::var_os("XDG_CONFIG_HOME") {
        let dir = PathBuf::from(xdg).join("micyou");
        if !dir.as_os_str().is_empty() {
            return dir;
        }
    }
    std::env::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".config")
        .join("micyou")
}

/// settings.json: the DSP settings shared by GUI and CLI.
pub fn settings_path() -> PathBuf {
    config_dir().join("settings.json")
}

/// ui.json: GUI UI preferences (language, theme color) that the CLI reads.
pub fn ui_prefs_path() -> PathBuf {
    config_dir().join("ui.json")
}

/// theme.json: current GUI theme colors exported for the CLI TUI.
pub fn theme_path() -> PathBuf {
    config_dir().join("theme.json")
}

/// Load DSP settings from settings.json, falling back to defaults.
pub fn load_dsp_settings() -> AudioDspSettings {
    let path = settings_path();
    if let Ok(text) = fs::read_to_string(&path) {
        if let Ok(settings) = serde_json::from_str::<AudioDspSettings>(&text) {
            return settings;
        }
    }
    AudioDspSettings::default()
}

/// Persist DSP settings to settings.json (GUI and CLI share this file).
pub fn save_dsp_settings(settings: &AudioDspSettings) -> Result<(), String> {
    let dir = config_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("create config dir failed: {e}"))?;
    let json = serde_json::to_string_pretty(settings)
        .map_err(|e| format!("serialize settings failed: {e}"))?;
    fs::write(settings_path(), json).map_err(|e| format!("write settings.json failed: {e}"))
}

/// Raw settings.json as a JSON value (for the CLI `settings get`).
pub fn settings_json() -> serde_json::Value {
    fs::read_to_string(settings_path())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_else(|| serde_json::to_value(AudioDspSettings::default()).unwrap_or_default())
}

/// GUI UI preferences persisted to ui.json.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct UiPrefs {
    pub language: String,
    pub theme_color: String,
}

pub fn load_ui_prefs() -> UiPrefs {
    fs::read_to_string(ui_prefs_path())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

pub fn save_ui_prefs(prefs: &UiPrefs) -> Result<(), String> {
    let dir = config_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("create config dir failed: {e}"))?;
    let json = serde_json::to_string_pretty(prefs)
        .map_err(|e| format!("serialize ui prefs failed: {e}"))?;
    fs::write(ui_prefs_path(), json).map_err(|e| format!("write ui.json failed: {e}"))
}

/// Theme colors exported from the GUI for the CLI TUI.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct ThemeColors {
    pub primary: String,
    pub secondary: String,
    pub tertiary: String,
    pub surface: String,
    pub surface_variant: String,
    pub on_surface: String,
    pub error: String,
}

pub fn load_theme_colors() -> ThemeColors {
    fs::read_to_string(theme_path())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

pub fn save_theme_colors(colors: &ThemeColors) -> Result<(), String> {
    let dir = config_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("create config dir failed: {e}"))?;
    let json = serde_json::to_string_pretty(colors)
        .map_err(|e| format!("serialize theme failed: {e}"))?;
    fs::write(theme_path(), json).map_err(|e| format!("write theme.json failed: {e}"))
}
