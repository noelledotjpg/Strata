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

const CRASH_FILE: &str = "crash.txt";
const STARTUP_FILE: &str = "startup.txt";

/// Next to the launcher when that folder can be written to (easy to find and to send), else in the user's data folder.
fn report_dir() -> Option<std::path::PathBuf> {
    static DIR: std::sync::OnceLock<Option<std::path::PathBuf>> = std::sync::OnceLock::new();
    DIR.get_or_init(|| {
        if let Some(dir) = std::env::current_exe().ok().and_then(|exe| exe.parent().map(|p| p.to_path_buf())) {
            let probe = dir.join(".strata-write-test");
            if std::fs::write(&probe, b"").is_ok() {
                let _ = std::fs::remove_file(&probe);
                return Some(dir);
            }
        }
        let dir = dirs::data_local_dir()?.join("StrataLauncher");
        std::fs::create_dir_all(&dir).ok()?;
        Some(dir)
    })
    .clone()
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// What the machine looks like, the first things asked about a window that will not open.
fn environment_report() -> String {
    let mut out = String::new();
    out.push_str(&format!("launcher {}\n", env!("CARGO_PKG_VERSION")));
    out.push_str(&format!("os {} {}\n", std::env::consts::OS, std::env::consts::ARCH));
    out.push_str(&format!("time (unix seconds) {}\n", now_secs()));
    out.push_str(&format!("args {:?}\n", std::env::args().collect::<Vec<_>>()));
    for var in [
        "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "DISPLAY", "WAYLAND_DISPLAY", "LIBGL_ALWAYS_SOFTWARE",
        "WINIT_UNIX_BACKEND", "LANG", SAFE_MODE_VAR,
    ] {
        out.push_str(&format!("{var}={}\n", std::env::var(var).unwrap_or_else(|_| "(not set)".into())));
    }
    out
}

/// A line of progress, kept in startup.txt until the first window frame is drawn. A crash inside a native library (the
/// window system, the GL driver) kills the process without any Rust error, then this file is all that is left.
pub fn stage(text: &str) {
    use std::io::Write;
    if let Some(dir) = report_dir() {
        if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(dir.join(STARTUP_FILE)) {
            let _ = writeln!(file, "{} {text}", now_secs());
        }
    }
}

/// The window is up and drawing: the start worked, so the progress file is not needed.
pub fn startup_finished() {
    if let Some(dir) = report_dir() {
        let _ = std::fs::remove_file(dir.join(STARTUP_FILE));
    }
}

/// The previous start never reached its first frame and left no error: it was killed by something native.
fn report_unfinished_start() {
    let dir = match report_dir() {
        Some(dir) => dir,
        None => return,
    };
    let startup = dir.join(STARTUP_FILE);
    if let Ok(steps) = std::fs::read_to_string(&startup) {
        let text = format!(
            "The previous start of the launcher ended before its window drew a frame, without any error message.\n\
             That is how a crash in the window system or the graphics driver looks. Its steps (unix seconds, step):\n\n{steps}\n{}",
            environment_report()
        );
        let _ = std::fs::write(dir.join(CRASH_FILE), text);
        let _ = std::fs::remove_file(startup);
    }
}

/// The release build has no console on Windows and says nothing useful on Linux when it dies, so the reason goes into crash.txt.
fn record_failure(text: &str) {
    eprintln!("strata-launcher: {text}");
    let mut report = String::new();
    report.push_str("The Strata launcher crashed.\n\n");
    report.push_str(text);
    report.push_str("\n\n");
    report.push_str(&environment_report());
    if let Some(dir) = report_dir() {
        if let Ok(steps) = std::fs::read_to_string(dir.join(STARTUP_FILE)) {
            report.push_str("\nsteps before it failed (unix seconds, step):\n");
            report.push_str(&steps);
        }
        let path = dir.join(CRASH_FILE);
        if std::env::var_os(SAFE_MODE_VAR).is_some() {
            // the safe mode try failed too: keep the report of the first try above it
            let first = std::fs::read_to_string(&path).unwrap_or_default();
            let _ = std::fs::write(&path, format!("{first}\n---- second try (safe mode: X11, software rendering) ----\n\n{report}"));
        } else {
            let _ = std::fs::write(&path, report);
        }
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
    // this start is already recorded in crash.txt, the new process begins with a clean progress file
    if let Some(dir) = report_dir() {
        let _ = std::fs::remove_file(dir.join(STARTUP_FILE));
    }
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
    stage("opening the window");
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
    std::panic::set_hook(Box::new(|info| {
        let backtrace = std::backtrace::Backtrace::force_capture();
        record_failure(&format!("the launcher panicked: {info}\n\n{backtrace}"));
    }));

    // the second try (safe mode) is started by a launcher that already wrote its own report
    if std::env::var_os(SAFE_MODE_VAR).is_none() {
        report_unfinished_start();
    }
    stage("starting");

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
