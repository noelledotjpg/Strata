use crate::net::Release;
use crate::storage::InstallInfo;
use eframe::egui;
use std::sync::mpsc::Sender;

pub enum Msg {
    Remote(Result<Release, String>),
    Progress { stage: String, done: u64, total: u64 },
    InstallDone(Result<InstallInfo, String>),
    UninstallDone(Result<(), String>),
}

#[derive(Clone)]
pub struct Reporter {
    tx: Sender<Msg>,
    ctx: egui::Context,
}

impl Reporter {
    pub fn new(tx: Sender<Msg>, ctx: egui::Context) -> Self {
        Self { tx, ctx }
    }

    pub fn send(&self, m: Msg) {
        let _ = self.tx.send(m);
        self.ctx.request_repaint();
    }

    pub fn progress(&self, stage: &str, done: u64, total: u64) {
        self.send(Msg::Progress { stage: stage.to_string(), done, total });
    }
}
