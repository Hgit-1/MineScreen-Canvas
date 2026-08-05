# Third-party notices

MineScreen's own source code is licensed under the MIT License. The final mod JAR also contains
unmodified third-party components with their own licenses.

## Mozilla Rhino 1.9.1

- Project: https://github.com/mozilla/rhino
- Artifact: `org.mozilla:rhino:1.9.1`
- License: Mozilla Public License 2.0
- Source: https://central.sonatype.com/artifact/org.mozilla/rhino/1.9.1
- License copy: `META-INF/licenses/rhino-MPL-2.0.txt` in the source tree and built JAR

MineScreen embeds the unmodified upstream Rhino JAR as a private resource solely for its client-side,
import-time JavaScript subprocess. It is not placed on NeoForge's normal mod class path. MineScreen
does not alter the license of Rhino, and recipients may replace or rebuild that component from the
upstream source above. MineScreen-authored files remain under MIT.

## JavaCPP 1.5.11

- Project: https://github.com/bytedeco/javacpp
- Artifact: `org.bytedeco:javacpp:1.5.11`
- Selected license: Apache License 2.0
- License copy: `META-INF/licenses/javacpp-Apache-2.0.txt`

MineScreen compiles legacy decoder sources against JavaCPP but no longer embeds the JavaCPP runtime
or JNI loaders in the mod JAR.

## FFmpeg 7.1 / JavaCPP Presets 1.5.11

- FFmpeg: https://ffmpeg.org/
- JavaCPP Preset: https://github.com/bytedeco/javacpp-presets/tree/1.5.11/ffmpeg
- Artifacts: `org.bytedeco:ffmpeg:7.1-1.5.11`
- Build family: standard non-`-gpl` JavaCPP artifacts
- License: GNU Lesser General Public License 2.1 or later, with separately licensed components
- License copy: `META-INF/licenses/ffmpeg-LGPL-2.1.txt`

MineScreen downloads one unmodified, platform-specific JavaCPP Presets FFmpeg artifact after the
Minecraft main menu appears. The package is stored outside the mod JAR, verified by HTTPS and a release-
pinned SHA-256, and its standalone FFmpeg executables/shared libraries remain separable components.
Corresponding upstream source and build scripts are available from the linked FFmpeg and JavaCPP
Presets repositories.

## Rail Map Painter / Rail Map Toolkit interoperability

- Project: https://github.com/railmapgen/rmp
- License: GPL-3.0-only

Rail Map Painter is not bundled. MineScreen contains an independently written JSON interoperability
adapter that reads a bounded subset of exported project data. It does not copy the RMP renderer,
migration chain, React components, fonts, artwork, or premium assets. Imported user projects and
their embedded brands, fonts, icons, or artwork remain subject to their respective licenses.

## TrainLCD design reference

- Project: https://github.com/jonhweider/TrainLCD
- License: MIT

TrainLCD is not bundled. MineScreen's linear dual-language LCD example independently implements a
bounded station window, localized station fields, passed-station grayscale, station numbering, and
dynamic name sizing after reviewing TrainLCD's public product architecture. It does not copy the
React Native components, fonts, icons, railway database, or operator artwork.

Other runtime libraries and required/optional mods are credited in `README.md` and
`README_ZH_CN.md`; they are not necessarily embedded in the MineScreen JAR.
