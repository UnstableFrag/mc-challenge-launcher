// src/instance.rs
use anyhow::Result;
use std::io::Read;
use std::path::PathBuf;
use tokio::time::{sleep, Duration};
use zip::ZipArchive;
use std::io::Cursor;
use std::fs;
use std::process::Command;

use crate::embed;

pub struct Instance {
    pub path: PathBuf,
    pub mods_dir: PathBuf,
    pub config_dir: PathBuf,
}

pub struct InstanceManager {
    base_dir: PathBuf,
    work_dir: PathBuf,
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
        let work = dirs::data_dir().unwrap().join("mc-challenge-launcher/instances");
        fs::create_dir_all(&work)?;
        Ok(Self { base_dir: base, work_dir: work })
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

    pub async fn create_instance_from_modpack(&self, slug: &str, client: &reqwest::Client, preferred: Option<&embed::Target>) -> Result<Instance> {
        let version_url = format!("https://api.modrinth.com/v2/project/{}/version", slug);
        let versions = client
            .get(&version_url)
            .header("User-Agent", "mc-challenge-launcher/0.3")
            .send().await?
            .json::<serde_json::Value>().await?;

        let mut candidates: Vec<&serde_json::Value> = versions.as_array()
            .map(|v| v.iter().filter(|ver| {
                ver["files"].as_array().map(|f| {
                    f.iter().any(|file| file["filename"].as_str().map(|n| n.ends_with(".mrpack")).unwrap_or(false))
                }).unwrap_or(false)
            }).collect())
            .unwrap_or_default();

        // Сначала версии, совпадающие с нужной MC-версией И загрузчиком модпака.
        // Загрузчики не взаимозаменяемы: никогда не берём .mrpack другого загрузчика.
        if let Some(pref) = preferred {
            let matches_loader = |ver: &serde_json::Value| {
                ver["loaders"].as_array()
                    .map(|ls| ls.iter().any(|l| {
                        l.as_str().map(|s| pref.loader.api_names().iter().any(|n| s.eq_ignore_ascii_case(n))).unwrap_or(false)
                    }))
                    .unwrap_or(false)
            };
            let exact = candidates.iter().position(|ver| {
                ver["game_versions"].as_array()
                    .map(|gv| gv.iter().any(|v| v.as_str() == Some(pref.version.as_str())))
                    .unwrap_or(false)
                    && matches_loader(ver)
            });
            if let Some(pos) = exact {
                candidates.swap(0, pos);
            } else if let Some(pos) = candidates.iter().position(|ver| matches_loader(ver)) {
                // Та же семья загрузчика, но MC-версия размечена иначе — берём её.
                candidates.swap(0, pos);
            } else {
                anyhow::bail!(
                    "No .mrpack release of {} tagged with loader {} (MC {}); refusing to mix loaders",
                    slug,
                    pref.loader.display_name(),
                    pref.version
                );
            }
        }

        let ver = candidates.first()
            .ok_or_else(|| anyhow::anyhow!("No .mrpack file found for {}", slug))?;

        let mrpack_url = ver["files"].as_array()
            .and_then(|f| f.iter().find(|file| file["filename"].as_str().map(|n| n.ends_with(".mrpack")).unwrap_or(false)))
            .and_then(|f| f["url"].as_str())
            .ok_or_else(|| anyhow::anyhow!("No .mrpack file found for {}", slug))?;

        let instance_dir = self.work_dir.join(slug);
        fs::create_dir_all(&instance_dir)?;

        let mrpack_data = client
            .get(mrpack_url)
            .header("User-Agent", "mc-challenge-launcher/0.3")
            .send().await?
            .bytes().await?;

        let mut archive = ZipArchive::new(Cursor::new(mrpack_data))?;
        
        let mut index_json = String::new();
        archive.by_name("index.json")?.read_to_string(&mut index_json)?;
        let index: serde_json::Value = serde_json::from_str(&index_json)?;

        let files = index["files"].as_object().ok_or_else(|| anyhow::anyhow!("No files in index"))?;
        
        let mods_dir = instance_dir.join("mods");
        let config_dir = instance_dir.join("config");
        fs::create_dir_all(&mods_dir)?;
        fs::create_dir_all(&config_dir)?;

        for (path, _file_info) in files {
            let dest = if path.starts_with("mods/") {
                mods_dir.join(path.strip_prefix("mods/").unwrap())
            } else if path.starts_with("config/") || path.starts_with("overrides/") {
                let stripped = path.strip_prefix("overrides/").unwrap_or(&path);
                config_dir.join(stripped)
            } else {
                instance_dir.join(path)
            };

            if let Some(parent) = dest.parent() {
                fs::create_dir_all(parent)?;
            }

            let mut file = archive.by_name(path)?;
            let mut dest_file = fs::File::create(&dest)?;
            std::io::copy(&mut file, &mut dest_file)?;
        }

        Ok(Instance {
            mods_dir,
            config_dir,
            path: instance_dir,
        })
    }

    pub fn launch_minecraft(&self, instance: &Instance, java_path: Option<&str>) -> Result<std::process::Child> {
        let java = java_path.unwrap_or("java");
        let mut cmd = Command::new(java);
        cmd.arg("-jar")
            .arg(instance.path.join("fabric-launcher.jar").to_string_lossy().to_string())
            .current_dir(&instance.path);
        Ok(cmd.spawn()?)
    }
}