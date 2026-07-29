# MC Challenge Launcher

**One binary. Zero setup. Instant challenge.**

```
┌─────────────────────────────────────────────────────────────┐
│  🎲  Modpack Challenge Launcher                              │
├─────────────────────────────────────────────────────────────┤
│  📦  Pack:  Direwolf20 1.21                                   │
│  👤  Author: direwolf20                                       │
│  🎮  MC: 1.21.1                                               │
│  🏷️  Categories: magic, tech, adventure                       │
│  ⬇️  Downloads: 2,340,123                                     │
│                                                               │
│  🎯 TARGET: minecraft:elytra                                  │
│  ⏱️  TIME: 12:34.56                                          │
│                                                               │
│  [R] Roll    [X] Cleanup    [Q] Quit                          │
└─────────────────────────────────────────────────────────────┘
```

## How it works

1. **Press [R]** — fetches a random modpack from Modrinth
2. **Opens Modrinth App** — via `modrinth://` deep link
3. **Auto-injects** — drops `challenge-hud.jar` + config into the instance
4. **Play** — beautiful in-game HUD shows target item + live timer
5. **Get the item** — full-screen 🏁 victory screen with confetti
6. **Press [X]** — wipes the instance clean, ready for next run

## For your friend

1. Go to **Releases** (right sidebar)
2. Download `mc-challenge-launcher.exe`
3. Run it — that's it. No Java, no Rust, no Gradle.

## Building (for you)

```bash
# 1. Push to GitHub
git push origin main

# 2. Tag a version
git tag v0.1.0
git push origin v0.1.0

# 3. GitHub Actions builds everything:
#    - Java mod (Architectury, works on Fabric/Forge/NeoForge)
#    - Rust launcher (Windows .exe)
#    - Publishes to Releases
```

## Requirements

- **Modrinth App** installed (handles modpack installs)
- **Windows 10/11** (launcher binary)
- The mod works on **Fabric, Forge, NeoForge, Quilt** via Architectury

## Customizing the item pool

Edit `src/challenge.rs` — `ItemPool::default()`:

```rust
items: vec![
    "minecraft:diamond".into(),
    "minecraft:netherite_ingot".into(),
    "minecraft:elytra".into(),
    "#minecraft:tools".into(),      // tags work!
    "#forge:ores/diamond".into(),   // Forge tags too
]
```

## License

MIT — do whatever.