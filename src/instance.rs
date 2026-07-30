// src/instance.rs
use anyhow::Result;
use std::path::PathBuf;
use tokio::time::{sleep, Duration};

pub struct Instance {
    pub path: PathBuf,
    pub mods_dir: PathBuf,
    pub config_dir: PathBuf,
}

pub struct InstanceManager {
    base_dir: PathBuf,
}

fn normalize(s: &str) -> String {
    s.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect()
}

fn matches(name: &str, slug: &str, title: &str) -> bool {
    let n = normalize(name);
    let sl = normalize(slug);
    let ti = normalize(title);
    name == title
        || n == ti
        || n == sl
        || (!sl.is_empty() && n.contains(&sl))
        || (!ti.is_empty() && n.contains(&ti))
}

impl InstanceManager {
    pub fn new() -> Result<Self> {
        // Modrinth App хранит профили в папке `profiles` (НЕ `instances`)
        let base = if cfg!(windows) {
            dirs::data_dir().unwrap().join("ModrinthApp/profiles")
        } else {
            dirs::home_dir().unwrap().join(".local/share/ModrinthApp/profiles")
        };
        Ok(Self { base_dir: base })
    }

    pub async fn wait_for_instance(&self, slug: &str, title: &str) -> Result<Instance> {
        for _ in 0..120 {
            if let Ok(entries) = std::fs::read_dir(&self.base_dir) {
                for entry in entries.flatten() {
                    let name = entry.file_name().to_string_lossy().to_string();
                    if matches(&name, slug, title) {
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
        anyhow::bail!("Instance not found for slug={} title={}", slug, title)
    }
}