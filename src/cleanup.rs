// src/cleanup.rs
use anyhow::Result;
use dirs;
use std::fs;

pub async fn clean_instance(slug: &str) -> Result<()> {
    let base = if cfg!(windows) {
        dirs::data_dir().unwrap().join("ModrinthApp/instances")
    } else {
        dirs::home_dir().unwrap().join(".local/share/ModrinthApp/instances")
    };
    
    if let Ok(entries) = fs::read_dir(&base) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.contains(slug) {
                fs::remove_dir_all(entry.path())?;
                break;
            }
        }
    }
    Ok(())
}