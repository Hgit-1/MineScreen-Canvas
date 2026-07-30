# MineScreen 1.1.0 production-readiness report

Date: 2026-07-30
Target: Minecraft 1.21.1 / NeoForge 21.1.219 / Java 21

> [!IMPORTANT]
> “Production-ready” here means the automated and local runtime gates below passed on the tested
> environment. It is not a guarantee that every graphics driver, modpack combination, website,
> VNC server, or Create contraption will behave identically. Back up worlds before upgrading and
> validate a staging server before a public deployment.

## Release gates

| Area | Result | Evidence |
|---|---:|---|
| Full Gradle build | Pass | `build` completed with all verification tasks |
| Client startup | Pass | MCEF discovered, CEF initialized, resources loaded, first frame rendered |
| Dedicated server | Pass | Started without MCEF/Create, reached `SERVER_STARTED`, saved and stopped |
| Real MP4 | Pass | `slideshow.mp4`, decoded RGBA frame at 1280×720, duration 30,000 ms |
| User LCD manifest | Pass | `manifest.json`, 12 stations, full structural round-trip |
| Template multiplayer sync | Pass | validation, compression, chunk assembly, SHA-256 and forward fields |
| Dynamic TXT notice overlay | Pass | bounded import, world-time playback, template-preserving overlay and sync validation |
| LCD Studio simulation | Pass | normal simulation plus 11-transfer stress fixture |
| FFmpeg packaging | Pass | JavaCPP API and five desktop native targets found in release JAR |
| VNC Tight decoder | Pass | decoder regression suite |
| RFB loopback | Pass | RFB 3.8, VNC password challenge, raw frame, pointer and key events |
| VNC live endpoint | Not run | `127.0.0.1:5900` was closed during the final test |
| Security policy | Pass | HTTPS/HTTP, private single-player, LAN, private IP and file rules |
| ETA estimator | Pass | acceleration, cruise and braking fixtures |
| Responsive layout math | Pass | seven viewports down to 320×180 and pointer round-trip |
| Resource integrity | Pass | version 1.1.0 and all 13 registered block families |

## Manifest compatibility contract

MineScreen 1.1.0 preserves unknown top-level and per-element JSON properties when importing,
installing, saving, and synchronizing traffic templates.

- Text `size`: accepted range `0.05..4`.
- Line width: `stroke_width`, `line_width`, or compatibility `size`; accepted range `0.05..64`.
- Explicit JSON `null` values are serialized and retained.
- Wrapped manifests may place extension metadata next to `manifest` or `template`; it is merged
  generically, while fields inside the manifest take precedence.
- New vendors should use namespaced keys, for example `example:glow_profile`.
- A future primitive unknown to MineScreen 1.1.0 must set `"optional": true` to be skipped safely.
  Unknown required primitives fail with a clear validation error instead of producing a corrupted
  display.

See `TRAFFIC_TEMPLATES.md` for the authoring contract.

## Runtime and deployment boundaries

- The MineScreen release JAR contains the Java FFmpeg runtime and desktop native libraries.
- MCEF remains a separate client mod and downloads/manages its own CEF/JCEF native package.
- Dedicated servers do not load client renderers, MCEF, CEF, OpenGL, or FFmpeg decoders.
- Servers synchronize configuration, state, timestamps, and validated traffic templates; they do
  not relay video, web, or VNC framebuffer pixels.
- Local media paths and credentials remain client-local.

## Security and abuse controls

- Internet access defaults remain configurable through NeoForge TOML configuration.
- Private, unpublished single-player may use its documented relaxed defaults.
- Publishing to LAN restores network boundary checks.
- Template uploads use fixed-window per-player byte budgets.
- Catalog and download requests are throttled.
- Payload list counts are rejected when invalid rather than partially decoded.
- Transfer assemblies expire and content is verified before installation.

## Manual release checklist

Before publishing a public server or store listing:

1. Start a fresh client profile with MineScreen and MCEF and allow the first CEF download to
   complete without closing the game.
2. Test at least one WEB page, one local MP4, and the actual target VNC server.
3. Test the exact Create version used by the server with a moving train, chunk unload/reload,
   assembly/disassembly, and station dwell.
4. Test GUI scale 1–4 and at least 1280×720, 1920×1080, and an ultrawide resolution.
5. Test with the final performance/rendering mods used by the modpack.
6. Back up and copy a representative world, then verify upgrade and rollback procedures.
7. Review the NeoForge configuration before exposing HTTP, localhost, private IPs, file URLs, or
   an unrestricted whitelist.

## Known operational notes

- The universal JAR is intentionally large because it carries FFmpeg natives for five desktop
  targets.
- The first WEB launch can take longer while MCEF downloads CEF. MineScreen does not distribute
  that native package.
- ETA is an estimate. Signals, unscheduled player intervention, other mods, and previously unseen
  route conditions can still change an arrival time.
- Create and VNC live interoperability should be validated against the exact server versions and
  topology used in production.
