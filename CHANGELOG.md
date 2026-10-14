# Changelog

<!--
  The top section of this file becomes the Marketplace's release notes verbatim, converted to HTML
  by `latestChangeNotes` in build.gradle.kts. That converter understands four things and no others:
  `## ` to open a release, `### ` subheadings, `- ` bullets with wrapped continuation lines, and
  inline `backticks`.
-->

## 0.6.0

### Running and debugging

- Alloy expressions in the debugger: Evaluate Expression and watches, breakpoint conditions, and
  breakpoints that log a message or an expression without stopping — compiled for the watch by
  the SDK's own `xsc`.
- When an app exits with memory it never freed, the run's console says how much.

### Building

- Each platform's RAM, free heap and resources under that platform in the Build window, with a
  warning when the heap is short or the app store would refuse the resources.

## 0.5.0

### Running and debugging

- Debug on an Alloy app runs in the IDE's own debugger, in place of xsbug: breakpoints — the
  JavaScript debugger's own where it is installed — stepping, pause, the call stack, variables and
  globals, break on exceptions, and the app's `console.log` in the console. TypeScript sources
  take breakpoints too.
- Stopping an Alloy debug session logs out first, so the app runs on, without the watch's "not
  responding" dialog.
- Debug says so when port 5002 is taken, as by an open xsbug.

## 0.4.0

### Running and debugging

- Debug for C apps in the emulator: a debug build is installed and `pebble gdb` runs in the
  console, where breakpoints, `bt` and `print` are typed.

### The emulator

- Capture Every Platform: a screenshot of the app on each platform it targets, from pebble-tool's
  own `screenshot --all-platforms`, side by side in the Pebble Emulator tool window's new
  Platforms tab.

### Code

- Calls in C to functions that some target platforms only stub out — the speaker, touch, the RGB
  backlight on the older watches — are reported where no `PBL_` condition chooses the platform.
  They compile everywhere and do nothing there, so the build never says.
- In `package.json`, a resource whose file is not in `resources/` is an error, and a message key
  no source uses is shown as unused.

## 0.3.0

### Running and debugging

- Debug for Alloy apps: a debug build is installed, and pebble-tool opens xsbug, Moddable's
  JavaScript debugger, beside it.
- A minimum log level per run configuration; PebbleKit JS lines count as Info.
- After a run that could not reach a cold emulator, the console says to run again.

### The emulator

- Clicks on a Pebble Time 2 or Round 2 screen land where they are made, and drags are touch moves.
- Battery, Bluetooth, the clock, tap, tilt, compass, Timeline Quick View and content size, from
  the Pebble Emulator tool window.
- Record GIF: the emulator's screen as an animated GIF, for up to a minute.

### Code

- In `package.json`, platform names and project types complete in the `pebble` section, and
  unknown platforms, Alloy where it cannot run and unknown project types are reported.
- PebbleKit JS typings under External Libraries, so `Pebble.` completes in `src/pkjs`.

### SDKs

- Settings lists the installed SDKs to activate or remove, and installs any version pebble-tool
  offers.

## 0.2.0

### Build and run

- Build | Build Pebble App runs `pebble build` into the Build tool window, with gcc's errors and
  warnings grouped by platform and a click away from the line.
- A Pebble run configuration installs the app in the emulator, on a phone over Developer
  Connection or CloudPebble, or on an Android phone over adb, after building it, and then shows
  the app's logs in the console — in pebble-tool's colours, with `main.c:42` as a link to the
  source. One is made when a Pebble project opens.
- The platform to build, run and analyse for is chosen beside the Run button.
- When a run cannot reach the phone, the console says where to look — Developer Connection, the
  network, `pebble login` — after pebble-tool's own error.
- Tools | Pebble | Take Screenshot saves the watch's screen; a run's Logs tab can save its output.

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
