# MineScreen Canvas — developer notes

MineScreen Canvas is an independent Minecraft 1.21.1 / NeoForge 21.1.219 project. WebDisplays is
mentioned only as a historical special acknowledgement; MineScreen is not a port, drop-in
replacement, or promise of behavioral parity.

## Current implementation boundaries

- Common code uses Java 21, Mojmap names, DeferredRegister/DeferredHolder, and NeoForge events.
- Client-only code owns MCEF, FFmpeg, OpenGL textures, VNC connections, browser tabs, and local
  credentials.
- Optional server installation stores authoritative screen/peripheral state and permissions; it
  never receives decoded media frames or local paths.
- VIDEO, WEB, VNC, audio, cable topology, irregular canvases, and peer distribution are MineScreen
  features with their own data model and lifecycle.

## Useful extension points

| Area | MineScreen entry point | Notes |
|---|---|---|
| Screen topology | `ScreenGroupManager`, `ScreenHostNetworkManager` | Adjacent tiles and cable-linked surfaces are cached outside rendering. |
| Client content | `ScreenContentManager` | IDLE, VIDEO, WEB, and VNC sessions share render-source contracts. |
| WEB | `McefBrowserSession`, `BrowserRequestPolicy` | Tabs, navigation, Pointer Lock, and URL policy are client-local. |
| VIDEO | `VideoPlaybackSession`, FFmpeg decoder classes | Decoder and texture upload run on separate threads. |
| VNC | `VncScreenSession`, Tight decoder classes | Framebuffer updates are local to each client. |
| GUI art | `CustomUiArtwork` | Optional PNGs are composited into the GUI/IDLE layers. |

## Explicit non-goals

- No Forge 1.12 `TileEntity`, block metadata, `SimpleNetworkWrapper`, TESR, or fixed-function GL.
- No bundled CEF native binaries; official MCEF manages its own native components.
- No transfer of passwords, cookies, local paths, or decoded frames through the Minecraft server.
- No claim of binary, world-save, or protocol compatibility with WebDisplays.

For player-facing setup, controls, security, and troubleshooting, read [README.md](README.md) or
[README_ZH_CN.md](README_ZH_CN.md). Long-term product ideas are listed in [FUTURE.md](FUTURE.md).
