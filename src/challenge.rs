// src/challenge.rs
use anyhow::Result;
use rand::seq::SliceRandom;
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ChallengeConfig {
    pub target: String,
    pub pool: Vec<String>,
    pub version: u32,
    #[serde(default)]
    pub challenge_type: String,
    #[serde(rename = "deadline", default)]
    pub deadline_ticks: u64,
}

impl ChallengeConfig {
    pub fn new(target: String, pool: Vec<String>) -> Self {
        Self {
            target,
            pool,
            version: 1,
            challenge_type: "ITEM".to_string(),
            deadline_ticks: 6000, // 5 minutes default
        }
    }
    pub fn write_to(&self, config_dir: &Path) -> Result<()> {
        std::fs::create_dir_all(config_dir)?;
        let path = config_dir.join("challenge.json");
        std::fs::write(path, serde_json::to_string_pretty(self)?)?;
        Ok(())
    }
}

pub struct ItemPool {
    pub items: Vec<String>,
}

impl ItemPool {
    pub fn random(&self) -> String {
        self.items.choose(&mut rand::thread_rng()).unwrap().clone()
    }
    pub fn has_tag_targets(&self) -> bool {
        self.items.iter().any(|i| i.starts_with('#'))
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
                "#minecraft:pickaxes".into(),
                "#minecraft:swords".into(),
                "#minecraft:bows".into(),
                "#minecraft:foods".into(),
                "minecraft:diamond_sword".into(),
                "minecraft:diamond_pickaxe".into(),
                "minecraft:diamond_armor".into(),
                "minecraft:netherite_sword".into(),
                "minecraft:netherite_pickaxe".into(),
                "#minecraft:axes".into(),
                "#minecraft:hoes".into(),
                "#minecraft:shovels".into(),
            ],
        }
    }
}

impl ItemPool {
    pub fn with_mod_items(mut self, mod_items: Vec<String>) -> Self {
        self.items.extend(mod_items);
        self.items.sort();
        self.items.dedup();
        self
    }
}