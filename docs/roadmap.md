# Roadmap

The product plan, mapped onto what the plugin builds and which IntelliJ extension points it builds
them on. The principle throughout is the one in [Architecture](architecture.md): pebble-tool does
the work, the IDE provides the UI, and the plugin connects them.

## Phase 0 — Foundation

Done when a Pebble project opens in IDEA, CLion or WebStorm and the plugin can say whether
everything a build needs is installed.

- [x] Gradle build, CI on Linux, macOS and Windows, Plugin Verifier against IDEA (newest and
  oldest supported), CLion and WebStorm, a headless self-check.
- [x] Finding pebble-tool (PATH, then uv's bin directories), its data directory, the installed
  SDKs and the active one — read from disk, not from `pebble sdk list`.
- [x] Recognising a Pebble project the way pebble-tool does: a `package.json` with a `pebble` key.
- [x] Settings | Languages & Frameworks | Pebble, with the environment checklist.
- [x] An editor banner when nothing could be built, offering to install or activate an SDK.

## Phase 1 — MVP: the complete development loop

Done when a developer can create or open a project, write code with SDK completion, build it, run
it in the emulator inside the IDE, install it on a watch and read its logs without typing a CLI
command.

Done so far — each item verified against pebble-tool 5.0.40 and SDK 4.33.1 by a live test:

- [x] **New project.** `pebble new-project` behind IDEA's wizard (`newProjectWizard.generator`) and
  CLion's and WebStorm's (`directoryProjectGenerator`): C or Alloy, watchapp or watchface,
  PebbleKit JS, a worker.
- [x] **SDKs.** The installed SDKs in Settings, to activate, remove, or add to from the versions
  pebble-tool offers; the latest from the editor banner. Libraries, the compile database and the
  banner follow every change.
- [x] **Active platform.** A selector in the run widget, stored in the workspace, read by the build,
  the compile database, runs and the emulator.
- [x] **C code insight.** `pebble compile-commands --platform <active>` on a switch and after a
  build. CLion reads it natively; IDEA and WebStorm get clangd through LSP4IJ — an optional
  dependency — with a `--query-driver` glob over the SDKs' gcc so newlib's headers resolve. The
  active platform's headers under External Libraries.
- [x] **Alloy typings** under External Libraries for the IDE's JavaScript and TypeScript support.
- [x] **Build.** `pebble build` into the Build tool window, gcc and linker diagnostics grouped by
  platform with navigation, waf's steps as progress.
- [x] **Run.** A Pebble run configuration — Emulator, Phone, CloudPebble or adb — with the build as
  a before-launch task and `pebble install … --logs` on a pseudo-terminal, so the console shows
  levels in pebble-tool's colours and log locations link to the source. Made when a project opens.
- [x] **Watches.** Tools | Pebble | Take Screenshot from the watch the selected run configuration
  installs on; a hint after pebble-tool's error when a run cannot reach the phone; the Logs tab's
  "Save console output to file" on every Pebble run, and a minimum log level per run — read from
  pebble-tool's colours, the only place the level is.
- [x] **package.json.** Platform names and project types complete in the `pebble` section, and an
  inspection reports unknown platforms, Alloy on a platform without it, and project types
  pebble-tool cannot build. Not a JSON Schema: the IDE uses one schema per file, and
  `package.json` already has npm's — a second is attached and never consulted.
- [x] **The emulator in the IDE.** A tool window with the live screen over VNC, the four buttons as
  buttons and arrow keys, clicks and drags as touches on emery and gabbro — confirmed by an app
  that logs its touches — screenshots, and stopping it.

Moved to Phase 2: Windows, which needs a machine with WSL to be verified on.

## Phase 2 — Advanced development experience (P1)

Done when testing across watches and finding bugs is quicker here than with a terminal, an external
emulator and a separate debugger.

- [x] Every platform side by side: `pebble screenshot --all-platforms` behind Capture Every
  Platform, and the newest screenshot of each in the emulator tool window's Platforms tab.
- Several live emulators at once, one per platform — waits on a VNC display number per emulator
  upstream.
- [x] GIF recording from the frame stream (`javax.imageio`, no dependency), up to a minute.
- [x] Battery, Bluetooth, clock, tap, tilt, compass, Timeline Quick View and content size from the
  emulator's tool window, over `pebble emu-*`.
- [x] Debugging C in the emulator: Debug builds with `--debug`, installs, and runs `pebble gdb` in
  the console — breakpoints, `bt` and `print` typed there. Since 0.8.0 in the IDE's debugger,
  below; the console stays behind an advanced setting.
- [x] Debugging Alloy: Debug builds with `--debug` and installs, and pebble-tool bridges the
  watch's JavaScript engine to a debugger — since 0.5.0 the IDE's own, below.
- [x] Calls in C to functions a target platform only stubs out, from each platform's `pebble.h`,
  wherever no `PBL_` condition chooses the platform — an inspection in every IDE, whatever the
  C file's language there.
- [x] `package.json`: a resource whose file is in `resources/` neither as named nor as a platform
  variant, and message keys no source uses.
- [x] PebbleKit JS typings for `src/pkjs`, the plugin's own, under External Libraries.
- **Windows.** Projects inside WSL: pebble-tool run in the distribution (`WSLDistribution`), its data
  directory and `pb-emulator.json` read through the distribution's paths, and the emulator's VNC
  reached over WSL's localhost forwarding.

## Phase 3 — Integrated tooling and automation (P2)

- [x] Alloy debugging in the IDE's debugger: an `XDebugProcess` over xsbug's protocol on port 5002,
  where pebble-tool looks for xsbug. Breakpoints — the JavaScript debugger's own where it is
  installed — stepping, pause, the stack, variables and globals, break on exceptions, and the
  app's logs; Stop logs out, and the app runs on.
- [x] Alloy expressions: Evaluate Expression and watches, breakpoint conditions, and breakpoints
  that log without stopping — compiled for the engine by the SDK's `xsc`, as Moddable's own
  xsbug-log does.
- [x] Memory: each platform's RAM, heap and resources from `pebble build`'s own report, under that
  platform in the Build window, a warning when the heap is short or the store would refuse the
  resources; and a word in the console when an app exits with memory it never freed.
- [x] Scripted test scenarios: `.pbtest` files of steps — install, buttons, touches, time,
  battery, Bluetooth, motion — and expected screens, run on each platform in the IDE's test
  runner, each screen compared with a reference screenshot: visual regression across platforms.
- [x] C debugging in the IDE's debugger: an `XDebugProcess` over gdb's machine interface, attached
  the way `pebble gdb` attaches once the app is installed. Breakpoints — CLion's own where it is
  installed — with conditions and logged expressions, stepping that keeps to the app's sources,
  pause, the stack, variables, Evaluate Expression and watches, setting values, and a stop where
  the app crashes; Stop detaches, and the app runs on.
- Release preparation: `.pbw` checks and store metadata.
- [x] Coding agents, part one: Pebble tools in the IDE's MCP server — the project, build, run,
  logs, screenshots, the emulator's buttons and settings, API lookup in the SDK — Build Project
  as `pebble build`, and a `pebble` Agent Skill the plugin adds to projects, also installable in
  Claude Code from this repository. Scenarios, tools and installation in [agents.md](agents.md).
- [x] Coding agents, part two: UI test scenarios run and their screens accepted as tools.
- [x] Coding agents, part three: the debugger as tools — start, breakpoints, wait, step, evaluate,
  stop — for C and Alloy alike, through the IDE's own debug session.

## Risks to settle early in Phase 1

Each of these is a short experiment against a real SDK before the feature that depends on it.

- **VNC — confirmed.** Frames arrive as raw 32-bit pixels and key events reach the buttons.
  Pointer events are touches on emery and gabbro. pebble-tool's `--vnc` uses
  display `:1` for every emulator, so only one can have it; the fix is a display number option
  upstream.
- **gdb and DAP — ruled out.** The SDK's gdb has no Python, so no `-i=dap`. C debugging speaks
  GDB/MI instead, which this gdb has.
- **CLion and a regenerated compile database.** Confirm that CLion reloads `compile_commands.json`
  when it changes. If it does not, a small optional module on CLion's API, in that one place.
- **C highlighting outside CLion.** Confirm the bundled TextMate grammar for C is enough to carry
  LSP4IJ's results in IDEA and WebStorm.
- **TypeScript and library roots.** Confirm the TypeScript service sees `.d.ts` files from library
  roots in Alloy `.ts` sources; if not, a generated `tsconfig.json`.
- **The name.** Pebble is a trademark, and the Marketplace refuses names that lean on one. The
  plugin's listed name is settled before the first upload.
