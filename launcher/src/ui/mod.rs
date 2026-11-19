mod app;
mod banner;
mod style;
mod tabs;
mod widgets;

pub use app::App;

pub const WINDOW_SIZE: [f32; 2] = [banner::BANNER_W + app::CONTENT_W, banner::BANNER_H];
