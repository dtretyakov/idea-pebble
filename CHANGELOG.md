# Changelog

<!--
  The top section of this file becomes the Marketplace's release notes verbatim, converted to HTML
  by `latestChangeNotes` in build.gradle.kts. That converter understands four things and no others:
  `## ` to open a release, `### ` subheadings, `- ` bullets with wrapped continuation lines, and
  inline `backticks`.
-->

## 0.1.0

- Finds pebble-tool on the PATH or where `uv tool install` puts it, its data directory, the
  installed SDKs and the active one.
- Recognises a Pebble project the way pebble-tool does: a `package.json` with a `pebble` section.
- Settings | Languages & Frameworks | Pebble, with a checklist of what a build needs: pebble-tool,
  an active SDK, its toolchain and Node.js.
- A banner in the editor when nothing could be built, offering to install the latest SDK or to
  activate an installed one.
