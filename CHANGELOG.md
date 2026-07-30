# Changelog

All notable user-facing changes are documented here.

## 1.1.0

### Highlights

- Added a universal desktop build with FFmpeg natives for Windows x64, Linux x64/ARM64, and
  macOS x64/ARM64.
- Added responsive layouts to the screen, computer, text display, traffic display, ceiling
  display, and station-alias editors.
- Added traffic displays, carriage information displays, text/electric displays, door LCDs,
  ceiling displays, and the Create train light test panel.
- Expanded Create carriage support for moving displays, carriage-local interaction, schedule
  data, station binding, and moving positional audio.

### LCD Studio and traffic templates

- Added optional dynamic UTF-8 TXT notice overlays. They render over the existing traffic,
  RMP, station, or carriage layout without replacing its template or live train data.
- Added synchronized notice pages, duration/alignment controls, top/center/bottom placement, and
  fade, slide, typewriter, blink, or static transitions.
- Fixed `manifest.json` imports losing line widths, explicit `null` values, and custom style
  metadata.
- Separated text-size validation from line-width validation. Compatibility `size` values on line
  elements now retain widths up to 64 instead of being clamped to 4.
- Wrapped manifests now preserve unknown sibling fields instead of copying a fixed allow-list.
- Added forward-compatible, namespaced extension fields. Optional future primitives can declare
  `"optional": true` so older clients skip them safely.
- Added structural round-trip and multiplayer synchronization regression tests using real
  `script_scene_v1` manifests.
- Added safer template catalog/download throttling, bounded uploads, hash validation, and stale
  transfer cleanup.

### Media, web, and networking

- Verified real 1280×720 MP4 decoding with the packaged FFmpeg runtime.
- Fixed unrestricted private single-player HTTP navigation being rejected by the integrated
  server. LAN and dedicated-server defaults remain protected.
- Hardened payload list decoding and template transfer rate limits.
- Added a real client smoke run that requires MCEF/CEF initialization and a rendered Minecraft
  frame before passing.
- Removed the missing dynamic-audio resource warning while preserving runtime PCM streaming.

### Quality and packaging

- Added release-integrity, security-policy, template-sync, ETA, Tight-decoder, FFmpeg, LCD Studio,
  client, and dedicated-server verification gates.
- Added third-party notices and packaged license checks.
- Kept MCEF as an external client dependency; CEF native binaries are not bundled into MineScreen.

### Compatibility notes

- Multiplayer protocol is now revision 13. MineScreen clients and MineScreen servers must update
  together; media frames and local paths are still not relayed.
- Minecraft Java Edition 1.21.1, NeoForge 21.1.219, and Java 21 are required.
- MCEF NeoForge 2.1.6-1.21.1 is required on clients using MineScreen.
- Create remains optional. Create-specific features activate only when a compatible Create build
  is installed.
