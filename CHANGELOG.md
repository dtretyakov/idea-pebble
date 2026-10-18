# Changelog

<!--
  The top section of this file becomes the Marketplace's release notes verbatim, converted to HTML
  by `latestChangeNotes` in build.gradle.kts. That converter understands four things and no others:
  `## ` to open a release, `### ` subheadings, `- ` bullets with wrapped continuation lines, and
  inline `backticks`.
-->

## 0.9.0

### Name

- The plugin is listed as Develop for Pebble. Pebble is a trademark of Core Devices LLC, which the
  plugin is not affiliated with; its ID, its MCP tools and its skill are unchanged.

### Getting started

- Set Up Pebble SDK: one button, on the balloon a project opens with, the editor banner, the
  settings page and the New Project wizard, installs whatever is missing of uv, pebble-tool and the
  latest SDK in one background task — no terminal, no `sudo`, nothing added to the PATH. A uv of
  your own is used when there is one; otherwise the plugin downloads uv 0.13.1, checked against a
  checksum it carries, into JetBrains' shared data directory. pebble-tool is installed on Python
  3.13, since the SDK does not support 3.14.
- A Pebble project that cannot be built says so as it opens, in a balloon, even with no file open;
  the editor banner says it above each file. "Don't show again" silences both for that problem.
- The emulator is checked before Set Up says the SDK is ready, and again when a project opens: a
  missing system library — SDL and friends on Linux, Homebrew's libpng on a Mac — or a glibc older
  than 2.38 is said in the project's balloon, and Run stops with the fix on its Fix button instead
  of failing in the console. The button runs the exact command for the machine — apt, dnf or
  Homebrew — in the IDE's terminal, where `sudo` can ask for the password, and everything updates
  by itself once it has worked. On an Intel Mac, where the SDK's emulator looks for libpng where
  only Apple silicon's Homebrew puts it, the plugin says so rather than offering a command that
  would not help.
- Debug for C stops at breakpoints in the app's start — `main`, `init`, the first window's load:
  once gdb is attached, the app is installed again and starts over under it, at the same address.
  Only with breakpoints set; the log goes on through the restart.
- Debug no longer logs an error on every launch about a debugger session's run content, which the
  split debugger does not hand out.
- The first Run on a cold emulator no longer ends in `Connection refused`: pebble-tool gives a
  starting emulator five seconds, so the plugin starts it in the before-launch task and waits
  until it can take the app.
- Node.js and clangd are mentioned where they matter: above PebbleKit JS files and C files, with the
  command that installs them, rather than at every file.
- Windows says what to do: Ubuntu 24.04 in WSL, or CloudPebble in the browser.
- The Pebble settings page fits its dialog and says only what is wrong, under the control it is
  about: pebble-tool and C code insight are named once, found tools are named in their fields'
  placeholders, and the SDK list shows before pebble-tool is installed, disabled. The paths it no
  longer lists are in Copy environment report, for a bug report.
- The page and the banner keep up: statuses are re-read on Apply and after an install, the banner is
  asked again whenever the IDE regains focus, and closing it silences that one problem rather than
  every later one. A path to pebble-tool or clangd that holds no executable is refused on Apply.

### Code insight

- `Pebble` resolves in PebbleKit JS: the typings are now a predefined JavaScript library, given to
  Pebble projects only, rather than an ordinary library JavaScript did not resolve globals from.
- A project that has never been built is loaded when it opens: one build in the background, as an
  IDE loads a CMake or Gradle project, because only waf knows how its C is compiled — the SDK's
  include directory, the platform's defines and the headers `pebble.h` includes. clangd starts once
  the compile database exists, instead of greeting the project with "'pebble.h' file not found"; a C
  file says it is loading meanwhile.
- A Pebble project is laid out when it first opens: `src/` and `worker_src/` are source roots,
  `resources/` a resource root, and waf's `build/`, its lock file and clangd's `.cache/` are
  excluded from search and Go to File. Once, and
  only in a module nobody has given source roots to.
- Alloy TypeScript sees the SDK's modules: after each build the plugin writes a `tsconfig.json`
  that extends the TypeScript settings the build checks the app with, so `import Poco from
  "commodetto/Poco"` resolves. A `tsconfig.json` of your own is left alone.

### Releasing

- Build | Check Pebble Release: a release build, checked against what the Pebble appstore takes —
  the version's form, the UUID, the `.pbw`'s size and platforms, resources within the limit, and
  each platform's screenshots at its exact size — in the Build window. For agents too.

### Coding agents

- Pebble tools in the IDE's MCP server, for agents such as Claude Code, Codex, Junie and Copilot:
  describe the project, build with problems listed once for all platforms and memory per
  platform, run in the emulator, read the logs from where they left off, press buttons, tap, set
  the time and battery, take screenshots, and look up the SDK's API — where a call is missing or
  only a stub, and the nearest real names when it does not exist.
- UI test scenarios as tools: run them in the IDE's test runner and get each screen's verdict,
  with the reference, the screenshot and a diff image of a failure; accept a new screen.
- The debugger as tools: start a debug session, set breakpoints with conditions or logged
  expressions, wait for a stop and read the stack and variables, step, evaluate, resume and stop
  — for C through gdb and for Alloy through the app's engine alike.
- Screens an agent can see: every screenshot comes with an enlarged view to read the watch's
  text from, and a failed UI test with the reference, the screen and their difference side by
  side.
- Emulator reset and SDK management as tools, for an agent whose emulator is stuck or whose
  project needs another SDK.
- Build Project, and the MCP server's `build_project`, run `pebble build`.
- A `pebble` skill for agents: in new projects, through Tools | Add Pebble Skill for Agents, and
  as a Claude Code plugin from the plugin's repository.

## 0.8.0

### Running and debugging

- C apps in the IDE's debugger: Debug installs the app and attaches gdb to the emulator as
  `pebble gdb` does. Breakpoints from the gutter — CLion's own in CLion — with conditions and
  logged expressions, stepping that keeps to the app's own sources, pause, the stack, variables,
  Evaluate Expression and watches, and a stop where the app crashes. Stop detaches gdb, and the
  app runs on. The gdb console is still there behind an advanced setting.

## 0.7.0

### Testing

- UI test scenarios: `.pbtest` files of steps — install, press, tap, wait, time, battery,
  Bluetooth, motion — and the screens to expect, run on each platform in the IDE's test runner.
  Each screen is compared with a reference screenshot, recorded on the first run; a difference
  fails the test with a picture of where, and Accept Screenshot takes the new one.

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
