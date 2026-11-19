use super::style::{mix, SUBTLE};
use eframe::egui::{self, Color32, Rounding, Stroke};

pub fn button(text: &str) -> egui::Button<'static> {
    egui::Button::new(text.to_string()).min_size(egui::vec2(75.0, 23.0))
}

pub fn default_button(text: &str, accent: Color32) -> egui::Button<'static> {
    button(text).stroke(Stroke::new(1.0, accent))
}

pub fn heading(text: &str, accent: Color32) -> egui::RichText {
    egui::RichText::new(text.to_string()).size(18.0).color(accent)
}

pub fn page_header(ui: &mut egui::Ui, accent: Color32, title: &str, subtitle: &str) {
    ui.label(heading(title, accent));
    let (bar, _) = ui.allocate_exact_size(egui::vec2(36.0, 3.0), egui::Sense::hover());
    ui.painter().rect_filled(bar, Rounding::ZERO, accent);
    if !subtitle.is_empty() {
        ui.label(egui::RichText::new(subtitle).color(SUBTLE));
    }
}

/// The built-in frame is rounded, so the square border is drawn here.
pub fn text_field(
    ui: &mut egui::Ui,
    id: &str,
    text: &mut String,
    hint: &str,
    max_chars: Option<usize>,
    enabled: bool,
    accent: Color32,
) -> egui::Response {
    let id = egui::Id::new(id);
    let focused = ui.memory(|m| m.has_focus(id));
    let border = if focused { accent } else { Color32::from_rgb(122, 122, 122) };
    ui.add_enabled_ui(enabled, |ui| {
        egui::Frame::none()
            .fill(Color32::WHITE)
            .stroke(Stroke::new(1.0, border))
            .rounding(Rounding::ZERO)
            .inner_margin(egui::Margin::symmetric(5.0, 3.0))
            .show(ui, |ui| {
                let mut edit = egui::TextEdit::singleline(text)
                    .id(id)
                    .frame(false)
                    .hint_text(hint)
                    .desired_width(f32::INFINITY);
                if let Some(max) = max_chars {
                    edit = edit.char_limit(max);
                }
                ui.add(edit)
            })
            .inner
    })
    .inner
}

pub fn progress_bar(ui: &mut egui::Ui, frac: f32, text: &str, accent: Color32) {
    let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 20.0), egui::Sense::hover());
    let painter = ui.painter();
    let inner = rect.shrink(1.0);
    painter.rect_filled(rect, Rounding::ZERO, Color32::from_rgb(188, 188, 188));
    painter.rect_filled(inner, Rounding::ZERO, Color32::from_rgb(230, 230, 230));
    let filled = egui::Rect::from_min_size(inner.min, egui::vec2(inner.width() * frac.clamp(0.0, 1.0), inner.height()));
    painter.rect_filled(filled, Rounding::ZERO, mix(Color32::WHITE, accent, 0.6));
    painter.text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        text,
        egui::FontId::proportional(13.0),
        Color32::BLACK,
    );
}
