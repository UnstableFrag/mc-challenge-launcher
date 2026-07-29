use anyhow::Result;
use rand::seq::SliceRandom;
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Serialize, Deserialize, Debug)]
pub struct ChallengeConfig {
    pub target: String,
    pub pool: Vec<String>,
    pub version: u32,
}

impl ChallengeConfig {
    pub fn new(target: String) -> Self {
        Self {
            target,
            pool: ItemPool::default().items,
            version: 1,
        }
    }
    
    pub fn write_to(&self, config_dir: &Path) -> Result<()> {
        std::fs::create_dir_all(config_dir)?;
        let path = config_dir.join("challenge.json");
        std::fs::write(path, serde_json::to_string_pretty(self)?)?;
        Ok(())
    }
}

#[derive(Default)]
pub struct ItemPool {
    pub items: Vec<String>,
}

impl ItemPool {
    pub fn random(&self) -> String {
        self.items.choose(&mut rand::thread_rng()).unwrap().clone()
    }
}

impl Default for ItemPool {
    fn default() -> Self {
        Self {
            items: vec![
                "minecraft:diamond".into(),
                "minecraft:netherite_ingot".into(),
                "minecraft:elytra".into(),
                "minecraft:enchanted_golden_apple".into(),
                "minecraft:beacon".into(),
                "minecraft:totem_of_undying".into(),
                "minecraft:nether_star".into(),
                "minecraft:dragon_egg".into(),
                "#minecraft:tools".into(),
                "#minecraft:armor".into(),
                "#forge:ores/diamond".into(),
            ]
        }
    }
}