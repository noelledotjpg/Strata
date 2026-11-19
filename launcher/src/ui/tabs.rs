use super::style::{mix, SUBTLE};
use eframe::egui::{self, Color32, Rounding, Stroke};

const TAB_W: f32 = 66.0;
const TAB_H: f32 = 26.0;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Play,
    News,
    Worlds,
    Servers,
}

impl Tab {
    const ALL: [Tab; 4] = [Tab::Play, Tab::News, Tab::Worlds, Tab::Servers];

    fn title(self) -> &'static str {
        match self {
            Tab::Play => "Play",
            Tab::News => "News",
            Tab::Worlds => "Worlds",
            Tab::Servers => "Servers",
        }
    }
}

pub fn tab_bar(ui: &mut egui::Ui, current: &mut Tab, accent: Color32) {
    let (bar, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), TAB_H), egui::Sense::hover());
    let painter = ui.painter().clone();
    painter.line_segment(
        [bar.left_bottom(), bar.right_bottom()],
        Stroke::new(1.0, Color32::from_rgb(213, 213, 213)),
    );

    for (i, tab) in Tab::ALL.iter().enumerate() {
        let rect = egui::Rect::from_min_size(bar.min + egui::vec2(i as f32 * TAB_W, 0.0), egui::vec2(TAB_W, TAB_H));
        let resp = ui.interact(rect, ui.id().with(("tab", i)), egui::Sense::click());
        let selected = *current == *tab;

        if resp.hovered() && !selected {
            painter.rect_filled(rect, Rounding::ZERO, mix(Color32::WHITE, accent, 0.08));
            ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
        }
        if selected {
            let underline = egui::Rect::from_min_max(
                egui::pos2(rect.min.x, rect.max.y - 2.0),
                egui::pos2(rect.max.x, rect.max.y),
            );
            painter.rect_filled(underline, Rounding::ZERO, accent);
        }

        let color = if selected { accent } else { SUBTLE };
        painter.text(
            rect.center() - egui::vec2(0.0, 1.0),
            egui::Align2::CENTER_CENTER,
            tab.title(),
            egui::FontId::proportional(13.0),
            color,
        );

        if resp.clicked() {
            *current = *tab;
        }
    }
}
