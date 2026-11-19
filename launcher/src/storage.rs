use crate::config::{Res, GAME_FOLDER};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Clone, Serialize, Deserialize)]
pub struct InstallInfo {
    pub version: String,
    pub path: String,
}

pub fn default_install_dir() -> PathBuf {
    let base = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    if cfg!(windows) {
        base.join("Programs").join("Strata")
    } else {
        base.join("strata-game")
    }
}

pub fn game_data_dir() -> Option<PathBuf> {
    dirs::data_dir().map(|d| d.join(if cfg!(windows) { "Strata" } else { "strata" }))
}

fn state_path() -> Option<PathBuf> {
    dirs::data_local_dir().map(|d| d.join("StrataLauncher").join("install.json"))
}

pub fn read_state() -> Option<InstallInfo> {
    let text = fs::read_to_string(state_path()?).ok()?;
    let info: InstallInfo = serde_json::from_str(&text).ok()?;
    if Path::new(&info.path).join(GAME_FOLDER).is_dir() {
        Some(info)
    } else {
        None
    }
}

pub fn write_state(info: &InstallInfo) -> Res<()> {
    let path = state_path().ok_or("no local data folder")?;
    fs::create_dir_all(path.parent().unwrap())?;
    fs::write(path, serde_json::to_string_pretty(info)?)?;
    Ok(())
}

pub fn clear_state() {
    if let Some(state) = state_path() {
        let _ = fs::remove_file(&state);
        if let Some(parent) = state.parent() {
            let _ = fs::remove_dir(parent);
        }
    }
}

pub fn same_file(a: &Path, b: &Path) -> bool {
    matches!((fs::canonicalize(a), fs::canonicalize(b)), (Ok(x), Ok(y)) if x == y)
}
