# MineScreen — WebDisplays behavior-port matrix

Target: Minecraft 1.21.1, NeoForge 21.1.219, Mojmap, Java 21 and external MCEF 2.1.6.

## Compatibility policy

- Registry namespace, distributable name and Java package remain `minescreen` / MineScreen.
- WebDisplays is the public-domain behavioral and feature reference, not a replacement identity.
- Upstream block/item concepts and interaction rules are the compatibility target; old Forge APIs
  and packet formats are not ABI-compatible.
- Server state is authoritative. CEF, OpenGL, FFmpeg, OpenAL and local credentials remain client-only.
- Existing VIDEO, VNC and P2P modes are extensions; they must not block WebDisplays parity work.

## Feature matrix

| Upstream WebDisplays feature | 1.21.1 port state | Port target |
|---|---|---|
| Joined wall screen and MCEF page | foundation available | Replace temporary profile model with authoritative WebDisplays screen-face state |
| Screen URL/navigation | foundation available | WebDisplays-compatible set-URL flow and permissions |
| Screen configurator | first pass available | Dedicated item opens the focused configuration UI; Shift-right-click remains migration fallback |
| Linker | partial through cables | Port explicit screen↔peripheral linker with DataComponents |
| Keyboard peripheral | partial | Handheld/fixed keyboard and host built-in keyboard available; upstream two-block keyboard behavior remains |
| Laser sensor/pointer | partial crosshair input | Dedicated laser item/upgrade and server-validated hit payload |
| MinePad | pending | Handheld browser item with persistent per-stack identity and client MCEF session |
| Laser/redstone/GPS upgrades | pending | DataComponent-backed installed-upgrade set and permission checks |
| Remote/redstone controllers | pending | Separate BlockEntity types and payloads |
| WebDisplays server/miniserv | pending redesign | Optional bounded content service; no 1.12 custom socket protocol reuse |
| Ownership and rights | partial | Port owner/friend/right bitset semantics to server saved data |
| ComputerCraft/OpenComputers | deferred | Optional adapter modules; no hard dependency in core |
| YouTube/distance volume | extension available in media layer | Reconnect browser media controls after core parity |
| VIDEO/VNC/audio/P2P | port extension available | Keep isolated from core WebDisplays compatibility |

## Migration order

1. Registry identity, attribution, resources and release artifact.
2. Authoritative screen-face state, URL, ownership and rights payloads.
3. Screen Configurator and Linker items.
4. Keyboard and Laser Pointer/peripheral input.
5. MinePad.
6. Upgrade slots, redstone input/output and GPS.
7. Remote Controller and Server/miniserv replacement.
8. Optional CC/OC adapters and final legacy-world remapping.

## Explicit non-goals

- No Forge 1.12 `TileEntity`, block metadata, `SimpleNetworkWrapper`, TESR or fixed-function GL.
- No bundled CEF native binaries; official MCEF manages them.
- No transfer of passwords, cookies or local paths through the Minecraft server.
- No claim of binary or world-save compatibility until registry remapping/data fixing is implemented.
