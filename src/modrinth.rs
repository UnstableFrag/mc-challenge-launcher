use anyhow::{bail, Result};
use rand::Rng;
use reqwest::Client;
use serde::Deserialize;

use crate::embed::{self, Loader, Target};

const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";

#[derive(Debug, Clone)]
pub struct Modpack {
    pub slug: String,
    pub title: String,
    pub author: String,
    pub versions: Vec<String>,
    pub loaders: Vec<String>,
    pub categories: Vec<String>,
    pub description: Option<String>,
}

impl Modpack {
    /// Основной загрузчик модпака (первый из fabric/quilt/forge/neoforge) или None.
    pub fn primary_loader(&self) -> Option<Loader> {
        embed::primary_loader(&self.loaders)
    }

    /// Лучшая поддерживаемая пара (MC-версия, загрузчик) или None.
    pub fn pick_target(&self) -> Option<Target> {
        let loader = self.primary_loader()?;
        self.versions.iter()
            .filter(|pv| embed::is_supported(pv, loader))
            .max_by(|a, b| version_cmp(a, b))
            .map(|v| Target { version: v.clone(), loader })
    }

    pub fn versions_str(&self) -> String {
        self.versions.join(", ")
    }

    pub fn loaders_str(&self) -> String {
        if self.loaders.is_empty() {
            "<none>".to_string()
        } else {
            self.loaders.join(", ")
        }
    }
}

fn version_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    let parse = |s: &str| -> Vec<u32> {
        s.split('.').filter_map(|p| p.parse::<u32>().ok()).collect()
    };
    let va = parse(a);
    let vb = parse(b);
    for i in 0..va.len().max(vb.len()) {
        let x = va.get(i).copied().unwrap_or(0);
        let y = vb.get(i).copied().unwrap_or(0);
        if x != y { return x.cmp(&y); }
    }
    std::cmp::Ordering::Equal
}

pub struct ModrinthApi {
    client: Client,
}

const FALLBACK_MODPACKS: &[&str] = &[
    "allthemodium",
    "enigmatica-9",
    "create",
    "prominence-ii-rpg",
    "dawncraft",
    "cabin-in-the-woods",
    "meatballcraft",
    "sci-fi-craft",
    "dungeons-and-taverns",
    "disney-lands",
];

impl ModrinthApi {
    pub fn new() -> Self {
        Self { client: Client::new() }
    }

    pub fn client(&self) -> &Client {
        &self.client
    }

    pub async fn random_modpack(&self) -> Result<Modpack> {
        for _ in 0..5 {
            if let Ok(m) = self.random_from_index().await {
                return Ok(m);
            }
        }
        self.random_from_search().await
    }

    async fn project_json(&self, slug: &str) -> Result<serde_json::Value> {
        let resp = self.client
            .get(format!("https://api.modrinth.com/v2/project/{}", slug))
            .header("User-Agent", UA)
            .send().await?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            bail!("Modrinth project {} returned {}: {}", slug, status, body.chars().take(200).collect::<String>());
        }
        Ok(resp.json::<serde_json::Value>().await?)
    }

    fn loaders_from_json(json: &serde_json::Value) -> Vec<String> {
        json["loaders"].as_array()
            .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
            .unwrap_or_default()
    }

    /// Есть ли встроенный jar для (версия, основной загрузчик) модпака.
    fn has_supported_target(json: &serde_json::Value) -> bool {
        let Some(loader) = embed::primary_loader(&Self::loaders_from_json(json)) else {
            return false;
        };
        json["game_versions"].as_array()
            .map(|a| a.iter().any(|v| embed::is_supported(v.as_str().unwrap_or(""), loader)))
            .unwrap_or(false)
    }

    fn author_of(json: &serde_json::Value) -> String {
        json["team"].as_array()
            .and_then(|t| t.first())
            .and_then(|m| m["user"]["username"].as_str())
            .unwrap_or("")
            .to_string()
    }

    pub async fn modpack_by_slug(&self, slug: &str) -> Result<Modpack> {
        let json = self.project_json(slug).await?;
        Ok(Modpack {
            slug: json["slug"].as_str().unwrap_or(slug).to_string(),
            title: json["title"].as_str().unwrap_or(slug).to_string(),
            author: Self::author_of(&json),
            versions: json["game_versions"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            loaders: Self::loaders_from_json(&json),
            categories: json["categories"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            description: json["description"].as_str().map(String::from),
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

                if let Ok(json) = self.project_json(&info.slug).await {
                    if !Self::has_supported_target(&json) {
                        continue;
                    }
                    return Ok(Modpack {
                        slug: info.slug.clone(),
                        title: json["title"].as_str().unwrap_or(&info.title).to_string(),
                        author: Self::author_of(&json),
                        versions: json["game_versions"].as_array()
                            .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                            .unwrap_or_default(),
                        loaders: Self::loaders_from_json(&json),
                        categories: json["categories"].as_array()
                            .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                            .unwrap_or_default(),
                        description: Some(pack.summary.clone()),
                    });
                }
            }
        }
        // 5 страниц не дали результата — расширяем диапазон страниц
        bail!("no compatible modpack found on Modpack Index")
    }

    async fn random_from_search(&self) -> Result<Modpack> {
        let facets = r#"[["project_type:modpack"]]"#;
        for _ in 0..10 {
            let offset = rand::thread_rng().gen_range(0..500);
            let offset_str = offset.to_string();
            let resp = self.client
                .get("https://api.modrinth.com/v2/search")
                .header("User-Agent", UA)
                .query(&[("facets", facets), ("limit", "100"), ("offset", offset_str.as_str())])
                .send().await?;

            let status = resp.status();
            if !status.is_success() {
                let body = resp.text().await.unwrap_or_default();
                bail!("Modrinth search returned {}: {}", status, body.chars().take(200).collect::<String>());
            }

            let body = resp.text().await?;
            let search: ModrinthSearch = serde_json::from_str(&body)?;

            if !search.hits.is_empty() {
                let hit = &search.hits[rand::thread_rng().gen_range(0..search.hits.len())];
                if let Ok(json) = self.project_json(&hit.slug).await {
                    if Self::has_supported_target(&json) {
                        // Приоритет — loaders из поискового хита (свежее),
                        // иначе из project JSON.
                        let loaders = if hit.loaders.is_empty() {
                            Self::loaders_from_json(&json)
                        } else {
                            hit.loaders.clone()
                        };
                        return Ok(Modpack {
                            slug: hit.slug.clone(),
                            title: hit.title.clone(),
                            author: Self::author_of(&json),
                            versions: json["game_versions"].as_array()
                                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                                .unwrap_or_default(),
                            loaders,
                            categories: hit.categories.clone(),
                            description: hit.description.clone(),
                        });
                    }
                }
            }
        }
        for _ in 0..10 {
            let slug = FALLBACK_MODPACKS[rand::thread_rng().gen_range(0..FALLBACK_MODPACKS.len())];
            if let Ok(pack) = self.modpack_by_slug(slug).await {
                if pack.pick_target().is_some() {
                    return Ok(pack);
                }
            }
        }
        bail!("no compatible modpack found (all fallback slugs unsupported)")
    }
}

#[derive(Deserialize)]
struct ModpackIndexResponse {
    data: Vec<ModpackIndexItem>,
}

#[derive(Deserialize)]
struct ModpackIndexItem {
    summary: String,
    modrinth_info: Option<ModrinthInfo>,
}

#[derive(Deserialize)]
struct ModrinthInfo {
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
}

#[derive(Deserialize)]
struct ModrinthSearch {
    hits: Vec<ModrinthSearchHit>,
}

#[derive(Deserialize)]
struct ModrinthSearchHit {
    slug: String,
    title: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
}
