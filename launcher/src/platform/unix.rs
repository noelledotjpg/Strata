use std::fs;
use std::path::{Path, PathBuf};

pub fn game_exe(game_dir: &Path) -> PathBuf {
    game_dir.join("bin").join("Strata")
}

fn desktop_entry(launcher: &Path, game_dir: &Path) -> String {
    format!(
        "[Desktop Entry]\nType=Application\nName=Strata\nComment=Strata launcher\nExec=\"{}\"\nIcon={}\nTerminal=false\nCategories=Game;\n",
        launcher.display(),
        game_dir.join("lib").join("Strata.png").display()
    )
}

pub fn install_integration(launcher: &Path, game_dir: &Path, _install_dir: &Path, _version: &str, desktop: bool) {
    let entry = desktop_entry(launcher, game_dir);

    if let Some(apps) = dirs::data_dir().map(|d| d.join("applications")) {
        let _ = fs::create_dir_all(&apps);
        let _ = fs::write(apps.join("strata.desktop"), &entry);
    }
    if desktop {
        if let Some(path) = dirs::desktop_dir().map(|d| d.join("Strata.desktop")) {
            if fs::write(&path, &entry).is_ok() {
                use std::os::unix::fs::PermissionsExt;
                let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o755));
            }
        }
    }
}

pub fn remove_integration() {
    if let Some(p) = dirs::data_dir().map(|d| d.join("applications").join("strata.desktop")) {
        let _ = fs::remove_file(p);
    }
    if let Some(p) = dirs::desktop_dir().map(|d| d.join("Strata.desktop")) {
        let _ = fs::remove_file(p);
    }
}

pub fn schedule_self_delete(_exe: &Path, _dir: &Path) {}
