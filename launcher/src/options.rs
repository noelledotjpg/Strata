use crate::config::{Res, DEFAULT_USERNAME, MAX_USERNAME_LEN, OPTIONS_FILE};
use crate::storage::game_data_dir;
use std::fs;
use std::path::{Path, PathBuf};

// The game stores options in a java.util.Properties file (ISO-8859-1).
// Only the `username` line is ever touched.

fn options_path() -> Option<PathBuf> {
    game_data_dir().map(|d| d.join(OPTIONS_FILE))
}

fn read_latin1(path: &Path) -> Option<String> {
    let bytes = fs::read(path).ok()?;
    Some(bytes.iter().map(|&b| b as char).collect())
}

fn unescape(s: &str) -> String {
    let mut units: Vec<u16> = Vec::new();
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            let mut b = [0u16; 2];
            units.extend_from_slice(c.encode_utf16(&mut b));
            continue;
        }
        match it.next() {
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                if let Ok(u) = u16::from_str_radix(&hex, 16) {
                    units.push(u);
                }
            }
            Some('t') => units.push(9),
            Some('n') => units.push(10),
            Some('r') => units.push(13),
            Some('f') => units.push(12),
            Some(other) => {
                let mut b = [0u16; 2];
                units.extend_from_slice(other.encode_utf16(&mut b));
            }
            None => {}
        }
    }
    String::from_utf16_lossy(&units)
}

fn escape_value(s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            ' '..='~' => out.push(c),
            _ => {
                let mut b = [0u16; 2];
                for u in c.encode_utf16(&mut b).iter() {
                    out.push_str(&format!("\\u{:04X}", u));
                }
            }
        }
    }
    out
}

fn parse_property(line: &str) -> Option<(String, String)> {
    let line = line.trim_start();
    if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
        return None;
    }
    let mut key_end = line.len();
    let mut escaped = false;
    for (i, c) in line.char_indices() {
        if escaped {
            escaped = false;
        } else if c == '\\' {
            escaped = true;
        } else if c == '=' || c == ':' || c.is_whitespace() {
            key_end = i;
            break;
        }
    }
    let mut rest = line[key_end..].trim_start();
    if rest.starts_with('=') || rest.starts_with(':') {
        rest = rest[1..].trim_start();
    }
    Some((unescape(&line[..key_end]), unescape(rest)))
}

/// Mirrors GameSettings.setUsername.
pub fn sanitize_username(raw: &str) -> String {
    let kept: String = raw.chars().filter(|&c| c as u32 >= 32 && c as u32 != 127).collect();
    let mut out = String::new();
    let mut units = 0;
    for c in kept.trim().chars() {
        units += c.len_utf16();
        if units > MAX_USERNAME_LEN {
            break;
        }
        out.push(c);
    }
    let out = out.trim().to_string();
    if out.is_empty() {
        DEFAULT_USERNAME.to_string()
    } else {
        out
    }
}

pub fn read_username() -> Option<String> {
    let text = read_latin1(&options_path()?)?;
    let mut found = None;
    for line in text.lines() {
        if let Some((key, value)) = parse_property(line) {
            if key == "username" {
                found = Some(value);
            }
        }
    }
    found
}

pub fn write_username(name: &str) -> Res<()> {
    let path = options_path().ok_or("no data folder")?;
    fs::create_dir_all(path.parent().ok_or("bad options path")?)?;

    let existing = read_latin1(&path).unwrap_or_default();
    let new_line = format!("username={}", escape_value(name));

    let mut lines: Vec<String> = Vec::new();
    let mut replaced = false;
    for line in existing.lines() {
        if matches!(parse_property(line), Some((key, _)) if key == "username") {
            if !replaced {
                lines.push(new_line.clone());
                replaced = true;
            }
        } else {
            lines.push(line.to_string());
        }
    }
    if !replaced {
        lines.push(new_line);
    }

    let mut text = lines.join("\n");
    text.push('\n');
    let bytes: Vec<u8> = text.chars().map(|c| c as u32 as u8).collect();

    let tmp = path.with_extension("txt.tmp");
    fs::write(&tmp, &bytes)?;
    fs::rename(&tmp, &path)?;
    Ok(())
}
