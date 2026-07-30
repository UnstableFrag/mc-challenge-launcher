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
    pub versions: Vec<String>,
    pub categories: Vec<String>,
    pub downloads: u64,
    pub follows: u64,
    pub description: Option<String>,
}

#[derive(Deserialize)]
struct SearchResponse { hits: Vec<Modpack> }

pub struct ModrinthApi { client: Client }

impl ModrinthApi {
    pub fn new() -> Self { Self { client: Client::new() } }
    
    pub async fn random_modpack(&self) -> Result<Modpack> {
        let page = rand::thread_rng().gen_range(0..12);
        let offset = page * 100 + rand::thread_rng().gen_range(0..100);
        
        let resp = self.client
            .get("https://api.modrinth.com/v2/search")
            .query(&[
                ("facets", "[[\"project_type:modpack\"]]"),
                ("limit", "1"),
                ("offset", &offset.to_string()),
                ("index", "relevance"),
            ])
            .header("User-Agent", "mc-challenge-launcher/0.1")
            .send().await?
            .json::<SearchResponse>().await?;
        
        resp.hits.into_iter().next().ok_or_else(|| anyhow::anyhow!("No modpack found"))
    }
}