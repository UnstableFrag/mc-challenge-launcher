# MC Challenge Launcher

**One binary. Zero setup. Instant challenge.**

## How it works

1. **Press [R]** — fetches a random modpack from Modrinth Index (popular modpacks with 100+ follows)
2. **Opens Modrinth App** — via `modrinth://` deep link
3. **Auto-injects** — drops `challenge-hud.jar` + config into the instance
4. **Play** — beautiful in-game HUD shows target item + live timer
5. **Get the item** — full-screen 🏁 victory screen with confetti
6. **Minecraft auto-closes** after the challenge ends (5-second timer)
7. **Auto-cleanup** — instance is wiped automatically, ready for the next round
8. **Press [X]** — manually wipe instance + reset at any time

## CLI

```
mc-challenge-launcher                    # random modpack from Modrinth Index
mc-challenge-launcher --modpack my-pack  # specific modpack by slug
```

If your friend doesn't have Modrinth, they can still join — just share a modpack slug and anyone can launch it with `--modpack`. If no modpacks are found on Modrinth, a built-in fallback list is used.

## Requirements

- **Modrinth App** installed (handles modpack installs)
- **Windows 10/11** (launcher binary)
- The mod works on **Fabric, Forge, NeoForge, Quilt** via Architectury

## License

MIT — do whatever.
