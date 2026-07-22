# MineScreen mod icon brief

Use case: logo-brand

Asset type: Minecraft/NeoForge mod icon, square PNG

Primary request: an original compact emblem for MineScreen, combining a block-shaped world screen, the in-game IDLE color pattern, and the lower-right character from `user_assets/loading_decoration.png`

Style/medium: crisp Minecraft-style pixel icon; flat, readable chassis and test bars; the supplied character is pixel-sampled rather than regenerated; polished at 512×512 and still recognizable at 32×32

Composition/framing: one centered square screen block with stepped charcoal corners; its 16:9 viewport shows MineScreen's IDLE pattern; the loading character overlaps the lower-right bezel; generous safe margin

Color palette: deep charcoal frame, the exact flat IDLE test-bar family, and the warm yellow IDLE/status accent used in game

Constraints: no words or letters except the in-screen `IDLE` status; no Minecraft grass block; no browser/vendor marks; no gradients that become muddy at small sizes; no mockup, background scene, watermark, bevel clutter, or photorealistic chassis

Intended output: editable `docs/assets/minescreen-logo.svg`, pixel-sampled mascot companion PNG, and `src/main/resources/minescreen.png` as the 512×512 packaged icon. Validate at 64×64 and 32×32 and keep `logoFile="minescreen.png"` in `neoforge.mods.toml`.
