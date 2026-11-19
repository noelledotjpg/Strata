use eframe::egui;
use std::time::Instant;

const BANNER_SCALE: f32 = 1.0;
pub const BANNER_W: f32 = 128.0 * BANNER_SCALE;
pub const BANNER_H: f32 = 300.0 * BANNER_SCALE;
const FADE_SECS: f32 = 0.7;

const BAYER8: [[u8; 8]; 8] = [
    [0, 32, 8, 40, 2, 34, 10, 42],
    [48, 16, 56, 24, 50, 18, 58, 26],
    [12, 44, 4, 36, 14, 46, 6, 38],
    [60, 28, 52, 20, 62, 30, 54, 22],
    [3, 35, 11, 43, 1, 33, 9, 41],
    [51, 19, 59, 27, 49, 17, 57, 25],
    [15, 47, 7, 39, 13, 45, 5, 37],
    [63, 31, 55, 23, 61, 29, 53, 21],
];

pub const INSTALL: usize = 0;
pub const LAUNCH: usize = 1;
pub const UNINSTALL: usize = 2;

struct Fade {
    from: Vec<u8>,
    start: Instant,
}

/// Pixel-art banner that switches between images with an ordered-dither fade.
pub struct Banner {
    rgba: [Vec<u8>; 3],
    size: [usize; 2],
    tex: egui::TextureHandle,
    displayed: Vec<u8>,
    target: usize,
    fade: Option<Fade>,
}

fn decode(bytes: &[u8]) -> ([usize; 2], Vec<u8>) {
    let img = image::load_from_memory(bytes).expect("banner png is valid").to_rgba8();
    ([img.width() as usize, img.height() as usize], img.into_raw())
}

impl Banner {
    pub fn new(ctx: &egui::Context, initial: usize) -> Self {
        let (size, install) = decode(include_bytes!("../../assets/banner_install.png"));
        let (_, launch) = decode(include_bytes!("../../assets/banner_launch.png"));
        let (_, uninstall) = decode(include_bytes!("../../assets/banner_uninstall.png"));
        let rgba = [install, launch, uninstall];

        let displayed = rgba[initial].clone();
        let tex = ctx.load_texture(
            "banner",
            egui::ColorImage::from_rgba_unmultiplied(size, &displayed),
            egui::TextureOptions::NEAREST,
        );
        Self { rgba, size, tex, displayed, target: initial, fade: None }
    }

    pub fn texture_id(&self) -> egui::TextureId {
        self.tex.id()
    }

    pub fn update(&mut self, ctx: &egui::Context, target: usize) {
        if target != self.target {
            self.fade = Some(Fade { from: self.displayed.clone(), start: Instant::now() });
            self.target = target;
        }

        let mut finished = false;
        if let Some(fade) = &self.fade {
            let t = (fade.start.elapsed().as_secs_f32() / FADE_SECS).clamp(0.0, 1.0);
            let eased = t * t * (3.0 - 2.0 * t);
            let level = (eased * 64.0) as usize;

            let to = &self.rgba[self.target];
            let w = self.size[0];
            let mut out = Vec::with_capacity(to.len());
            for (i, (a, b)) in fade.from.chunks_exact(4).zip(to.chunks_exact(4)).enumerate() {
                let (x, y) = (i % w, i / w);
                out.extend_from_slice(if (BAYER8[y % 8][x % 8] as usize) < level { b } else { a });
            }
            self.displayed = out;
            self.tex.set(
                egui::ColorImage::from_rgba_unmultiplied(self.size, &self.displayed),
                egui::TextureOptions::NEAREST,
            );
            finished = t >= 1.0;
            ctx.request_repaint();
        }
        if finished {
            self.fade = None;
        }
    }
}
