mod screens;

use super::banner::{self, Banner};
use super::style::{self, BLUE, GREEN, MUTED_RED, RED};
use super::tabs::Tab;
use crate::config::GAME_FOLDER;
use crate::install::{do_install, do_uninstall};
use crate::net::{fetch_latest, Release};
use crate::options::{read_username, sanitize_username, write_username};
use crate::platform;
use crate::storage::{default_install_dir, read_state, InstallInfo};
use crate::task::{Msg, Reporter};
use eframe::egui::{self, Color32};
use std::path::PathBuf;
use std::process::Command;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;

pub const CONTENT_W: f32 = 372.0;
const FOOTER_H: f32 = 44.0;

#[derive(Clone)]
enum Remote {
    Checking,
    Ready(Release),
    Failed(String),
}

#[derive(Clone, Copy, PartialEq)]
enum Screen {
    Install,
    Main,
    ConfirmUninstall,
    Uninstalled,
}

impl Screen {
    fn accent(self) -> Color32 {
        match self {
            Screen::Install => BLUE,
            Screen::Main => GREEN,
            Screen::ConfirmUninstall => RED,
            Screen::Uninstalled => MUTED_RED,
        }
    }

    fn banner(self) -> usize {
        match self {
            Screen::Install => banner::INSTALL,
            Screen::Main => banner::LAUNCH,
            Screen::ConfirmUninstall | Screen::Uninstalled => banner::UNINSTALL,
        }
    }
}

enum Action {
    Install,
    Play,
    AskUninstall,
    CancelUninstall,
    Uninstall,
    Retry,
    Close,
}

struct Busy {
    stage: String,
    done: u64,
    total: u64,
}

pub struct App {
    banner: Banner,
    tx: Sender<Msg>,
    rx: Receiver<Msg>,
    installed: Option<InstallInfo>,
    remote: Remote,
    screen: Screen,
    tab: Tab,
    styled_accent: Color32,
    install_path: String,
    desktop_shortcut: bool,
    delete_data: bool,
    data_deleted: bool,
    username: String,
    busy: Option<Busy>,
    message: Option<(String, bool)>,
    first_frame_reported: bool,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>, open_uninstall: bool) -> Self {
        crate::stage("window created, building the interface");
        style::install_fonts(&cc.egui_ctx);

        let installed = read_state();
        let screen = match (&installed, open_uninstall) {
            (None, _) => Screen::Install,
            (Some(_), true) => Screen::ConfirmUninstall,
            (Some(_), false) => Screen::Main,
        };
        let accent = screen.accent();
        style::apply_style(&cc.egui_ctx, accent);

        let (tx, rx) = channel();
        let mut app = App {
            banner: Banner::new(&cc.egui_ctx, screen.banner()),
            tx,
            rx,
            installed,
            remote: Remote::Checking,
            screen,
            tab: Tab::Play,
            styled_accent: accent,
            install_path: default_install_dir().to_string_lossy().into_owned(),
            desktop_shortcut: true,
            delete_data: false,
            data_deleted: false,
            username: read_username().unwrap_or_default(),
            busy: None,
            message: None,
            first_frame_reported: false,
        };
        app.check_remote(&cc.egui_ctx);
        app
    }

    fn reporter(&self, ctx: &egui::Context) -> Reporter {
        Reporter::new(self.tx.clone(), ctx.clone())
    }

    fn check_remote(&mut self, ctx: &egui::Context) {
        self.remote = Remote::Checking;
        let rep = self.reporter(ctx);
        thread::spawn(move || {
            let result = fetch_latest();
            rep.send(Msg::Remote(result));
        });
    }

    fn start_install(&mut self, ctx: &egui::Context) {
        let release = match &self.remote {
            Remote::Ready(r) => r.clone(),
            _ => return,
        };
        let (dir, desktop) = match &self.installed {
            // an update must not bring back a desktop shortcut the user may have removed
            Some(info) => (PathBuf::from(&info.path), false),
            None => (PathBuf::from(self.install_path.trim()), self.desktop_shortcut),
        };
        if dir.as_os_str().is_empty() {
            self.message = Some(("Pick an install location first.".into(), true));
            return;
        }

        self.message = None;
        self.busy = Some(Busy { stage: "Starting".into(), done: 0, total: 0 });
        let rep = self.reporter(ctx);
        thread::spawn(move || {
            let result = do_install(&rep, &release, &dir, desktop).map_err(|e| e.to_string());
            rep.send(Msg::InstallDone(result));
        });
    }

    fn start_uninstall(&mut self, ctx: &egui::Context) {
        let info = match &self.installed {
            Some(i) => i.clone(),
            None => return,
        };
        self.message = None;
        self.busy = Some(Busy { stage: "Uninstalling".into(), done: 0, total: 0 });
        let delete_data = self.delete_data;
        let rep = self.reporter(ctx);
        thread::spawn(move || {
            let result = do_uninstall(&info, delete_data).map_err(|e| e.to_string());
            rep.send(Msg::UninstallDone(result));
        });
    }

    fn commit_username(&mut self) {
        let clean = sanitize_username(&self.username);
        let current = read_username();
        if self.username.trim().is_empty() && current.is_none() {
            return;
        }
        if current.as_deref() != Some(clean.as_str()) {
            if let Err(e) = write_username(&clean) {
                self.message = Some((format!("Could not save the username: {e}"), true));
                return;
            }
        }
        self.username = clean;
    }

    fn play(&mut self, ctx: &egui::Context) {
        let info = match &self.installed {
            Some(i) => i.clone(),
            None => return,
        };
        self.commit_username();
        let game_dir = PathBuf::from(&info.path).join(GAME_FOLDER);
        let exe = platform::game_exe(&game_dir);
        match Command::new(&exe).current_dir(&game_dir).spawn() {
            Ok(_) => ctx.send_viewport_cmd(egui::ViewportCommand::Close),
            Err(e) => self.message = Some((format!("Could not start the game: {e}"), true)),
        }
    }

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::Remote(r) => {
                self.remote = match r {
                    Ok(rel) => Remote::Ready(rel),
                    Err(e) => Remote::Failed(e),
                }
            }
            Msg::Progress { stage, done, total } => {
                self.busy = Some(Busy { stage, done, total });
            }
            Msg::InstallDone(Ok(info)) => {
                self.message = Some((format!("Strata {} is installed.", info.version), false));
                self.installed = Some(info);
                self.busy = None;
                self.screen = Screen::Main;
                self.tab = Tab::Play;
                self.username = read_username().unwrap_or_default();
            }
            Msg::InstallDone(Err(e)) => {
                self.busy = None;
                self.message = Some((e, true));
            }
            Msg::UninstallDone(Ok(())) => {
                self.installed = None;
                self.busy = None;
                self.message = None;
                self.data_deleted = self.delete_data;
                self.screen = Screen::Uninstalled;
            }
            Msg::UninstallDone(Err(e)) => {
                self.busy = None;
                self.message = Some((e, true));
            }
        }
    }

    fn apply(&mut self, ctx: &egui::Context, action: Action) {
        match action {
            Action::Install => self.start_install(ctx),
            Action::Play => self.play(ctx),
            Action::AskUninstall => {
                self.message = None;
                self.screen = Screen::ConfirmUninstall;
            }
            Action::CancelUninstall => {
                self.message = None;
                self.screen = Screen::Main;
            }
            Action::Uninstall => self.start_uninstall(ctx),
            Action::Retry => self.check_remote(ctx),
            Action::Close => ctx.send_viewport_cmd(egui::ViewportCommand::Close),
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if !self.first_frame_reported {
            self.first_frame_reported = true;
            crate::startup_finished();
        }
        while let Ok(msg) = self.rx.try_recv() {
            self.handle(msg);
        }

        let screen = self.screen;
        let accent = screen.accent();
        if accent != self.styled_accent {
            style::apply_style(ctx, accent);
            self.styled_accent = accent;
        }
        self.banner.update(ctx, screen.banner());
        let banner_id = self.banner.texture_id();
        let mut action: Option<Action> = None;

        egui::SidePanel::left("banner")
            .exact_width(banner::BANNER_W)
            .resizable(false)
            .show_separator_line(false)
            .frame(egui::Frame::none())
            .show(ctx, |ui| {
                ui.add(egui::Image::new((banner_id, egui::vec2(banner::BANNER_W, banner::BANNER_H))));
            });

        egui::TopBottomPanel::bottom("footer")
            .exact_height(FOOTER_H)
            .resizable(false)
            .show_separator_line(true)
            .frame(
                egui::Frame::none()
                    .fill(Color32::from_rgb(240, 240, 240))
                    .inner_margin(egui::Margin::symmetric(12.0, 9.0)),
            )
            .show(ctx, |ui| match screen {
                Screen::Install => self.footer_install(ui, &mut action),
                Screen::Main => self.footer_main(ui, &mut action),
                Screen::ConfirmUninstall => self.footer_confirm_uninstall(ui, &mut action),
                Screen::Uninstalled => self.footer_uninstalled(ui, &mut action),
            });

        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(Color32::WHITE).inner_margin(egui::Margin::symmetric(16.0, 12.0)))
            .show(ctx, |ui| match screen {
                Screen::Install => self.content_install(ui, &mut action),
                Screen::Main => self.content_main(ui, &mut action),
                Screen::ConfirmUninstall => self.content_confirm_uninstall(ui),
                Screen::Uninstalled => self.content_uninstalled(ui),
            });

        if let Some(action) = action {
            self.apply(ctx, action);
        }
    }
}
