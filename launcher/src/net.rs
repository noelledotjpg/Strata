use crate::config::{Res, PLATFORM, REPO};
use crate::task::Reporter;
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;

#[derive(Clone)]
pub struct Release {
    pub tag: String,
    pub url: String,
    pub size: u64,
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .user_agent("strata-launcher")
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(30))
        .build()
}

/// GitHub's /latest skips drafts and pre-releases.
pub fn fetch_latest() -> Result<Release, String> {
    let url = format!("https://api.github.com/repos/{REPO}/releases/latest");
    let resp = agent()
        .get(&url)
        .set("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| format!("Could not reach GitHub: {e}"))?;
    let json: serde_json::Value = resp.into_json().map_err(|e| format!("Bad response from GitHub: {e}"))?;

    let tag = json["tag_name"].as_str().ok_or("The release has no tag")?.to_string();
    let suffix = format!("-{PLATFORM}-x64-portable.zip");
    let asset = json["assets"]
        .as_array()
        .and_then(|list| list.iter().find(|a| a["name"].as_str().map_or(false, |n| n.ends_with(&suffix))))
        .ok_or_else(|| format!("Release {tag} has no {PLATFORM} download"))?;

    Ok(Release {
        tag,
        url: asset["browser_download_url"].as_str().ok_or("Asset has no download link")?.to_string(),
        size: asset["size"].as_u64().unwrap_or(0),
    })
}

pub fn download(rep: &Reporter, url: &str, dest: &Path, size_hint: u64) -> Res<()> {
    let resp = agent().get(url).call().map_err(|e| format!("Download failed: {e}"))?;
    let total = resp
        .header("Content-Length")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(size_hint);

    let mut reader = resp.into_reader();
    let mut out = File::create(dest)?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = 0u64;
    loop {
        let n = reader.read(&mut buf).map_err(|e| format!("Download interrupted: {e}"))?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        done += n as u64;
        rep.progress("Downloading", done, total);
    }
    out.flush()?;
    Ok(())
}
