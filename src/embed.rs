// src/embed.rs
use include_dir::{include_dir, Dir};

// Манифест встроенных jar-файлов — 46 файлов по контракту нейминга:
//
//   Fabric (22):    challenge-hud-{mc}.jar
//     1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4,
//     1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21, 1.21.1, 1.21.2, 1.21.3,
//     1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
//   Forge (9):      challenge-hud-{mc}-forge.jar
//     1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4,
//     1.20.1, 1.20.2, 1.20.4
//   NeoForge (15):  challenge-hud-{mc}-neoforge.jar
//     1.20.2, 1.20.4, 1.20.6, 1.21, 1.21.1, 1.21.2, 1.21.3,
//     1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
//
// Полные имена файлов (46):
//   challenge-hud-1.16.5.jar,      challenge-hud-1.17.1.jar,
//   challenge-hud-1.18.2.jar,      challenge-hud-1.19.2.jar,
//   challenge-hud-1.19.3.jar,      challenge-hud-1.19.4.jar,
//   challenge-hud-1.20.1.jar,      challenge-hud-1.20.2.jar,
//   challenge-hud-1.20.4.jar,      challenge-hud-1.20.6.jar,
//   challenge-hud-1.21.jar,        challenge-hud-1.21.1.jar,
//   challenge-hud-1.21.2.jar,      challenge-hud-1.21.3.jar,
//   challenge-hud-1.21.4.jar,      challenge-hud-1.21.5.jar,
//   challenge-hud-1.21.6.jar,      challenge-hud-1.21.7.jar,
//   challenge-hud-1.21.8.jar,      challenge-hud-1.21.9.jar,
//   challenge-hud-1.21.10.jar,     challenge-hud-1.21.11.jar,
//   challenge-hud-1.16.5-forge.jar,   challenge-hud-1.17.1-forge.jar,
//   challenge-hud-1.18.2-forge.jar,   challenge-hud-1.19.2-forge.jar,
//   challenge-hud-1.19.3-forge.jar,   challenge-hud-1.19.4-forge.jar,
//   challenge-hud-1.20.1-forge.jar,   challenge-hud-1.20.2-forge.jar,
//   challenge-hud-1.20.4-forge.jar,
//   challenge-hud-1.20.2-neoforge.jar, challenge-hud-1.20.4-neoforge.jar,
//   challenge-hud-1.20.6-neoforge.jar, challenge-hud-1.21-neoforge.jar,
//   challenge-hud-1.21.1-neoforge.jar, challenge-hud-1.21.2-neoforge.jar,
//   challenge-hud-1.21.3-neoforge.jar, challenge-hud-1.21.4-neoforge.jar,
//   challenge-hud-1.21.5-neoforge.jar, challenge-hud-1.21.6-neoforge.jar,
//   challenge-hud-1.21.7-neoforge.jar, challenge-hud-1.21.8-neoforge.jar,
//   challenge-hud-1.21.9-neoforge.jar, challenge-hud-1.21.10-neoforge.jar,
//   challenge-hud-1.21.11-neoforge.jar

pub const MOD_JAR_DIR: Dir<'static> = include_dir!("$CARGO_MANIFEST_DIR/assets/challenge-mod");

/// Загрузчик модпака. Quilt разделяет jar с Fabric (Quilt Loader запускает
/// fabric.mod.json моды).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Loader {
    Fabric,
    Quilt,
    Forge,
    NeoForge,
}

/// Пара (версия MC, загрузчик), для которой выбран jar.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    pub version: String,
    pub loader: Loader,
}

impl Loader {
    /// Имена загрузчиков в API Modrinth, совместимые с jar этого загрузчика
    /// (для поиска подходящего .mrpack релиза).
    pub fn api_names(self) -> &'static [&'static str] {
        match self.jar_loader() {
            Loader::Fabric => &["fabric", "quilt"],
            Loader::Forge => &["forge"],
            Loader::NeoForge => &["neoforge"],
            Loader::Quilt => unreachable!(),
        }
    }

    /// Семейство jar: Quilt использует Fabric jar.
    pub fn jar_loader(self) -> Loader {
        match self {
            Loader::Quilt => Loader::Fabric,
            other => other,
        }
    }

    pub fn display_name(self) -> &'static str {
        match self {
            Loader::Fabric => "Fabric",
            Loader::Quilt => "Quilt (via Fabric)",
            Loader::Forge => "Forge",
            Loader::NeoForge => "NeoForge",
        }
    }
}

/// Версии MC с Fabric jar (Quilt использует те же jar).
pub const FABRIC_VERSIONS: &[&str] = &[
    "1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.19.4",
    "1.20.1", "1.20.2", "1.20.4", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
    "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9",
    "1.21.10", "1.21.11",
];

/// Версии MC с Forge jar.
pub const FORGE_VERSIONS: &[&str] = &[
    "1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.19.4",
    "1.20.1", "1.20.2", "1.20.4",
];

/// Версии MC с NeoForge jar (все, кроме 1.20.1).
pub const NEOFORGE_VERSIONS: &[&str] = &[
    "1.20.2", "1.20.4", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
    "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9",
    "1.21.10", "1.21.11",
];

/// Поддерживаемые версии MC для загрузчика (по контракту jar-нейминга).
pub fn supported_versions(loader: Loader) -> &'static [&'static str] {
    match loader.jar_loader() {
        Loader::Fabric => FABRIC_VERSIONS,
        Loader::Forge => FORGE_VERSIONS,
        Loader::NeoForge => NEOFORGE_VERSIONS,
        Loader::Quilt => unreachable!(),
    }
}

/// Есть ли встроенный jar для пары (версия, загрузчик)?
pub fn is_supported(version: &str, loader: Loader) -> bool {
    supported_versions(loader).contains(&version)
}

/// Первый из fabric/quilt/forge/neoforge, присутствующий в списке загрузчиков
/// модпака (порядок приоритета фиксированный). None — ни один не поддерживается.
pub fn primary_loader(loaders: &[String]) -> Option<Loader> {
    for (api, loader) in [
        ("fabric", Loader::Fabric),
        ("quilt", Loader::Quilt),
        ("forge", Loader::Forge),
        ("neoforge", Loader::NeoForge),
    ] {
        if loaders.iter().any(|l| l.eq_ignore_ascii_case(api)) {
            return Some(loader);
        }
    }
    None
}

/// Имя jar-файла для пары (версия, загрузчик) по контракту нейминга.
pub fn jar_name(target: &Target) -> String {
    match target.loader.jar_loader() {
        Loader::Fabric => format!("challenge-hud-{}.jar", target.version),
        Loader::Forge => format!("challenge-hud-{}-forge.jar", target.version),
        Loader::NeoForge => format!("challenge-hud-{}-neoforge.jar", target.version),
        Loader::Quilt => unreachable!(),
    }
}

/// Краткое описание поддержки для UI/логов.
pub fn support_summary() -> String {
    format!(
        "Fabric: {} | Forge: {} (1.16.5–1.20.4) | NeoForge: {} (1.20.2+) | Quilt: via Fabric",
        FABRIC_VERSIONS.len(),
        FORGE_VERSIONS.len(),
        NEOFORGE_VERSIONS.len(),
    )
}
