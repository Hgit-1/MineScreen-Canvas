# MineScreen Canvas

[English](README.md) | [简体中文](README_ZH_CN.md)

**MineScreen Canvas** is the project name for MineScreen's Minecraft 1.21.1 NeoForge implementation. The mod id and registry namespace remain `minescreen`, and the in-game mod name remains **MineScreen** for save compatibility.

MineScreen places joined displays in the world and renders WEB pages, VIDEO sources, VNC framebuffers, and an IDLE test pattern. Screens can form irregular canvases, independent regions, or cable-linked multi-surface layouts. Content is rendered by each client; the optional server component synchronizes metadata, permissions, and playback state rather than forwarding video or VNC frames.

## WebDisplays relationship

Yes. MineScreen Canvas uses Montoyo's WebDisplays 1.12.2 project as a behavioral, interaction, peripheral, and data-model reference. The audited upstream revision and public-domain notice are documented in [UPSTREAM.md](UPSTREAM.md).

This repository is an independent Java 21 / Mojmap / NeoForge 21.1.219 reimplementation. It is not an official Montoyo release, does not reuse legacy Forge/MCP APIs, and does not bundle the old Chromium integration. Local video, VNC, positional audio, irregular canvases, content regions, and optional WEB peer distribution are MineScreen extensions. See [PORTING.md](PORTING.md) for compatibility progress.

## Requirements

- Minecraft Java Edition 1.21.1
- NeoForge 21.1.219
- Java 21
- Official MCEF NeoForge 2.1.6-1.21.1 on clients
- MineScreen on clients, and also on the server for multiplayer synchronization

Dedicated servers do not install MCEF and do not initialize Chromium, FFmpeg, OpenAL, or client render classes.

## Quick start

1. Install MineScreen and MCEF on the client.
2. Place `minescreen:screen` blocks. Coplanar tiles with the same facing join automatically.
3. Attach at least one `minescreen:screen_cable`, then power any cable in the connected network with a lever, powered redstone wire, button, or another redstone source.
4. Use `minescreen:screen_configurator` on a powered screen, or use the legacy Shift + right-click gesture.
5. Choose IDLE, VIDEO, WEB, or VNC and save the profile.
6. Aim at WEB/VNC content to click or scroll. Keyboard input requires the handheld keyboard, a linked fixed keyboard, or the computer's built-in preview keyboard.

An unpowered screen is black, releases its content backend, and refuses to open its screen panel. A linked computer remains fully configurable while unpowered and displays a clear `NO POWER` status.

Use an iron-or-better pickaxe in the main hand to remove a screen. While a valid pickaxe is held, the attack button is reserved for block breaking and is not sent to WEB/VNC.

## Content modes

### IDLE

Displays a color test image and a centered IDLE mark without opening an external connection. Old `TEST` profiles migrate to IDLE.

### VIDEO

- Local MP4 files and direct HTTP(S) media URLs, including signed URLs up to 65,535 characters.
- FFmpeg 7.1 JavaCPP decode, a three-slot bounded frame ring, reused DynamicTexture uploads, up to 30 FPS, looping, seeking, and pause.
- FFmpeg/OpenAL positional audio with distance attenuation and an audio clock used to calibrate video.
- Local paths never enter server payloads. Multiplayer clients map the same shared media id to their own local file.

### WEB

- MCEF off-screen Chromium at the configured canvas resolution.
- Managed tabs, new-window capture, back/forward/reload, current-tab navigation, and single/two/four-pane layouts.
- Main-frame loading animation and an error page for HTTP, DNS, TLS, and CEF failures.
- Exit thumbnails are cached locally under `config/minescreen-web-thumbnails/` and displayed while Chromium restores a page.
- Crosshair-based mouse input. When a page requests Pointer Lock, the virtual cursor is centered and the player view is aligned perpendicular to the physical screen before camera rotation is frozen.

Optional peer-assisted WEB distribution uses a deterministic direct-player tree. The Minecraft server exchanges only endpoints and session tokens. JPEG page frames use the direct peer connection and never traverse the game server.

### VNC

- RFB 3.3/3.7/3.8 with None and classic VNC authentication.
- Raw, CopyRect, DesktopSize, LastRect, and mature Tight decoding with persistent zlib streams, palette/gradient filters, JPEG, and PNG.
- Per-screen VNC request FPS. Limiting FPS reduces framebuffer requests at the VNC server instead of discarding already-received frames.
- Multiple players may control the same endpoint concurrently by default. Passwords remain in `config/minescreen-vnc-credentials.json`.

Classic VNC is not encrypted. Use a trusted VPN, SSH tunnel, or TLS tunnel outside MineScreen.

## Joined and multi-surface displays

- NORTH, SOUTH, EAST, WEST, UP, and DOWN screen faces are supported.
- Irregular and cable-separated layouts draw only real screen tiles. Missing cells do not create black quads or invisible collision-free surfaces.
- One physical screen group can assign tiles to the main region plus independent regions 1-3.
- A cable-linked computer can keep each physical surface independent or combine different planes into horizontal, vertical, or custom canvases.
- Surface order, logical position, rotation, split assignment, and per-region content are client-local profile data.
- Joined canvas limits default to 3840x2160 and about 8.3 MP. Resolution presets from 25% to 100% trade detail for larger WEB UI and lower resource use.

## Input

- Crosshair over WEB/VNC: move, click, and scroll without creating a separate OS cursor.
- Handheld keyboard while aiming: exclusive keyboard input; Minecraft and other mod hotkeys are suppressed.
- Linked fixed keyboard: right-click to enter input mode.
- Computer preview: built-in mouse and keyboard input.
- `Esc`: release keyboard mode and WEB Pointer Lock.
- Pointer Lock always starts from the browser center and aligns the player view to the screen normal, avoiding a retained upward/downward camera angle.

## UI and loading customization

MineScreen media previews, widget frames, text, EditBox content, and carets are composited into one texture by default for ModernUI compatibility. Optional UI adapters can register through `MineScreenUiProvider`.

The native NeoForge config screen uses short English labels to avoid entry overflow and includes an optional original pixel assistant. The screen editor and computer use the same assistant without placing it over controls.

The following options are available in `config/minescreen-common.toml` and in `Mods -> MineScreen -> Config`:

- `web_loading_style`: `ORBIT`, `PULSE`, or `MINIMAL`
- `web_loading_accent_color`: ARGB animation color
- `web_loading_background_color`: ARGB fallback background
- `web_loading_speed_percent`: 25-300
- `web_loading_show_thumbnail`
- `web_loading_show_mascot`
- `ui_show_mascot`
- `ui_provider` and `composite_ui_layer`

## Power behavior

MineScreen power travels only through the logical extension-cable network. The cable is not a vanilla redstone conductor. A powered lever can touch any cable face; powered redstone wire and other powered signal blocks are recognized explicitly. Power topology is cached and refreshed outside the renderer.

Power loss immediately renders black and closes Chromium, FFmpeg, VNC, audio, and associated textures. Restoring power recreates the selected backend asynchronously from the saved profile.

## Network security

An integrated single-player world that is not open to LAN uses `unrestricted_singleplayer=true` by default. HTTP, localhost, private addresses, arbitrary domains, cloud metadata, and local files are then available without a blocking prompt.

LAN and multiplayer restore the protected defaults:

- HTTPS and a domain allow-list by default
- HTTP, `file://`, localhost, private/link-local addresses, and cloud metadata blocked
- Resolved IP addresses checked to reduce DNS rebinding bypasses
- Explicit config switches for each relaxed capability

## Multiplayer synchronization

The optional server component validates topology, dimensions, distance, ownership/OP permissions, URLs/media ids, playback timestamps, volume, and access mode. It does not proxy VIDEO or VNC frames. Each client connects to and renders its own source.

WEB metadata synchronization sends a deduplicated active URL after navigation stabilizes. Optional WEB peer distribution sends compressed page pixels directly between participating clients, not through the Minecraft server.

## Performance model

- Screen grouping, cable power, and host topology are tick-batched and never rebuilt in a BER render call.
- URL/DNS/file preflight runs on one bounded worker.
- At most one prepared content backend is finalized per client tick.
- VIDEO uses bounded decode/audio queues and one reused texture.
- VNC uploads dirty rectangles only.
- Hidden Chromium tabs use a 64x64 viewport; restored tabs are created one per tick.
- Loading animation uploads are capped and reuse one texture.
- Thumbnail JPEG encoding and disk IO use a bounded background worker; at most 128 cached files are retained.

## Build

Use Java 21 and the included wrapper:

```powershell
.\gradlew.bat clean build
```

The release artifact is `build/libs/minescreen-0.3.0.jar`.

Key dependencies:

- `org.bytedeco:ffmpeg-platform:7.1-1.5.11`
- `com.cinemamod:mcef:2.1.6-1.21.1` for compilation
- `com.cinemamod:mcef-neoforge:2.1.6-1.21.1` for development runtime

MCEF manages its own CEF/JCEF native components. MineScreen does not bundle the CEF native runtime.

## License

New MineScreen code is MIT licensed. WebDisplays provenance and its public-domain notice are documented in [UPSTREAM.md](UPSTREAM.md).
