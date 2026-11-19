#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod config;
mod install;
mod net;
mod options;
mod platform;
mod storage;
mod task;
mod ui;

use eframe::egui;

fn window_icon() -> Option<egui::IconData> {
    let img = image::load_from_memory(include_bytes!("../assets/icon.png")).ok()?.to_rgba8();
    Some(egui::IconData { width: img.width(), height: img.height(), rgba: img.into_raw() })
}

/// Set on the second try, see `retry_with_safe_settings`.
const SAFE_MODE_VAR: &str = "STRATA_LAUNCHER_SAFE_MODE";

fn crash_log_path() -> Option<std::path::PathBuf> {
    dirs::data_local_dir().map(|d| d.join("StrataLauncher").join("launcher-error.txt"))
}

/// The release build has no console on Windows and prints nothing useful on Linux when it dies, so the reason is also kept in a file.
fn record_failure(text: &str) {
    eprintln!("strata-launcher: {text}");
    if let Some(path) = crash_log_path() {
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(path, text);
    }
}

/// Linux only. A window that cannot be created is usually the Wayland session (a missing or broken compositor protocol, libdecor)
/// or the GPU driver (no usable OpenGL). The window system and the GL driver are picked once per process, so the second try is a new
/// process: through X11 (XWayland) and with Mesa's software renderer, which both work almost everywhere.
#[cfg(unix)]
fn retry_with_safe_settings() -> bool {
    use std::os::unix::process::CommandExt;

    if std::env::var_os(SAFE_MODE_VAR).is_some() {
        return false;
    }
    let exe = match std::env::current_exe() {
        Ok(exe) => exe,
        Err(_) => return false,
    };
    // exec only returns when it failed
    let err = std::process::Command::new(exe)
        .args(std::env::args_os().skip(1))
        .env(SAFE_MODE_VAR, "1")
        .env("WINIT_UNIX_BACKEND", "x11")
        .env_remove("WAYLAND_DISPLAY")
        .env_remove("WAYLAND_SOCKET")
        .env("LIBGL_ALWAYS_SOFTWARE", "1")
        .exec();
    record_failure(&format!("could not restart in safe mode: {err}"));
    false
}

#[cfg(not(unix))]
fn retry_with_safe_settings() -> bool {
    false
}

fn run(open_uninstall: bool) -> eframe::Result<()> {
    let mut viewport = egui::ViewportBuilder::default()
        .with_title(config::WINDOW_TITLE)
        .with_inner_size(ui::WINDOW_SIZE)
        .with_resizable(false)
        .with_maximize_button(false);
    if let Some(icon) = window_icon() {
        viewport = viewport.with_icon(icon);
    }

    let options = eframe::NativeOptions {
        viewport,
        // centering needs the monitor list, which not every Linux compositor gives out; the window manager places it instead
        centered: cfg!(windows),
        ..Default::default()
    };

    eframe::run_native(
        config::WINDOW_TITLE,
        options,
        Box::new(move |cc| Ok(Box::new(ui::App::new(cc, open_uninstall)))),
    )
}

fn main() {
    // panic = "unwind" in Cargo.toml: a panic during startup is caught here instead of aborting without a word
    std::panic::set_hook(Box::new(|info| record_failure(&format!("the launcher panicked: {info}"))));

    let open_uninstall = std::env::args().any(|a| a == "--uninstall");

    match std::panic::catch_unwind(|| run(open_uninstall)) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => {
            record_failure(&format!("could not open the window: {e}"));
            if !retry_with_safe_settings() {
                std::process::exit(1);
            }
        }
        Err(_) => {
            if !retry_with_safe_settings() {
                std::process::exit(1);
            }
        }
    }
}
