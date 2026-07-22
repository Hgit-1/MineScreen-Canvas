# MineScreen Canvas

MineScreen Canvas is a client-first display mod for Minecraft Java 1.21.1 and NeoForge. It lets
you build screens in the world and use them for web pages, local video, VNC desktops, or a clear
IDLE test pattern. Adjacent screens can form one canvas, while cable-linked screens can be arranged
as independent panes or as one panoramic display.

> [!NOTE]
> This project was written with assistance from AI coding tools. All code, dependencies, media
> behavior, and security settings still require human review. Please report reproducible issues
> with your Minecraft version, NeoForge version, mode, and client log.

> [!WARNING]
> WEB, VIDEO, and VNC sources are normally opened by each player's client. The Minecraft server
> does not relay decoded video/VNC frames. Only configured multiplayer state may be synchronized;
> local file paths, passwords, cookies, and browser pixels stay on the client.

## Install

1. Install Minecraft Java 1.21.1 with NeoForge 21.1.219.
2. Put `minescreen-1.0.0.jar` in the client `mods` folder.
3. For WEB mode, also install the official MCEF NeoForge mod `2.1.6-1.21.1`.
4. Start the game once, then configure MineScreen from `Mods -> MineScreen -> Config`.

MCEF is only needed on clients that use WEB mode. A dedicated server does not need MCEF. FFmpeg
is included for the supported local-video path; Windows x64 is the primary validation platform.

## First screen

1. Place a Screen block and connect its cable network to a powered redstone source or lever.
2. Place adjacent Screen blocks with the same facing to form a canvas automatically. Missing tiles
   remain empty instead of producing fake black geometry.
3. Look at a powered screen, then hold Shift and right-click, or use the Screen Configurator item.
4. Choose IDLE, VIDEO, WEB, or VNC and press Save/Apply.
5. A connected Computer opens the larger control panel. Its preview remains visible when the GUI
   is closed.

The screen is black while unpowered and cannot be configured from the screen itself until power is
restored. The Computer panel can still be opened and will explain the power state.

## Modes

### IDLE

IDLE is the safe starting mode. It draws color bars, orientation lines, a centered `IDLE` label,
and—when supplied—your transparent artwork in the lower gray test area. It opens no network
connection and is useful for checking facing, rotation, joins, and empty spaces.

### VIDEO

VIDEO plays a local MP4 through FFmpeg. It supports play/pause, seeking, looping, a configurable
resolution, and a maximum of 30 FPS. The file path is stored locally and is never sent to a server.

### WEB

WEB uses MCEF's off-screen Chromium renderer. It supports HTTPS/HTTP according to configuration,
navigation, pop-up links as MineScreen tabs, tab switching, scrolling, clicking, keyboard focus,
and browser Pointer Lock when a page requests relative mouse movement. Press Escape to leave input
capture.

### VNC

VNC connects from the client to an RFB server. Tight-style rectangle decoding and configurable
refresh limits are used to reduce bandwidth. Credentials are stored in the local client credential
store; they are not sent through MineScreen's multiplayer state.

## Using the controls

| Action | Result |
|---|---|
| Crosshair on a powered screen | The crosshair is the virtual pointer position. |
| Left click | Click the screen. Hold a supported pickaxe in the main hand to mine the Screen instead. |
| Mouse wheel | Scroll the focused WEB/VNC surface. |
| Shift + right-click | Open the screen editor. |
| Right-click Computer | Open the host panel. |
| Right-click Fixed Keyboard | Enter keyboard input mode. |
| Hold the handheld Keyboard item | Route keyboard input to the screen while focused. |
| Escape | Release keyboard focus or browser Pointer Lock. |

When a browser requests Pointer Lock, MineScreen aims at the physical screen that contains the
logical canvas center. This works across rotations, irregular layouts, gaps, and different screen
faces; it does not simply aim at the master block.

## Joining and multi-surface layouts

- Same-facing adjacent Screen blocks join automatically.
- A Computer and Screen Cables can connect screens on different faces.
- Host layouts include free panes, horizontal panorama, vertical panorama, and custom positions.
- A missing or disabled tile stays empty; it does not render an opaque area in the air.
- One host network can expose multiple regions, allowing different panes to play different content.

## Multiplayer behavior

MineScreen is designed as a client mod with an optional server installation. In multiplayer, install
MineScreen on the server when authoritative screen state and permissions are required. Clients still
open their own WEB/VNC/video sources. Playback timestamps and selected state can be synchronized,
but frame-perfect visual identity is not guaranteed because network latency, decoding speed, and
source timing differ between clients.

## Security and configuration

Open `config/minescreen-common.toml` or use the NeoForge configuration screen. The policy supports
domain allowlists and controls for HTTP, localhost, private IPs, cloud metadata, arbitrary domains,
and `file://`. Single-player defaults are intentionally convenient; review them before opening a
world to LAN.

Useful settings include:

- screen resolution and canvas pixel limits;
- WEB loading animation and page thumbnail behavior;
- `web_loading_show_custom_decoration`;
- `ui_show_custom_decoration` and `ui_custom_decoration_opacity_percent`;
- VNC FPS, WEB peer distribution, audio distance, and render distance.

## Custom artwork

Place optional transparent PNG files in [user_assets](user_assets/):

- `loading_decoration.png` is contain-fitted over the WEB loading/error background;
- `panel_decoration.png` is drawn at low opacity behind the Computer/editor UI;
- the same artwork is cropped to the lower gray area of IDLE so it is visible in world previews.

See [user_assets/README_ZH_CN.txt](user_assets/README_ZH_CN.txt) for size and composition guidance.
Missing artwork is ignored without a missing-texture placeholder.

## Known boundaries

- WEB requires MCEF on the client.
- Local video support is currently MP4-focused and has no audio track mixing.
- VNC bandwidth depends heavily on desktop changes, compression, resolution, and FPS.
- Client-side sources are not a server-side media relay.

## Documentation

- [中文用户说明](README_ZH_CN.md)
- [Future roadmap](FUTURE.md)
- [Developer and compatibility notes](PORTING.md)

## Special thanks

Special thanks to Montoyo and the WebDisplays project for helping establish the idea of in-world
web displays and for useful historical context. MineScreen Canvas is an independent 1.21.1
NeoForge project, not a WebDisplays port or drop-in replacement, and its video, VNC, audio, cable,
canvas, and peer features have their own implementation and behavior.

## License

MineScreen code is distributed under the MIT License. See [LICENSE](LICENSE).
