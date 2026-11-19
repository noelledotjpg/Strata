use crate::config::{Res, GAME_FOLDER, LAUNCHER_NAME};
use crate::net::{download, Release};
use crate::platform;
use crate::storage::{clear_state, game_data_dir, same_file, write_state, InstallInfo};
use crate::task::Reporter;
use std::fs::{self, File};
use std::path::{Path, PathBuf};

fn extract(rep: &Reporter, zip_path: &Path, dest: &Path) -> Res<()> {
    let mut archive = zip::ZipArchive::new(File::open(zip_path)?)?;
    let count = archive.len();
    fs::create_dir_all(dest)?;

    for i in 0..count {
        let mut entry = archive.by_index(i)?;
        // enclosed_name() rejects "../" and absolute paths (zip-slip)
        let rel = match entry.enclosed_name() {
            Some(p) => p.to_path_buf(),
            None => continue,
        };
        let out_path = dest.join(rel);

        if entry.is_dir() {
            fs::create_dir_all(&out_path)?;
        } else {
            if let Some(parent) = out_path.parent() {
                fs::create_dir_all(parent)?;
            }

            #[cfg(unix)]
            {
                use std::io::Read;
                let mode = entry.unix_mode().unwrap_or(0);
                if mode & 0o170000 == 0o120000 {
                    let mut target = String::new();
                    entry.read_to_string(&mut target)?;
                    std::os::unix::fs::symlink(target, &out_path)?;
                    continue;
                }
            }

            let mut out = File::create(&out_path)?;
            std::io::copy(&mut entry, &mut out)?;
            drop(out);

            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                if let Some(mode) = entry.unix_mode() {
                    fs::set_permissions(&out_path, fs::Permissions::from_mode(mode & 0o777))?;
                }
            }
        }

        if i % 25 == 0 || i + 1 == count {
            rep.progress("Extracting", (i + 1) as u64, count as u64);
        }
    }
    Ok(())
}

pub fn do_install(rep: &Reporter, rel: &Release, dir: &Path, desktop_shortcut: bool) -> Res<InstallInfo> {
    fs::create_dir_all(dir).map_err(|e| format!("Could not create {}: {e}", dir.display()))?;

    let zip_path = std::env::temp_dir().join("strata-download.zip");
    download(rep, &rel.url, &zip_path, rel.size)?;

    let staging = dir.join(".staging");
    let _ = fs::remove_dir_all(&staging);
    extract(rep, &zip_path, &staging)?;
    let _ = fs::remove_file(&zip_path);

    let extracted = staging.join(GAME_FOLDER);
    if !extracted.is_dir() {
        return Err(format!("The download does not contain a \"{GAME_FOLDER}\" folder").into());
    }

    rep.progress("Installing", 0, 0);
    let game = dir.join(GAME_FOLDER);
    if game.exists() {
        fs::remove_dir_all(&game)
            .map_err(|e| format!("Could not replace the old version ({e}). Is Strata still running?"))?;
    }
    fs::rename(&extracted, &game)?;
    let _ = fs::remove_dir_all(&staging);

    // installed copies keep worlds and options in the user's data folder
    let _ = fs::remove_file(game.join("portable.txt"));

    // shortcuts and the Settings > Apps entry point at this copy
    let launcher = dir.join(LAUNCHER_NAME);
    let me = std::env::current_exe()?;
    if !same_file(&me, &launcher) {
        fs::copy(&me, &launcher).map_err(|e| format!("Could not copy the launcher: {e}"))?;
    }

    platform::install_integration(&launcher, &game, dir, &rel.tag, desktop_shortcut);

    let info = InstallInfo { version: rel.tag.clone(), path: dir.to_string_lossy().into_owned() };
    write_state(&info)?;
    Ok(info)
}

pub fn do_uninstall(info: &InstallInfo, delete_data: bool) -> Res<()> {
    let dir = PathBuf::from(&info.path);

    // never delete the whole folder the user picked, only what was installed
    let game = dir.join(GAME_FOLDER);
    if game.exists() {
        fs::remove_dir_all(&game)
            .map_err(|e| format!("Could not delete {} ({e}). Is Strata still running?", game.display()))?;
    }
    let _ = fs::remove_dir_all(dir.join(".staging"));

    platform::remove_integration();

    let launcher = dir.join(LAUNCHER_NAME);
    if launcher.exists() && fs::remove_file(&launcher).is_err() {
        platform::schedule_self_delete(&launcher, &dir);
    }
    let _ = fs::remove_dir(&dir);

    if delete_data {
        if let Some(data) = game_data_dir() {
            if data.exists() {
                fs::remove_dir_all(&data).map_err(|e| format!("Could not delete {} ({e})", data.display()))?;
            }
        }
    }

    clear_state();
    Ok(())
}
