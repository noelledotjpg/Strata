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

fn window_icon() -> egui::IconData {
    let img = image::load_from_memory(include_bytes!("../assets/icon.png"))
        .expect("icon png is valid")
        .to_rgba8();
    egui::IconData { width: img.width(), height: img.height(), rgba: img.into_raw() }
}

fn main() -> eframe::Result<()> {
    let open_uninstall = std::env::args().any(|a| a == "--uninstall");

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title(config::WINDOW_TITLE)
            .with_icon(window_icon())
            .with_inner_size(ui::WINDOW_SIZE)
            .with_resizable(false)
            .with_maximize_button(false),
        centered: true,
        ..Default::default()
    };

    eframe::run_native(
        config::WINDOW_TITLE,
        options,
        Box::new(move |cc| Ok(Box::new(ui::App::new(cc, open_uninstall)))),
    )
}
