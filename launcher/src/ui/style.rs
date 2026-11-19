use eframe::egui::{self, Color32, FontData, FontDefinitions, FontFamily, FontId, Rounding, Stroke, TextStyle};
use std::fs;
use std::path::Path;

pub const BLUE: Color32 = Color32::from_rgb(0, 120, 215);
pub const GREEN: Color32 = Color32::from_rgb(46, 125, 50);
pub const RED: Color32 = Color32::from_rgb(176, 42, 42);
pub const MUTED_RED: Color32 = Color32::from_rgb(128, 64, 64);

pub const ERROR: Color32 = Color32::from_rgb(190, 40, 40);
pub const SUCCESS: Color32 = Color32::from_rgb(30, 120, 50);
pub const SUBTLE: Color32 = Color32::from_rgb(90, 90, 90);

pub fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let lerp = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
    Color32::from_rgb(lerp(a.r(), b.r()), lerp(a.g(), b.g()), lerp(a.b(), b.b()))
}

pub fn install_fonts(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();
    if cfg!(windows) {
        let windir = std::env::var("WINDIR").unwrap_or_else(|_| r"C:\Windows".into());
        if let Ok(bytes) = fs::read(Path::new(&windir).join("Fonts").join("segoeui.ttf")) {
            fonts.font_data.insert("segoe".to_owned(), FontData::from_owned(bytes));
            if let Some(family) = fonts.families.get_mut(&FontFamily::Proportional) {
                family.insert(0, "segoe".to_owned());
            }
        }
    }
    ctx.set_fonts(fonts);
}

/// egui draws its own widgets, so this is a close imitation of Win32 controls, tinted per screen.
pub fn apply_style(ctx: &egui::Context, accent: Color32) {
    let mut style = (*ctx.style()).clone();
    style.text_styles.insert(TextStyle::Body, FontId::proportional(13.0));
    style.text_styles.insert(TextStyle::Button, FontId::proportional(13.0));
    style.text_styles.insert(TextStyle::Small, FontId::proportional(11.0));
    style.text_styles.insert(TextStyle::Heading, FontId::proportional(18.0));
    style.spacing.item_spacing = egui::vec2(6.0, 6.0);
    style.spacing.button_padding = egui::vec2(8.0, 3.0);
    style.spacing.interact_size.y = 23.0;

    let square = Rounding::ZERO;
    let black = Color32::BLACK;
    let hover_fill = mix(Color32::WHITE, accent, 0.10);
    let press_fill = mix(Color32::WHITE, accent, 0.22);

    let mut v = egui::Visuals::light();
    v.window_fill = Color32::WHITE;
    v.panel_fill = Color32::WHITE;
    v.extreme_bg_color = Color32::WHITE;
    v.selection.bg_fill = accent;
    v.selection.stroke = Stroke::new(1.0, accent);
    v.window_rounding = square;
    v.menu_rounding = square;

    let w = &mut v.widgets;
    w.noninteractive.rounding = square;
    w.noninteractive.bg_stroke = Stroke::new(1.0, Color32::from_rgb(213, 213, 213));
    w.noninteractive.fg_stroke = Stroke::new(1.0, black);

    w.inactive.rounding = square;
    w.inactive.bg_fill = Color32::from_rgb(225, 225, 225);
    w.inactive.weak_bg_fill = Color32::from_rgb(225, 225, 225);
    w.inactive.bg_stroke = Stroke::new(1.0, Color32::from_rgb(173, 173, 173));
    w.inactive.fg_stroke = Stroke::new(1.0, black);

    w.hovered.rounding = square;
    w.hovered.bg_fill = hover_fill;
    w.hovered.weak_bg_fill = hover_fill;
    w.hovered.bg_stroke = Stroke::new(1.0, accent);
    w.hovered.fg_stroke = Stroke::new(1.0, black);

    w.active.rounding = square;
    w.active.bg_fill = press_fill;
    w.active.weak_bg_fill = press_fill;
    w.active.bg_stroke = Stroke::new(1.0, mix(accent, black, 0.35));
    w.active.fg_stroke = Stroke::new(1.0, black);

    w.open.rounding = square;

    style.visuals = v;
    ctx.set_style(style);
}
