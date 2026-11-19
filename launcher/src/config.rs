pub const REPO: &str = "noelledotjpg/Strata";
pub const GAME_FOLDER: &str = "Strata";
pub const PLATFORM: &str = if cfg!(windows) { "windows" } else { "linux" };
pub const LAUNCHER_NAME: &str = if cfg!(windows) { "StrataLauncher.exe" } else { "strata-launcher" };
pub const WINDOW_TITLE: &str = "Strata Launcher";

pub const DEFAULT_USERNAME: &str = "Hakumu";
pub const MAX_USERNAME_LEN: usize = 16;
pub const OPTIONS_FILE: &str = "options.txt";

pub type Res<T> = Result<T, Box<dyn std::error::Error>>;
