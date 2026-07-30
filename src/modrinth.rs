use anyhow::{bail, Result};
use rand::Rng;
use reqwest::Client;
use serde::Deserialize;

const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";

#[derive(Debug, Clone)]
pub struct Modpack {
    pub slug: String,
    pub title: String,
    pub author: String,
    pub versions: Vec<String>,
    pub categories: Vec<String>,
    pub description: Option<String>,
}

pub struct ModrinthApi {
    client: Client,
}

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
    pub fn new() -> Self {
        Self { client: Client::new() }
    }

    pub fn client(&self) -> &Client {
        &self.client
    }

    pub async fn random_modpack(&self) -> Result<Modpack> {
        self.random_from_index().await
    }

    pub async fn modpack_by_slug(&self, slug: &str) -> Result<Modpack> {
        let resp = self.client
            .get(format!("https://api.modrinth.com/v2/project/{}", slug))
            .header("User-Agent", UA)
            .send().await?
            .json::<serde_json::Value>().await?;

        Ok(Modpack {
            slug: resp["slug"].as_str().unwrap_or(slug).to_string(),
            title: resp["title"].as_str().unwrap_or(slug).to_string(),
            author: String::new(),
            versions: resp["game_versions"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            categories: resp["categories"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            description: resp["description"].as_str().map(String::from),
        })
    }

    async fn random_from_index(&self) -> Result<Modpack> {
        for _ in 0..5 {
            let page = rand::thread_rng().gen_range(1..=5000);
            let url = format!(
                "https://www.modpackindex.com/api/v1/modpacks?limit=100&page={}",
                page
            );

            let resp = self.client
                .get(&url)
                .header("User-Agent", UA)
                .send().await?;

            let status = resp.status();
            if !status.is_success() {
                let body = resp.text().await.unwrap_or_default();
                bail!("Modpack Index returned {}: {}", status, body.chars().take(200).collect::<String>());
            }

            let body = resp.text().await?;
            let index_resp: ModpackIndexResponse = serde_json::from_str(&body)?;

            let mut rng = rand::thread_rng();
            let packs: Vec<_> = index_resp.data.into_iter()
                .filter(|p| p.modrinth_info.is_some())
                .collect();

            if !packs.is_empty() {
                let idx = rng.gen_range(0..packs.len());
                let pack = &packs[idx];
                let info = pack.modrinth_info.as_ref().unwrap();

                return Ok(Modpack {
                    slug: if !info.slug.is_empty() { info.slug.clone() } else { pack.slug.clone() },
                    title: if !info.title.is_empty() { info.title.clone() } else { pack.name.clone() },
                    author: String::new(),
                    versions: vec![],
                    categories: info.categories.clone(),
                    description: Some(pack.summary.clone()),
                });
            }
        }
        self.random_from_fallback().await
    }

    async fn random_from_fallback(&self) -> Result<Modpack> {
        let slug = FALLBACK_MODPACKS[rand::thread_rng().gen_range(0..FALLBACK_MODPACKS.len())];
        self.modpack_by_slug(slug).await
    }
}

#[derive(Deserialize)]
struct ModpackIndexResponse {
    data: Vec<ModpackIndexItem>,
}

#[derive(Deserialize)]
struct ModpackIndexItem {
    name: String,
    slug: String,
    summary: String,
    modrinth_info: Option<ModrinthInfo>,
}

#[derive(Deserialize)]
struct ModrinthInfo {
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    categories: Vec<String>,
}
