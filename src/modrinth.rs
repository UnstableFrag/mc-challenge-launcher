// src/modrinth.rs
use anyhow::Result;
use rand::Rng;
use reqwest::Client;
use serde::Deserialize;
use std::path::Path;

#[derive(Deserialize, Debug, Clone)]
pub struct Modpack {
    pub slug: String,
    pub title: String,
    pub author: String,
    #[serde(default)]
    pub versions: Vec<String>,
    #[serde(default)]
    pub categories: Vec<String>,
    pub downloads: u64,
    pub follows: u64,
    pub description: Option<String>,
}

#[derive(Deserialize, Debug)]
struct SearchResponse { hits: Vec<Modpack> }

#[derive(Deserialize, Debug, Clone)]
pub struct ModpackVersion {
    pub id: String,
    pub version_number: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub files: Vec<ModpackFile>,
    pub dependencies: Vec<ModpackDependency>,
}

#[derive(Deserialize, Debug, Clone)]
pub struct ModpackFile {
    pub hashes: std::collections::HashMap<String, String>,
    pub url: String,
    pub filename: String,
    pub primary: bool,
    pub size: u64,
    pub file_type: String,
}

#[derive(Deserialize, Debug, Clone)]
struct ModpackDependency {
    version_id: Option<String>,
    project_id: Option<String>,
    file_name: Option<String>,
    dependency_type: String,
}

pub struct ModrinthApi { client: Client }

const FALLBACK_MODPACKS: &[&str] = &[
    "create",
    "skyfactory-5",
    "allthemodium",
    "dungeons-monsters-and-arsenic",
    "ftbquests",
    "enigmatica-6",
    "dragnlo-eternity",
    "all-the-mod-7",
    "farming-for-blockheads",
    "thermal-series",
];

impl ModrinthApi {
    pub fn new() -> Self { Self { client: Client::new() } }

    pub fn client(&self) -> &Client {
        &self.client
    }

    pub async fn random_modpack(&self) -> Result<Modpack> {
        self.random_modpack_from_index().await
    }

    pub async fn modpack_by_slug(&self, slug: &str) -> Result<Modpack> {
        let resp = self.client
            .get(format!("https://api.modrinth.com/v2/project/{}", slug))
            .header("User-Agent", "mc-challenge-launcher/0.2")
            .send().await?
            .json::<Modpack>().await?;
        Ok(resp)
    }

    pub async fn get_latest_version(&self, slug: &str) -> Result<ModpackVersion> {
        let resp = self.client
            .get(format!("https://api.modrinth.com/v2/project/{}/version", slug))
            .query(&[("loaders", "[\"fabric\",\"forge\",\"neoforge\",\"quilt\"]")])
            .header("User-Agent", "mc-challenge-launcher/0.2")
            .send().await?
            .json::<Vec<ModpackVersion>>().await?;
        resp.into_iter().next().ok_or_else(|| anyhow::anyhow!("No compatible version found"))
    }

    pub async fn download_mrpack(&self, version: &ModpackVersion, dest: &Path) -> Result<()> {
        let file = version.files.iter()
            .find(|f| f.primary && f.filename.ends_with(".mrpack"))
            .or_else(|| version.files.iter().find(|f| f.filename.ends_with(".mrpack")))
            .ok_or_else(|| anyhow::anyhow!("No .mrpack file in version"))?;

        let resp = self.client.get(&file.url).send().await?;
        let bytes = resp.bytes().await?;
        std::fs::write(dest, bytes)?;
        Ok(())
    }

    async fn random_modpack_from_index(&self) -> Result<Modpack> {
        let page = rand::thread_rng().gen_range(0..50);
        let offset = page * 100;

        let resp = self.client
            .get("https://api.modrinth.com/v2/search")
            .query(&[
                ("facets", "[[\"project_type:modpack\"],[\"follows:>100\"]]"),
                ("limit", "1"),
                ("offset", &offset.to_string()),
                ("index", "follows"),
            ])
            .header("User-Agent", "mc-challenge-launcher/0.2")
            .send().await?
            .json::<SearchResponse>().await?;

        if let Some(pack) = resp.hits.into_iter().next() {
            return Ok(pack);
        }

        self.random_modpack_from_fallback().await
    }

    async fn random_modpack_from_fallback(&self) -> Result<Modpack> {
        let mut rng = rand::thread_rng();
        let slug = FALLBACK_MODPACKS[rng.gen_range(0..FALLBACK_MODPACKS.len())];
        self.modpack_by_slug(slug).await
    }
}