<p align="center">
  <img src="src/main/resources/minescreen.png" alt="MineScreen Canvas logo" width="192">
</p>

# MineScreen Canvas

[简体中文](README_ZH_CN.md)

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
2. Put `minescreen-1.2.0.jar` in the client `mods` folder.
3. For full WEB support, install the official MCEF NeoForge mod `2.1.6-1.21.1` on the client.
4. Start the game once, then configure MineScreen from `Mods -> MineScreen -> Config`.

MCEF is now an optional, recommended client backend. Without it, MineScreen still starts and keeps
IDLE, text, traffic, electric-light and pure-Java VNC features. MineScreen can use an already
installed Chromium-family browser as a muted emergency WEB backend. FFmpeg is included for Windows
x64, Linux x64/ARM64, and macOS x64/ARM64; an existing system `ffmpeg` + `ffprobe` can be selected
when the embedded native backend is not usable. MineScreen never downloads either external program.

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

WEB prefers MCEF's off-screen Chromium renderer. Its emergency backend controls an already-installed,
isolated Chromium process through a loopback-only internal interface. Both support HTTPS/HTTP according to configuration,
navigation, pop-up links as MineScreen tabs, tab switching, scrolling, clicking, keyboard focus,
and browser Pointer Lock when a page requests relative mouse movement. Press Escape to leave input
capture.

### VNC

VNC connects from the client to an RFB server. Tight-style rectangle decoding and configurable
refresh limits are used to reduce bandwidth. Credentials are stored in the local client credential
store; they are not sent through MineScreen's multiplayer state.

### Synchronized text boards

The Synchronized Text Board and Animated Text Board are lightweight public-information devices.
Right-click one to edit up to 512 characters, text/background colors, font size, animation and
speed. Same-facing, adjacent boards of the same kind automatically join into one logical canvas;
the deterministic master renders the complete text once and the other tiles do not duplicate it.
The server synchronizes those parameters and one start game time; each client renders static
wrapping, marquee, pulse, or warning-flash animation locally, so no text-frame video stream is sent.

Text Boards and Traffic Displays are the two flat display families that support two readable sides.
Their back can be disabled, mirror the front content, or use independently edited content. Ordinary
VIDEO/WEB/VNC Screen blocks remain intentionally single-sided.

Traffic Displays provide structured transit fields (service, destination, current/next stop, ETA,
status and a template ID). `Station_Next` resolves a nearby or LCD-Studio-bound Create station,
filters train types and displays Create's live departure predictions. `Station_Map` renders an RMP
or declarative route map and highlights the current station and next destination. Boards associated
with one Create station share a cached timetable snapshot instead of scanning per tile or frame.

External traffic layouts use declarative JSON plus optional PNG artwork under
`config/minescreen/traffic_templates/<template_id>/`. The importer accepts Rail Map Painter's native
`RMP_*.json` projects as a simplified station/line layer, and MineScreen `.js` generators that are
compiled once into bounded text/rectangle/line scenes. RMP remains the static map layer; MineScreen's
current/next stop, ETA, status, multiplayer timing and carriage behavior remain the live layer.

Imported JavaScript runs only in a disposable 64 MiB child JVM with class/file/network access denied,
then the source is discarded. It is never run from a server, browser, world tick or renderer. See
[Traffic templates](TRAFFIC_TEMPLATES.md) for the exact limits and the non-pixel-perfect RMP boundary.

Multiplayer can synchronize validated `script_scene_v1` manifests by SHA-256. The server requests
24 KiB chunks only for a missing hash and persists the bounded declaration in the world folder.
JavaScript source, local paths, RMP projects, PNG sidecars and rendered frames are never uploaded or
executed remotely.

Traffic and carriage displays can also import an optional UTF-8 TXT notice overlay from the
“Content & notice” page. The notice is drawn as a synchronized translucent strip over the existing
template; it does not replace the route map, station board, ETA, or live train animation. Pages use
`---` separators and may configure timing, transitions, alignment, and top/center/bottom placement.

### Electric Light Board

The Electric Light Board is a separate, single-sided LED/neon text device with a dark panel, cyan
full-bright output, scanlines and synchronized static/marquee/pulse/alert animation. It joins only
other Electric Light Boards, so it cannot accidentally merge with ordinary or animated Text Boards.

### Ceiling carriage display

The Ceiling Display is an equilateral-triangular-prism carriage display. Its two sloped faces can
show linked or independent traffic/MEDIA content, while the mounting face stays blank. Segments only
join along their selected horizontal axis, making the shape predictable in narrow vehicle interiors.
It is powered by default and does not require a redstone cable. A separate 45-degree single-sided
Door LCD joins only matching doorway panels and never merges with the 60-degree prism. In Traffic
mode on a Create carriage, both forms read the owning train's live navigation destination. The
traffic-only Carriage Arrival Information Strip is a horizontally joining, above-door layout that
automatically shows the Create train name/type, consist length, terminal, next and upcoming stops,
ETA, and remaining dwell time. Its terminal is inferred from ordered schedule predictions, with the
manually configured destination retained as a fallback for wildcard destinations. An optional
passenger notice (for example, a limited-express ticket requirement) is stored separately and is
not overwritten by arrival state. Displays switch to an arriving/prepare-to-alight notice inside a
configurable 10–120 second ETA window (10 seconds by default). Sloped-face WEB/VNC pointer mapping
is still experimental.

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
| Right-click a Text Board | Open its server-validated text/style editor. |
| Adjacent same-facing Text Boards | Automatically join into one synchronized text canvas. |
| Right-click a Traffic Display | Edit structured transit fields and select a client template ID. |
| Traffic editor “Content & notice” | Import or clear an optional dynamic TXT notice without changing the base template. |
| Traffic editor “Quick setup” | Select Manual, `Station_Next`, or `Station_Map`; live modes can bind a Create station. |
| Right-click the back of a Text/Traffic Display | Edit the independent back when two-sided mode is enabled. |
| Shift + right-click a Ceiling Display | Configure either sloped face and choose traffic or MEDIA content. |
| Shift + right-click a 45° Door LCD | Configure its single face; Traffic mode follows the assembled train's next stop. |
| Right-click a Carriage Information Strip | Edit fallback route data and an optional passenger notice; the assembled train schedule is read automatically. |
| Right-click an Electric Light Board | Edit its single-sided luminous text and animation. |

When a browser requests Pointer Lock, MineScreen aims at the physical screen that contains the
logical canvas center. This works across rotations, irregular layouts, gaps, and different screen
faces; it does not simply aim at the master block.

## Joining and multi-surface layouts

- Same-facing adjacent Screen blocks join automatically.
- A Computer and Screen Cables can connect screens on different faces.
- Host layouts include free panes, horizontal panorama, vertical panorama, and custom positions.
- A missing or disabled tile stays empty; it does not render an opaque area in the air.
- One host network can expose multiple regions, allowing different panes to play different content.
- Ordinary Screen blocks are single-sided; only Text/Traffic Displays have optional front/back content.

## Create carriages (experimental)

MineScreen can render an adjacent same-plane screen group from Create's client-side contraption
world. The carriage transform is supplied by Create while MineScreen keeps the existing VIDEO/WEB
session and texture alive across assembly. Moving screens currently remain active while rendered,
because virtual contraption redstone does not continuously tick like the normal world.

With Create 6.0.9 or newer installed, MineScreen uses Create's public contraption transform API for the
current world-space canvas bounds, screen audio source, and cable-connected carriage speakers.
Visibility and Chromium window activity therefore follow the carriage instead of its assembly site.

Moving same-plane screens now also accept crosshair clicks, wheel input, and the handheld Keyboard.
The world ray is transformed into carriage-local coordinates and Pointer Lock converts the selected
canvas center back to the carriage's current world position. Fixed Keyboards, carriage redstone,
cross-face Computer networks, and stricter contraption-block occlusion remain future work.

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

`config/minescreen-client.toml` contains client-only compatibility settings. The screen editor's
**Compatibility & backends** page shows the selected WEB/VIDEO engines, reruns detection, lets you
select existing executables, and copies a diagnostic summary. Program paths and probe results are
never uploaded to a server.

## Platform compatibility (v1.2.0)

The base game must be able to start Minecraft 1.21.1, Java 21 and LWJGL first. MineScreen's
compatibility layer cannot make an unsupported launcher, JVM or graphics stack boot, but it avoids
loading an optional native backend after the platform has been classified as incompatible.

| Runtime | WEB | VIDEO | Core displays / VNC |
|---|---|---|---|
| Supported Windows 10+, Linux or macOS desktop | MCEF preferred; installed Chromium fallback | Embedded FFmpeg preferred; system FFmpeg fallback | Supported |
| Windows 7 / old desktop | Experimental installed Chromium or last thumbnail | Experimental system FFmpeg | Best effort |
| LoongArch / uncommon desktop architecture | Installed Chromium when detected | Matching system FFmpeg when detected | Best effort if Minecraft starts |
| Pojav, Android, iOS, HarmonyOS | Dynamic WEB disabled; last thumbnail and reason | System executable only when actually present | Best effort |
| Unknown runtime | Optional native engines remain unloaded | Optional native engines remain unloaded | Core-only mode |

Win7, Pojav/Amethyst, HarmonyOS and LoongArch are experimental rather than supported release
targets. External browser mode is muted and rate-limited by default; external FFmpeg prioritizes
picture, pause, seek and loop and may be muted. No fallback program is bundled or downloaded.

## Custom artwork

Place optional transparent PNG files in [user_assets](user_assets/):

- `loading_decoration.png` is contain-fitted over the WEB loading/error background;
- `panel_decoration.png` is drawn at low opacity behind the Computer/editor UI;
- the same artwork is cropped to the lower gray area of IDLE so it is visible in world previews.

See [user_assets/README_ZH_CN.txt](user_assets/README_ZH_CN.txt) for size and composition guidance.
Missing artwork is ignored without a missing-texture placeholder.

## Known boundaries

- MCEF is optional but recommended for full WEB performance, page audio and the best input support;
  the external-browser fallback is an emergency compatibility path.
- Local video support is currently MP4-focused. The first audio stream is decoded to positional
  48 kHz stereo; selecting or mixing multiple audio tracks is not supported.
- VNC bandwidth depends heavily on desktop changes, compression, resolution, and FPS.
- Client-side sources are not a server-side media relay.
- Create carriage display configuration and moving pointer/handheld-keyboard interaction are
  supported, but should be validated with the exact Create build and contraption used by a pack.

## Documentation

- [Future roadmap](FUTURE.md)
- [Developer and compatibility notes](PORTING.md)
- [1.1.0 production-readiness report](docs/PRODUCTION_READINESS_1.1.0.md)
- [Changelog](CHANGELOG.md)
- [Traffic templates and Rail Map Toolkit PNG workflow](TRAFFIC_TEMPLATES.md)
- [JR-East-inspired carriage LCD browser/server example](examples/traffic_templates/jr_east_lcd/)
- [Linear dual-language carriage LCD browser/server example](examples/traffic_templates/linear_dual_language_lcd/)

## Special thanks

- Montoyo and WebDisplays, for the historical in-world web-display concept and context;
- CinemaMod/MCEF, for the off-screen Chromium integration used by WEB mode;
- FFmpeg and the Bytedeco JavaCPP project, for media decoding and Java native bindings;
- the NeoForge project, for the mod loader, APIs and tooling;
- simibubi and the Create team, for the optional contraption transform API;
- Rail Map Toolkit, for the external railway-map workflow targeted by MineScreen's PNG/template
  import path.
- jonhweider/TrainLCD, for product-design reference on localized station names, bounded station
  windows, and linear carriage-LCD information hierarchy;
- Mozilla Rhino, for the MPL-2.0 import-time JavaScript engine used in the isolated template worker.

MineScreen Canvas is independent and is not a WebDisplays, Create, MCEF, or Rail Map Toolkit
distribution. No Rail Map Toolkit project parser or third-party artwork is bundled unless its
license and attribution are explicitly documented.

## License

MineScreen code is distributed under the MIT License. See [LICENSE](LICENSE).
Embedded third-party components retain their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
