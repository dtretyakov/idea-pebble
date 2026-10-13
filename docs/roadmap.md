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

1. **Project and SDK.** Install, activate and remove SDKs from Settings. New Project wizard over
   `pebble new-project` — `newProjectWizard.generator` in IDEA, `directoryProjectGenerator` in
   CLion and WebStorm. A JSON Schema for `package.json`'s `pebble` section
   (`JsonSchemaProviderFactory`): completion and validation of project configuration with no model
   of our own. A warning when `targetPlatforms` asks for Alloy on a platform without it.
2. **Active platform.** A selector in the run widget, stored in project settings, read by
   everything below.
3. **C code insight.** `pebble compile-commands --platform <active>` after configure and on a
   platform switch. CLion: its own engine. IDEA and WebStorm: clangd via LSP4IJ, with
   `--query-driver` pointing at the SDK's `arm-none-eabi-gcc` so system headers resolve. SDK
   headers under External Libraries (`AdditionalLibraryRootsProvider`).
4. **Alloy code insight.** `toolchain/moddable/typings` as library roots, for the IDE's own
   JavaScript and TypeScript support.
5. **Build.** `pebble build` into the Build tool window (`BuildViewManager`), gcc and waf
   diagnostics as file messages with navigation.
6. **Run.** A Pebble run configuration (`configurationType`, `runConfigurationProducer`): target
   Emulator, Phone (IP), CloudPebble or ADB; logs and debug build as options; build before
   launch. The run process is `pebble install … --logs`, so the console *is* the log, Stop stops
   it and Rerun rebuilds and reinstalls.
7. **The emulator in the IDE.** A tool window showing the emulator's screen from QEMU's VNC
   server, live; Back, Up, Select, Down as buttons and keys; touch on emery and gabbro; screenshot
   to PNG from the frame already on screen; stop and wipe.
8. **Physical watches.** Reachability of the phone's Developer Connection before installing,
   pebble-tool's own error when installing fails, screenshots through `pebble screenshot --phone`.
9. **Logs.** Levels recovered from pebble-tool's colours, a level filter, `file.c:123>` as a link
   to the source (`ConsoleFilterProvider`), and saving a log to a file.
10. **Windows.** Projects inside WSL, with pebble-tool run in the distribution.

## Phase 2 — Advanced development experience (P1)

Done when testing across watches and finding bugs is quicker here than with a terminal, an external
emulator and a separate debugger.

- Several emulators at once, one per platform, side by side; screenshot comparison across them.
- GIF recording from the frame stream (`javax.imageio`, no dependency).
- Battery, Bluetooth, time, accelerometer and compass controls over `pebble emu-*`.
- Debugging C in the emulator: breakpoints, stack and variables in the IDE's own debugger through
  DAP, if the SDK's gdb speaks it; otherwise CLion's Remote GDB configuration in CLion.
- Debugging Alloy: a debug run that starts xsbug the way `pebble install` does.
- Inspections: API not available on a target platform (from that platform's `pebble.h`), resources
  declared in `package.json` and missing on disk, message keys that do not match their use.
- PebbleKit JS typings for `src/pkjs`.

## Phase 3 — Integrated tooling and automation (P2)

- Alloy debugging in the IDE's debugger: an `XDebugProcess` over xsbug's protocol on port 5002.
- Memory: sizes from the app's ELF, heap from its logs.
- Scripted test scenarios: `emu-*` inputs and screenshots, run as tests.
- Visual regression across platforms.
- Release preparation: `.pbw` checks and store metadata.

## Risks to settle early in Phase 1

Each of these is a short experiment against a real SDK before the feature that depends on it.

- **VNC.** pebble-tool's `--vnc` uses display `:1` (port 5901) for every emulator, so only one can
  have it. Confirm that VNC key events reach the Pebble buttons and pointer events act as touch.
  The fallback is the QEMU monitor's `screendump` with `pebble emu-button`; the real fix is a
  display number option in pebble-tool.
- **gdb and DAP.** `gdb -i=dap` needs GDB 14 or later built with Python. If the SDK's
  `arm-none-eabi-gdb` lacks it, C debugging uses CLion's Remote GDB in CLion and the terminal
  elsewhere.
- **CLion and a regenerated compile database.** Confirm that CLion reloads `compile_commands.json`
  when it changes. If it does not, a small optional module on CLion's API, in that one place.
- **C highlighting outside CLion.** Confirm the bundled TextMate grammar for C is enough to carry
  LSP4IJ's results in IDEA and WebStorm.
- **TypeScript and library roots.** Confirm the TypeScript service sees `.d.ts` files from library
  roots in Alloy `.ts` sources; if not, a generated `tsconfig.json`.
- **The name.** Pebble is a trademark, and the Marketplace refuses names that lean on one. The
  plugin's listed name is settled before the first upload.
