// src/modrinth.rs
use anyhow::Result;
use rand::Rng;
use reqwest::Client;
use serde::Deserialize;

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

#[derive(Deserialize)]
struct SearchResponse { hits: Vec<Modpack> }

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