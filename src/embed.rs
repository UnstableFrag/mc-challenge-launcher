// src/embed.rs
use include_dir::{include_dir, Dir};

pub const MOD_JAR_DIR: Dir<'static> = include_dir!("$CARGO_MANIFEST_DIR/assets/challenge-mod");

/// Версии MC, для которых встроены сборки challenge-hud-{version}.jar
pub const SUPPORTED_VERSIONS: &[&str] = &[
    "1.20.1", "1.20.2", "1.20.4", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
    "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9",
    "1.21.10", "1.21.11",
];

pub fn jar_name(version: &str) -> String {
    format!("challenge-hud-{}.jar", version)
}
