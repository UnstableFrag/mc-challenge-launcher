use include_dir::{include_dir, Dir};
use std::env;
use std::fs;
use std::path::PathBuf;

const MOD_JAR: Dir = include_dir!("$CARGO_MANIFEST_DIR/assets/challenge-mod");

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let dest = out_dir.join("challenge-mod.jar");
    
    if let Some(file) = MOD_JAR.get_file("challenge-mod.jar") {
        fs::write(&dest, file.contents()).unwrap();
        println!("cargo:rustc-env=CHALLENGE_MOD_JAR={}", dest.display());
    } else {
        // В CI сборке мод будет положен в assets/ до компиляции
        // Для локальной разработки создаём заглушку
        if !dest.exists() {
            fs::write(&dest, b"").unwrap();
        }
        println!("cargo:rustc-env=CHALLENGE_MOD_JAR={}", dest.display());
    }
    
    println!("cargo:rerun-if-changed=assets/challenge-mod.jar");
}