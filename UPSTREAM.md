# Upstream provenance

This project is an independent Minecraft 1.21.1 / NeoForge reimplementation inspired by
**WebDisplays**, with behavioral compatibility as an explicit goal.

- Upstream project: <https://github.com/montoyo/webdisplays>
- Upstream author: BARBOTIN Nicolas (Montoyo)
- Audited upstream commit: `cf16d2e1a4aa60d5a108c5d98d375dcb37c2a603`
- Upstream target: Minecraft 1.12.2 / Forge
- Upstream license statement: the mod and its source code were placed in the public domain;
  forks and redistribution are explicitly permitted, with attribution appreciated.

The original 1.12.2 code is used as a behavioral and data-model reference. Forge 1.12 APIs,
MCP names, `SimpleNetworkWrapper`, legacy TESR/OpenGL, metadata blocks, capabilities and bundled
MCEF code are not copied blindly. Their behavior is reimplemented against Java 21, Mojmap and
NeoForge 21.1.219.

New port code is distributed under the repository's MIT license. Existing MineScreen-derived
video, VNC, audio and P2P code is retained as an optional extension to the WebDisplays feature set.
