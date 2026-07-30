// src/instance.rs
use anyhow::Result;
use dirs;
use std::path::{Path, PathBuf};
use tokio::time::{sleep, Duration};

pub struct Instance {
    pub path: PathBuf,
    pub mods_dir: PathBuf,
    pub config_dir: PathBuf,
}

pub struct InstanceManager {
    base_dir: PathBuf,
}

impl InstanceManager {
    pub fn new() -> Result<Self> {
        let base = if cfg!(windows) {
            dirs::data_dir().unwrap().join("ModrinthApp/instances")
        } else {
            dirs::home_dir().unwrap().join(".local/share/ModrinthApp/instances")
        };
        Ok(Self { base_dir: base })
    }
    
    pub async fn wait_for_instance(&self, slug: &str) -> Result<Instance> {
        for _ in 0..120 {
            if let Ok(entries) = std::fs::read_dir(&self.base_dir) {
                for entry in entries.flatten() {
                    let name = entry.file_name().to_string_lossy().to_string();
                    if name.contains(slug) || name.contains(&slug[..slug.len().min(8)]) {
                        let path = entry.path();
                        return Ok(Instance {
                            mods_dir: path.join("mods"),
                            config_dir: path.join("config"),
                            path,
                        });
                    }
                }
            }
            sleep(Duration::from_secs(1)).await;
        }
        anyhow::bail!("Instance not found for slug: {}", slug)
    }
}