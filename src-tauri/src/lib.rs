mod dexcom;
mod settings;

use dexcom::{DexcomClient, GlucoseReading};
use settings::{load_settings, persist_settings, AppSettings};
use std::ffi::c_void;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};
use std::thread;
use std::time::{Duration, Instant};
use tauri::{
    menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, PhysicalPosition, Position, State, WebviewWindow, WindowEvent,
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

#[cfg(target_os = "windows")]
fn ensure_overlay_topmost(overlay_hwnd: *mut c_void, settings_hwnd: Option<*mut c_void>) {
    #[link(name = "user32")]
    extern "system" {
        fn IsIconic(h_wnd: *mut c_void) -> i32;
        fn IsWindowVisible(h_wnd: *mut c_void) -> i32;
        fn ShowWindow(h_wnd: *mut c_void, n_cmd_show: i32) -> i32;
        fn GetForegroundWindow() -> *mut c_void;
        fn SetWindowPos(
            h_wnd: *mut c_void,
            h_wnd_insert_after: *mut c_void,
            x: i32,
            y: i32,
            cx: i32,
            cy: i32,
            u_flags: u32,
        ) -> i32;
    }

    const SW_SHOWNOACTIVATE: i32 = 4;
    const HWND_TOPMOST: *mut c_void = -1isize as *mut c_void;
    const SWP_NOSIZE: u32 = 0x0001;
    const SWP_NOMOVE: u32 = 0x0002;
    const SWP_NOACTIVATE: u32 = 0x0010;

    unsafe {
        if IsIconic(overlay_hwnd) != 0 || IsWindowVisible(overlay_hwnd) == 0 {
            ShowWindow(overlay_hwnd, SW_SHOWNOACTIVATE);
        }
        let fg = GetForegroundWindow();
        if settings_hwnd.is_some_and(|sh| sh == fg) {
            return;
        }
        SetWindowPos(
            overlay_hwnd,
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );
    }
}

#[cfg(target_os = "windows")]
fn reassert_overlay_topmost(app: &AppHandle, overlay: &WebviewWindow) {
    if let Ok(hwnd) = overlay.hwnd() {
        let settings_hwnd = app
            .get_webview_window("settings")
            .and_then(|w| w.hwnd().ok())
            .map(|h| h.0);
        ensure_overlay_topmost(hwnd.0, settings_hwnd);
    }
}

#[cfg(not(target_os = "windows"))]
fn reassert_overlay_topmost(_app: &AppHandle, _overlay: &WebviewWindow) {}

#[cfg(target_os = "windows")]
fn apply_run_on_startup(enabled: bool) -> Result<(), String> {
    use std::io::ErrorKind;
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    const RUN_KEY_PATH: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
    const VALUE_NAME: &str = "AlGlucoMon";

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (key, _) = hkcu
        .create_subkey(RUN_KEY_PATH)
        .map_err(|e| format!("Failed to open Windows Run key: {e}"))?;

    if enabled {
        let exe = std::env::current_exe()
            .map_err(|e| format!("Failed to resolve executable path: {e}"))?;
        let quoted = format!("\"{}\"", exe.display());
        key.set_value(VALUE_NAME, &quoted)
            .map_err(|e| format!("Failed to register startup entry: {e}"))?;
    } else {
        match key.delete_value(VALUE_NAME) {
            Ok(()) => {}
            Err(e) if e.kind() == ErrorKind::NotFound => {}
            Err(e) => return Err(format!("Failed to remove startup entry: {e}")),
        }
    }
    Ok(())
}

#[cfg(not(target_os = "windows"))]
fn apply_run_on_startup(_enabled: bool) -> Result<(), String> {
    Ok(())
}

fn is_position_visible(x: i32, y: i32, bounds: &[(i32, i32, u32, u32)]) -> bool {
    if x <= -10_000 || y <= -10_000 {
        return false;
    }
    const EDGE_TOL: i32 = 40;
    bounds.iter().any(|&(mx, my, mw, mh)| {
        let max_x = mx + (mw as i32) - EDGE_TOL;
        let max_y = my + (mh as i32) - EDGE_TOL;
        x >= mx - EDGE_TOL && x <= max_x && y >= my - EDGE_TOL && y <= max_y
    })
}

fn place_overlay_initial(overlay: &WebviewWindow, saved_x: Option<i32>, saved_y: Option<i32>) {
    let monitors = overlay.available_monitors().unwrap_or_default();
    let rects: Vec<(i32, i32, u32, u32)> = monitors
        .iter()
        .map(|m| {
            let p = m.position();
            let s = m.size();
            (p.x, p.y, s.width, s.height)
        })
        .collect();

    if let (Some(x), Some(y)) = (saved_x, saved_y) {
        if is_position_visible(x, y, &rects) {
            let _ = overlay.set_position(Position::Physical(PhysicalPosition::new(x, y)));
            return;
        }
    }

    let target_monitor = overlay
        .primary_monitor()
        .ok()
        .flatten()
        .or_else(|| monitors.into_iter().next());

    if let Some(monitor) = target_monitor {
        let m_pos = monitor.position();
        let m_size = monitor.size();
        let scale = monitor.scale_factor();
        let margin = (24.0 * scale).round() as i32;
        let win_w = overlay
            .outer_size()
            .map(|s| s.width as i32)
            .unwrap_or((256.0 * scale).round() as i32);
        let x = m_pos.x + (m_size.width as i32) - win_w - margin;
        let y = m_pos.y + margin;
        let _ = overlay.set_position(Position::Physical(PhysicalPosition::new(x, y)));
    }
}

fn flush_pending_position(app: &AppHandle) {
    let Some(state) = app.try_state::<AppState>() else {
        return;
    };
    if !state.position_dirty.swap(false, Ordering::Relaxed) {
        return;
    }
    let snapshot = {
        let Ok(guard) = state.settings.lock() else {
            return;
        };
        guard.clone()
    };
    let _ = persist_settings(app, &snapshot);
}

struct AppState {
    settings: Mutex<AppSettings>,
    dexcom: DexcomClient,
    modifier_override: AtomicBool,
    position_dirty: AtomicBool,
    user_hidden: AtomicBool,
}

#[tauri::command]
fn is_portable_mode() -> bool {
    settings::is_portable_mode()
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
    let (startup_changed, saved_x, saved_y) = {
        let guard = state
            .settings
            .lock()
            .map_err(|_| "Settings mutex poisoned".to_string())?;
        (
            guard.run_on_startup != settings.run_on_startup,
            guard.window_x,
            guard.window_y,
        )
    };
    settings.window_x = saved_x;
    settings.window_y = saved_y;
    if settings::is_portable_mode() {
        settings.run_on_startup = false;
    } else if startup_changed {
        apply_run_on_startup(settings.run_on_startup)?;
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
    let Some(overlay) = app.get_webview_window("overlay") else {
        return;
    };
    let state = app.state::<AppState>();
    let user_hidden = state.user_hidden.load(Ordering::Relaxed);
    let visible = !user_hidden && overlay.is_visible().unwrap_or(true);

    if visible {
        state.user_hidden.store(true, Ordering::Relaxed);
        let _ = overlay.hide();
    } else {
        state.user_hidden.store(false, Ordering::Relaxed);
        let (always_on_top, click_through) = state
            .settings
            .lock()
            .map(|g| (g.always_on_top, g.click_through))
            .unwrap_or((true, false));
        let _ = overlay.unminimize();
        let _ = overlay.show();
        let _ = overlay.set_always_on_top(always_on_top);
        if always_on_top {
            reassert_overlay_topmost(app, &overlay);
        }
        if !click_through {
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
    if let Some(webview_dir) = settings::portable_webview_dir() {
        let _ = std::fs::create_dir_all(&webview_dir);
        std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &webview_dir);
    }

    tauri::Builder::default()
        .setup(|app| {
            let mut initial_settings = load_settings(app.handle());
            let is_signed_in = !initial_settings.username.trim().is_empty()
                && !initial_settings.password.is_empty();
            if !is_signed_in {
                initial_settings.click_through = false;
            }
            if settings::is_portable_mode() {
                initial_settings.run_on_startup = false;
            } else if initial_settings.run_on_startup {
                let _ = apply_run_on_startup(true);
            }

            let saved_x = initial_settings.window_x;
            let saved_y = initial_settings.window_y;

            app.manage(AppState {
                settings: Mutex::new(initial_settings.clone()),
                dexcom: DexcomClient::new(),
                modifier_override: AtomicBool::new(false),
                position_dirty: AtomicBool::new(false),
                user_hidden: AtomicBool::new(false),
            });

            if let Some(overlay) = app.get_webview_window("overlay") {
                let _ = overlay.set_always_on_top(initial_settings.always_on_top);
                let _ = overlay.set_ignore_cursor_events(initial_settings.click_through);
                place_overlay_initial(&overlay, saved_x, saved_y);
            }

            let handle = app.handle().clone();
            thread::spawn(move || {
                let mut last_topmost_check = Instant::now();
                loop {
                    let state = handle.state::<AppState>();
                    if state.position_dirty.load(Ordering::Relaxed) && !is_vk_down(VK_LBUTTON) {
                        flush_pending_position(&handle);
                    }

                    let (click_through, modifier, always_on_top) = {
                        let Ok(guard) = state.settings.lock() else {
                            thread::sleep(Duration::from_millis(120));
                            continue;
                        };
                        (
                            guard.click_through,
                            guard.click_through_modifier.clone(),
                            guard.always_on_top,
                        )
                    };

                    if always_on_top
                        && !state.user_hidden.load(Ordering::Relaxed)
                        && last_topmost_check.elapsed() >= Duration::from_millis(1500)
                    {
                        last_topmost_check = Instant::now();
                        if let Some(overlay) = handle.get_webview_window("overlay") {
                            reassert_overlay_topmost(&handle, &overlay);
                        }
                    }

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
                    let next_override =
                        mod_down || (currently_overriding && is_vk_down(VK_LBUTTON));

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
                }
            });

            let toggle_vis =
                MenuItemBuilder::with_id("toggle_visibility", "Show / Hide Overlay").build(app)?;
            let toggle_lock =
                MenuItemBuilder::with_id("toggle_click_through", "Toggle Click-Through Lock")
                    .build(app)?;
            let reset_pos =
                MenuItemBuilder::with_id("reset_position", "Reset Overlay Position").build(app)?;
            let open_cfg = MenuItemBuilder::with_id("open_settings", "Settings...").build(app)?;
            let sep = PredefinedMenuItem::separator(app)?;
            let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;

            let menu = MenuBuilder::new(app)
                .items(&[&toggle_vis, &toggle_lock, &reset_pos, &open_cfg, &sep, &quit])
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
                    "reset_position" => {
                        if let Some(state) = app.try_state::<AppState>() {
                            state.user_hidden.store(false, Ordering::Relaxed);
                            state.position_dirty.store(false, Ordering::Relaxed);
                            let updated = {
                                let Ok(mut guard) = state.settings.lock() else {
                                    return;
                                };
                                guard.window_x = None;
                                guard.window_y = None;
                                guard.clone()
                            };
                            let _ = persist_settings(app, &updated);
                        }
                        if let Some(overlay) = app.get_webview_window("overlay") {
                            place_overlay_initial(&overlay, None, None);
                            let _ = overlay.show();
                            reassert_overlay_topmost(app, &overlay);
                        }
                    }
                    "open_settings" => {
                        let _ = open_settings(app.clone());
                    }
                    "quit" => {
                        flush_pending_position(app);
                        app.exit(0);
                    }
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
        .on_window_event(|window, event| match window.label() {
            "settings" => {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
            "overlay" => match event {
                WindowEvent::Moved(pos) => {
                    if pos.x <= -10_000 || pos.y <= -10_000 {
                        return;
                    }
                    if let Some(state) = window.app_handle().try_state::<AppState>() {
                        let changed = {
                            let Ok(mut guard) = state.settings.lock() else {
                                return;
                            };
                            if guard.window_x == Some(pos.x) && guard.window_y == Some(pos.y) {
                                false
                            } else {
                                guard.window_x = Some(pos.x);
                                guard.window_y = Some(pos.y);
                                true
                            }
                        };
                        if changed {
                            state.position_dirty.store(true, Ordering::Relaxed);
                        }
                    }
                }
                WindowEvent::CloseRequested { .. } | WindowEvent::Destroyed => {
                    flush_pending_position(window.app_handle());
                }
                _ => {}
            },
            _ => {}
        })
        .invoke_handler(tauri::generate_handler![
            is_portable_mode,
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
    use super::{is_position_visible, modifier_vk};

    #[test]
    fn maps_click_through_modifier_keys() {
        assert_eq!(modifier_vk("alt"), 0x12);
        assert_eq!(modifier_vk("ctrl"), 0x11);
        assert_eq!(modifier_vk("shift"), 0x10);
        assert_eq!(modifier_vk("unknown"), 0x12);
    }

    #[test]
    fn validates_saved_window_position_against_monitors() {
        let single = [(0, 0, 1920, 1080)];
        let dual = [(0, 0, 1920, 1080), (-1920, 0, 1920, 1080)];

        assert!(is_position_visible(100, 100, &single));
        assert!(is_position_visible(-1500, 200, &dual));
        assert!(!is_position_visible(2500, 200, &single));
        assert!(!is_position_visible(-32000, -32000, &single));
    }
}
