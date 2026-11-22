mod dexcom;
mod settings;

use dexcom::{DexcomClient, GlucoseReading};
use settings::{load_settings, persist_settings, AppSettings};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};
use std::thread;
use std::time::Duration;
use tauri::{
    menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, State, WindowEvent,
};

const VK_LBUTTON: i32 = 0x01;

fn modifier_vk(name: &str) -> i32 {
    match name.trim().to_ascii_lowercase().as_str() {
        "ctrl" | "control" => 0x11,
        "shift" => 0x10,
        _ => 0x12,
    }
}

#[cfg(target_os = "windows")]
fn is_vk_down(vk: i32) -> bool {
    #[link(name = "user32")]
    extern "system" {
        fn GetAsyncKeyState(v_key: i32) -> i16;
    }
    unsafe { GetAsyncKeyState(vk) < 0 }
}

#[cfg(not(target_os = "windows"))]
fn is_vk_down(_vk: i32) -> bool {
    false
}

struct AppState {
    settings: Mutex<AppSettings>,
    dexcom: DexcomClient,
    modifier_override: AtomicBool,
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> Result<AppSettings, String> {
    state
        .settings
        .lock()
        .map(|s| s.clone())
        .map_err(|_| "Settings mutex poisoned".to_string())
}

#[tauri::command]
fn save_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    mut settings: AppSettings,
) -> Result<(), String> {
    let is_signed_in = !settings.username.trim().is_empty() && !settings.password.is_empty();
    if !is_signed_in {
        settings.click_through = false;
    }
    persist_settings(&app, &settings)?;
    {
        let mut guard = state
            .settings
            .lock()
            .map_err(|_| "Settings mutex poisoned".to_string())?;
        if guard.region != settings.region
            || guard.username != settings.username
            || guard.password != settings.password
        {
            state.dexcom.clear_session();
        }
        *guard = settings.clone();
    }
    if !settings.click_through && state.modifier_override.swap(false, Ordering::Relaxed) {
        let _ = app.emit("modifier-override", false);
    }
    if let Some(overlay) = app.get_webview_window("overlay") {
        let override_active = state.modifier_override.load(Ordering::Relaxed);
        let _ = overlay.set_ignore_cursor_events(settings.click_through && !override_active);
        let _ = overlay.set_always_on_top(settings.always_on_top);
    }
    let _ = app.emit("settings-updated", &settings);
    Ok(())
}

#[tauri::command]
fn open_settings(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window("settings") {
        let _ = win.unminimize();
        win.show().map_err(|e| format!("Failed to show settings: {e}"))?;
        win.set_focus()
            .map_err(|e| format!("Failed to focus settings: {e}"))?;
    }
    Ok(())
}

#[tauri::command]
async fn fetch_dexcom_readings(
    state: State<'_, AppState>,
    minutes: Option<u32>,
    max_count: Option<u32>,
) -> Result<Vec<GlucoseReading>, String> {
    let (region, username, password) = {
        let guard = state
            .settings
            .lock()
            .map_err(|_| "Settings mutex poisoned".to_string())?;
        (
            guard.region.clone(),
            guard.username.clone(),
            guard.password.clone(),
        )
    };
    let mins = minutes.unwrap_or(360);
    let count = max_count.unwrap_or(72);
    state
        .dexcom
        .fetch_readings(&region, &username, &password, mins, count)
        .await
}

#[tauri::command]
async fn test_dexcom_connection(
    state: State<'_, AppState>,
    region: String,
    username: String,
    password: String,
) -> Result<String, String> {
    state
        .dexcom
        .test_connection(&region, &username, &password)
        .await
}

fn toggle_overlay_visibility(app: &AppHandle) {
    if let Some(overlay) = app.get_webview_window("overlay") {
        let visible = overlay.is_visible().unwrap_or(true);
        if visible {
            let _ = overlay.hide();
        } else {
            let _ = overlay.show();
            let _ = overlay.set_focus();
        }
    }
}

fn toggle_click_through_from_tray(app: &AppHandle) {
    let state = app.state::<AppState>();
    let updated = {
        let Ok(mut guard) = state.settings.lock() else {
            return;
        };
        guard.click_through = !guard.click_through;
        guard.clone()
    };
    let _ = persist_settings(app, &updated);
    if !updated.click_through && state.modifier_override.swap(false, Ordering::Relaxed) {
        let _ = app.emit("modifier-override", false);
    }
    if let Some(overlay) = app.get_webview_window("overlay") {
        let override_active = state.modifier_override.load(Ordering::Relaxed);
        let _ = overlay.set_ignore_cursor_events(updated.click_through && !override_active);
    }
    let _ = app.emit("settings-updated", &updated);
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let mut initial_settings = load_settings(app.handle());
            let is_signed_in = !initial_settings.username.trim().is_empty()
                && !initial_settings.password.is_empty();
            if !is_signed_in {
                initial_settings.click_through = false;
            }

            if let Some(overlay) = app.get_webview_window("overlay") {
                let _ = overlay.set_always_on_top(initial_settings.always_on_top);
                let _ = overlay.set_ignore_cursor_events(initial_settings.click_through);
            }

            app.manage(AppState {
                settings: Mutex::new(initial_settings),
                dexcom: DexcomClient::new(),
                modifier_override: AtomicBool::new(false),
            });

            let handle = app.handle().clone();
            thread::spawn(move || loop {
                let (click_through, modifier) = {
                    let state = handle.state::<AppState>();
                    let Ok(guard) = state.settings.lock() else {
                        thread::sleep(Duration::from_millis(120));
                        continue;
                    };
                    (guard.click_through, guard.click_through_modifier.clone())
                };

                let state = handle.state::<AppState>();
                if !click_through {
                    if state.modifier_override.swap(false, Ordering::Relaxed) {
                        if let Some(overlay) = handle.get_webview_window("overlay") {
                            let _ = overlay.set_ignore_cursor_events(false);
                        }
                        let _ = handle.emit("modifier-override", false);
                    }
                    thread::sleep(Duration::from_millis(120));
                    continue;
                }

                let currently_overriding = state.modifier_override.load(Ordering::Relaxed);
                let mod_down = is_vk_down(modifier_vk(&modifier));
                let next_override = mod_down || (currently_overriding && is_vk_down(VK_LBUTTON));

                if next_override != currently_overriding {
                    state
                        .modifier_override
                        .store(next_override, Ordering::Relaxed);
                    if let Some(overlay) = handle.get_webview_window("overlay") {
                        let _ = overlay.set_ignore_cursor_events(!next_override);
                    }
                    let _ = handle.emit("modifier-override", next_override);
                }

                thread::sleep(Duration::from_millis(40));
            });

            let toggle_vis =
                MenuItemBuilder::with_id("toggle_visibility", "Show / Hide Overlay").build(app)?;
            let toggle_lock =
                MenuItemBuilder::with_id("toggle_click_through", "Toggle Click-Through Lock")
                    .build(app)?;
            let open_cfg = MenuItemBuilder::with_id("open_settings", "Settings...").build(app)?;
            let sep = PredefinedMenuItem::separator(app)?;
            let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;

            let menu = MenuBuilder::new(app)
                .items(&[&toggle_vis, &toggle_lock, &open_cfg, &sep, &quit])
                .build()?;

            let icon = app
                .default_window_icon()
                .cloned()
                .expect("default window icon must be configured");

            TrayIconBuilder::with_id("main-tray")
                .icon(icon)
                .tooltip("AlGlucoMon")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "toggle_visibility" => toggle_overlay_visibility(app),
                    "toggle_click_through" => toggle_click_through_from_tray(app),
                    "open_settings" => {
                        let _ = open_settings(app.clone());
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::DoubleClick {
                        button: MouseButton::Left,
                        ..
                    } = event
                    {
                        let _ = open_settings(tray.app_handle().clone());
                    }
                })
                .build(app)?;

            if !is_signed_in {
                let _ = open_settings(app.handle().clone());
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == "settings" {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            open_settings,
            fetch_dexcom_readings,
            test_dexcom_connection,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::modifier_vk;

    #[test]
    fn maps_click_through_modifier_keys() {
        assert_eq!(modifier_vk("alt"), 0x12);
        assert_eq!(modifier_vk("ctrl"), 0x11);
        assert_eq!(modifier_vk("shift"), 0x10);
        assert_eq!(modifier_vk("unknown"), 0x12);
    }
}
