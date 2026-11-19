use super::{Action, App, Remote};
use crate::config::{DEFAULT_USERNAME, MAX_USERNAME_LEN};
use crate::storage::game_data_dir;
use crate::ui::style::{ERROR, SUCCESS, SUBTLE};
use crate::ui::tabs::{tab_bar, Tab};
use crate::ui::widgets::{button, default_button, heading, page_header, progress_bar, text_field};
use eframe::egui::{self, Color32};

fn download_size(bytes: u64) -> String {
    if bytes == 0 {
        String::new()
    } else {
        format!("  ·  {:.0} MB download", bytes as f64 / 1_048_576.0)
    }
}

fn work_in_progress(ui: &mut egui::Ui) {
    ui.centered_and_justified(|ui| {
        ui.label(egui::RichText::new("WIP").size(28.0).strong().color(Color32::from_gray(175)));
    });
}

impl App {
    fn show_status(&self, ui: &mut egui::Ui) {
        let accent = self.screen.accent();
        if let Some(b) = &self.busy {
            if b.total > 0 {
                let frac = (b.done as f32 / b.total as f32).clamp(0.0, 1.0);
                progress_bar(ui, frac, &format!("{} {}%", b.stage, (frac * 100.0) as u32), accent);
            } else {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label(format!("{}…", b.stage));
                });
            }
        } else if let Some((text, is_error)) = &self.message {
            let color = if *is_error { ERROR } else { SUCCESS };
            ui.label(egui::RichText::new(text.as_str()).color(color));
        }
    }

    pub(super) fn content_install(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let accent = self.screen.accent();

        page_header(
            ui,
            accent,
            "Install Strata",
            "Downloads the game from GitHub for your user account. No admin rights needed.",
        );
        ui.add_space(6.0);

        ui.label("Install location");
        text_field(ui, "install_path", &mut self.install_path, "", None, !busy, accent);
        ui.add_enabled(!busy, egui::Checkbox::new(&mut self.desktop_shortcut, "Create a desktop shortcut"));
        ui.add_space(6.0);

        match &self.remote {
            Remote::Checking => {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label("Checking the latest version…");
                });
            }
            Remote::Failed(e) => {
                ui.label(egui::RichText::new(e.as_str()).color(ERROR));
                if ui.add(button("Try again")).clicked() {
                    *action = Some(Action::Retry);
                }
            }
            Remote::Ready(r) => {
                ui.label(format!("Latest version: {}{}", r.tag, download_size(r.size)));
            }
        }

        self.show_status(ui);
    }

    pub(super) fn content_main(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let accent = self.screen.accent();
        let version = self.installed.as_ref().map(|i| i.version.clone()).unwrap_or_default();

        ui.horizontal(|ui| {
            ui.label(heading("Strata", accent));
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.label(egui::RichText::new(version.as_str()).color(SUBTLE));
            });
        });
        tab_bar(ui, &mut self.tab, accent);
        ui.add_space(6.0);

        let tab = self.tab;
        match tab {
            Tab::Play => self.tab_play(ui, action, &version),
            Tab::News | Tab::Worlds | Tab::Servers => {
                self.show_status(ui);
                work_in_progress(ui);
            }
        }
    }

    fn tab_play(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>, version: &str) {
        let busy = self.busy.is_some();
        let accent = self.screen.accent();

        match &self.remote {
            Remote::Ready(r) if r.tag != version => {
                ui.label(egui::RichText::new(format!("A new version is available: {}", r.tag)).color(accent).strong());
            }
            Remote::Ready(_) => {
                ui.label("You are up to date.");
            }
            Remote::Checking => {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label("Checking for updates…");
                });
            }
            Remote::Failed(_) => {
                ui.label("Could not check for updates.");
                if ui.add(button("Try again")).clicked() {
                    *action = Some(Action::Retry);
                }
            }
        }

        ui.add_space(6.0);
        ui.label("Username");
        let field = text_field(ui, "username", &mut self.username, DEFAULT_USERNAME, Some(MAX_USERNAME_LEN), !busy, accent);
        if field.lost_focus() {
            self.commit_username();
        }

        ui.add_space(4.0);
        self.show_status(ui);
    }

    pub(super) fn content_confirm_uninstall(&mut self, ui: &mut egui::Ui) {
        let busy = self.busy.is_some();
        let accent = self.screen.accent();

        page_header(ui, accent, "Uninstall Strata?", "This will remove:");
        ui.label("•  The game and this launcher");
        ui.label("•  Start menu and desktop shortcuts");
        ui.add_space(6.0);

        ui.add_enabled(!busy, egui::Checkbox::new(&mut self.delete_data, "Also delete my worlds and options"));
        if let Some(dir) = game_data_dir() {
            ui.label(egui::RichText::new(dir.to_string_lossy().into_owned()).small().weak());
        }
        if self.delete_data {
            ui.label(egui::RichText::new("Deleted worlds cannot be recovered.").color(ERROR));
        }
        ui.add_space(4.0);
        self.show_status(ui);
    }

    pub(super) fn content_uninstalled(&mut self, ui: &mut egui::Ui) {
        let accent = self.screen.accent();

        page_header(ui, accent, "Strata was uninstalled", "The game, its shortcuts and the launcher were removed.");
        ui.add_space(8.0);

        if self.data_deleted {
            ui.label("Your worlds and options were deleted too.");
        } else {
            ui.label("Your worlds and options were kept in:");
            if let Some(dir) = game_data_dir() {
                ui.label(egui::RichText::new(dir.to_string_lossy().into_owned()).small().weak());
            }
        }
        ui.add_space(8.0);
        ui.label("You can close this window.");
    }

    pub(super) fn footer_install(&self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let ready = matches!(self.remote, Remote::Ready(_));
        let accent = self.screen.accent();
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.add_enabled(!busy, button("Close")).clicked() {
                *action = Some(Action::Close);
            }
            if ui.add_enabled(ready && !busy, default_button("Install", accent)).clicked() {
                *action = Some(Action::Install);
            }
        });
    }

    pub(super) fn footer_main(&self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let accent = self.screen.accent();
        let update_available = match (&self.remote, &self.installed) {
            (Remote::Ready(r), Some(i)) => r.tag != i.version,
            _ => false,
        };
        ui.horizontal(|ui| {
            if ui.add_enabled(!busy, button("Uninstall")).clicked() {
                *action = Some(Action::AskUninstall);
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.add_enabled(!busy, default_button("Play", accent)).clicked() {
                    *action = Some(Action::Play);
                }
                if update_available && ui.add_enabled(!busy, button("Update")).clicked() {
                    *action = Some(Action::Install);
                }
            });
        });
    }

    pub(super) fn footer_confirm_uninstall(&self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let accent = self.screen.accent();
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.add_enabled(!busy, button("Cancel")).clicked() {
                *action = Some(Action::CancelUninstall);
            }
            if ui.add_enabled(!busy, default_button("Uninstall", accent)).clicked() {
                *action = Some(Action::Uninstall);
            }
        });
    }

    pub(super) fn footer_uninstalled(&self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let accent = self.screen.accent();
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.add(default_button("Close", accent)).clicked() {
                *action = Some(Action::Close);
            }
        });
    }
}
