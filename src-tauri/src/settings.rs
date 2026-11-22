use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use tauri::{AppHandle, Manager};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AppSettings {
    pub region: String,
    pub username: String,
    pub password: String,
    pub poll_interval_sec: u64,
    pub show_background_graph: bool,
    pub graph_hours: u8,
    pub graph_opacity: f64,
    pub show_target_band: bool,
    pub graph_style: String,
    pub unit: String,
    pub urgent_low_threshold: i32,
    pub low_threshold: i32,
    pub high_threshold: i32,
    pub theme: String,
    pub opacity: f64,
    pub widget_size: String,
    pub always_on_top: bool,
    pub click_through: bool,
    pub click_through_opacity: f64,
    pub click_through_modifier: String,
    pub show_delta: bool,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            region: "us".to_string(),
            username: String::new(),
            password: String::new(),
            poll_interval_sec: 120,
            show_background_graph: true,
            graph_hours: 3,
            graph_opacity: 0.45,
            show_target_band: true,
            graph_style: "dots".to_string(),
            unit: "mg/dL".to_string(),
            urgent_low_threshold: 55,
            low_threshold: 70,
            high_threshold: 180,
            theme: "dark-glass".to_string(),
            opacity: 0.86,
            widget_size: "standard".to_string(),
            always_on_top: true,
            click_through: false,
            click_through_opacity: 0.55,
            click_through_modifier: "alt".to_string(),
            show_delta: true,
        }
    }
}

fn settings_file_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_config_dir()
        .map_err(|e| format!("Failed to resolve config directory: {e}"))?;
    fs::create_dir_all(&dir).map_err(|e| format!("Failed to create config directory: {e}"))?;
    Ok(dir.join("settings.json"))
}

pub fn load_settings(app: &AppHandle) -> AppSettings {
    let Ok(path) = settings_file_path(app) else {
        return AppSettings::default();
    };
    let Ok(raw) = fs::read_to_string(path) else {
        return AppSettings::default();
    };
    serde_json::from_str(&raw).unwrap_or_default()
}

pub fn persist_settings(app: &AppHandle, settings: &AppSettings) -> Result<(), String> {
    let path = settings_file_path(app)?;
    let json = serde_json::to_string_pretty(settings)
        .map_err(|e| format!("Failed to serialize settings: {e}"))?;
    fs::write(path, json).map_err(|e| format!("Failed to write settings file: {e}"))
}
