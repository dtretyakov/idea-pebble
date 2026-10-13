# Changelog

<!--
  The top section of this file becomes the Marketplace's release notes verbatim, converted to HTML
  by `latestChangeNotes` in build.gradle.kts. That converter understands four things and no others:
  `## ` to open a release, `### ` subheadings, `- ` bullets with wrapped continuation lines, and
  inline `backticks`.
-->

## 0.2.0

### Build and run

- Build | Build Pebble App runs `pebble build` into the Build tool window, with gcc's errors and
  warnings grouped by platform and a click away from the line.
- A Pebble run configuration installs the app in the emulator, on a phone over Developer
  Connection or CloudPebble, or on an Android phone over adb, after building it, and then shows
  the app's logs in the console — in pebble-tool's colours, with `main.c:42` as a link to the
  source. One is made when a Pebble project opens.
- The platform to build, run and analyse for is chosen beside the Run button.

### The emulator

- The Pebble Emulator tool window shows the emulator's screen, live, with Back, Up, Select and
  Down beside it — or the arrow keys — and saves screenshots. Clicking the screen of a Pebble Time
  2 or Pebble Round 2 is a touch.

### Code

- C gets completion, navigation and diagnostics for the chosen platform: natively in CLion, and
  through clangd and the LSP4IJ plugin in IntelliJ IDEA and WebStorm. `compile_commands.json` is
  kept up to date with `pebble compile-commands`.
- The SDK's headers, and for Alloy projects its typings, are under External Libraries.

### Projects

- New Project | Pebble makes a C or Alloy watchapp or watchface with `pebble new-project`.

## 0.1.0

- Finds pebble-tool on the PATH or where `uv tool install` puts it, its data directory, the
  installed SDKs and the active one.
- Recognises a Pebble project the way pebble-tool does: a `package.json` with a `pebble` section.
- Settings | Languages & Frameworks | Pebble, with a checklist of what a build needs: pebble-tool,
  an active SDK, its toolchain and Node.js.
- A banner in the editor when nothing could be built, offering to install the latest SDK or to
  activate an installed one.
