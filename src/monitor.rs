use anyhow::Result;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize, Debug)]
pub struct RunResult {
    pub item: String,
    pub player: String,
    pub time_ticks: u64,
}

pub struct Monitor {
    result_path: Option<PathBuf>,
}

impl Monitor {
    pub fn new() -> Self { Self { result_path: None } }

    pub fn start(&mut self, instance_path: PathBuf) {
        self.result_path = Some(instance_path.join("logs/challenge_result.json"));
    }

    pub fn check_result(&self) -> Result<Option<RunResult>> {
        if let Some(path) = &self.result_path {
            if path.exists() {
                let content = std::fs::read_to_string(path)?;
                let res: RunResult = serde_json::from_str(&content)?;
                std::fs::remove_file(path).ok();
                return Ok(Some(res));
            }
        }
        Ok(None)
    }
}