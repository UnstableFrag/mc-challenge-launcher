// src/cleanup.rs
use anyhow::Result;
use std::fs;

fn normalize(s: &str) -> String {
    s.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect()
}

fn matches(name: &str, slug: &str, title: &str) -> bool {
    let n = normalize(name);
    let sl = normalize(slug);
    let ti = normalize(title);
    name == title || n == ti || n == sl
        || (!sl.is_empty() && n.contains(&sl))
        || (!ti.is_empty() && n.contains(&ti))
}

pub async fn clean_instance(slug: &str, title: &str) -> Result<()> {
    let base = if cfg!(windows) {
        dirs::data_dir().unwrap().join("ModrinthApp/profiles")
    } else {
        dirs::home_dir().unwrap().join(".local/share/ModrinthApp/profiles")
    };
    if let Ok(entries) = fs::read_dir(&base) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if matches(&name, slug, title) {
                fs::remove_dir_all(entry.path())?;
                break;
            }
        }
    }
    Ok(())
}