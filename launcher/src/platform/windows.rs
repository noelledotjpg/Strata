use crate::config::Res;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use winreg::{enums::HKEY_CURRENT_USER, RegKey};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const DETACHED_PROCESS: u32 = 0x0000_0008;
const UNINSTALL_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\Strata";

pub fn game_exe(game_dir: &Path) -> PathBuf {
    game_dir.join("Strata.exe")
}

fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "''"))
}

fn powershell(script: &str) -> Res<()> {
    let status = Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
        .creation_flags(CREATE_NO_WINDOW)
        .status()?;
    if status.success() {
        Ok(())
    } else {
        Err("powershell failed".into())
    }
}

fn shortcut_script(folder: &str, launcher: &Path, icon: &Path) -> String {
    format!(
        "$l = Join-Path ([Environment]::GetFolderPath('{folder}')) 'Strata.lnk'; \
         $s = $ws.CreateShortcut($l); \
         $s.TargetPath = {target}; \
         $s.WorkingDirectory = {work}; \
         $s.IconLocation = {icon}; \
         $s.Save()",
        folder = folder,
        target = quote(&launcher.to_string_lossy()),
        work = quote(&launcher.parent().unwrap_or(Path::new(".")).to_string_lossy()),
        icon = quote(&format!("{},0", icon.to_string_lossy())),
    )
}

pub fn install_integration(launcher: &Path, game_dir: &Path, install_dir: &Path, version: &str, desktop: bool) {
    let icon = game_exe(game_dir);

    let mut script = String::from("$ws = New-Object -ComObject WScript.Shell; ");
    script.push_str(&shortcut_script("Programs", launcher, &icon));
    if desktop {
        script.push_str("; ");
        script.push_str(&shortcut_script("Desktop", launcher, &icon));
    }
    let _ = powershell(&script);

    let _ = (|| -> Res<()> {
        let hkcu = RegKey::predef(HKEY_CURRENT_USER);
        let (key, _) = hkcu.create_subkey(UNINSTALL_KEY)?;
        key.set_value("DisplayName", &"Strata")?;
        key.set_value("DisplayVersion", &version.to_string())?;
        key.set_value("Publisher", &"noelledotjpg")?;
        key.set_value("InstallLocation", &install_dir.to_string_lossy().to_string())?;
        key.set_value("DisplayIcon", &icon.to_string_lossy().to_string())?;
        key.set_value("UninstallString", &format!("\"{}\" --uninstall", launcher.display()))?;
        key.set_value("NoModify", &1u32)?;
        key.set_value("NoRepair", &1u32)?;
        Ok(())
    })();
}

pub fn remove_integration() {
    let _ = powershell(
        "Remove-Item -Force -ErrorAction SilentlyContinue \
         (Join-Path ([Environment]::GetFolderPath('Programs')) 'Strata.lnk'), \
         (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Strata.lnk')",
    );
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let _ = hkcu.delete_subkey_all(UNINSTALL_KEY);
}

/// Windows will not delete a running exe, so a detached helper does it after we exit.
pub fn schedule_self_delete(exe: &Path, dir: &Path) {
    let cmd = format!(
        "/C ping -n 3 127.0.0.1 >nul & del /f /q \"{}\" & rmdir \"{}\"",
        exe.display(),
        dir.display()
    );
    let _ = Command::new("cmd")
        .raw_arg(cmd)
        .creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS)
        .spawn();
}
