// src/embed.rs
use include_dir::{include_dir, Dir};

pub const MOD_JAR_DIR: Dir<'static> = include_dir!("$CARGO_MANIFEST_DIR/assets/challenge-mod");
pub const MOD_JAR_NAME: &str = "challenge-mod.jar";