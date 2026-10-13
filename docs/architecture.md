# Architecture

How the plugin is put together, and why it is put together that way.

## The idea

The plugin carries no model of C, JavaScript or the Pebble API, and no build system or emulator of
its own. Everything that has to understand a Pebble app already exists — in the Pebble SDK, in
pebble-tool, or in the IDE — and the plugin's job is to find those things and connect them:

| Concern | What does the work | How the plugin talks to it |
|---|---|---|
| SDKs, new projects | `pebble` (pebble-tool): `sdk install/activate`, `new-project` | command line; state is read from the SDK's own files, never parsed out of prose |
| C code insight | CLion's own engine; clangd in IDEA and WebStorm | `pebble compile-commands` writes `compile_commands.json` for the active platform; clangd through [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) |
| Alloy code insight | the IDE's own JavaScript and TypeScript support | the SDK's `.d.ts` typings as library roots |
| Building | `pebble build` (waf and gcc) | command line, output parsed into the Build tool window |
| Running, installing, logs | `pebble install --emulator P \| --phone IP \| --cloudpebble \| --adb --logs` | the run configuration's process and console |
| The emulator's screen | QEMU's VNC server (`pebble install --vnc`) | a small RFB client: frames in, buttons and touches out |
| Emulator controls | `pebble emu-*`, `screenshot`, `kill`, `wipe` | command line |
| Debugging C | `arm-none-eabi-gdb` from the SDK's toolchain | not yet: the SDK's gdb has no Python, so no DAP (see the roadmap) |
| Debugging Alloy | `xsbug` from the SDK's toolchain | started by `pebble install` at first; later a debug process of our own over its protocol |

Nothing from the SDK is redistributed: the plugin finds what the user installed with pebble-tool
and runs it.

That is the whole design argument. The SDK changes — new platforms, new APIs, new project types —
and anything this plugin reimplemented would be behind from the day it shipped. pebble-tool *is*
the build, so it is never behind.

## Decisions that keep it cheap to maintain

**One archive for IDEA, CLion and WebStorm.** The plugin depends on `com.intellij.modules.platform`
— and, once clangd arrives, LSP4IJ — and is compiled against no IDE-specific API. CLion opens a `compile_commands.json` natively, so C
support there is the plugin keeping that file current; in IDEA and WebStorm, which have no C
engine, the same file feeds clangd. The clangd server declines to start where CLion's engine is
present, which is a runtime check on an installed plugin id rather than a compile-time dependency.
`./gradlew verifyPlugin -PverifyClion` and `-PverifyWebStorm` hold the claim to account.

**Only stable protocols.** RFB is an RFC. `package.json`, `manifest.json`, `pb-emulator.json` and
`compile_commands.json` are formats other tools already depend on. The Pebble Protocol itself is
not implemented here: anything that talks to a watch goes through pebble-tool.

**The SDK's templates, not copies of them.** The new project wizard runs `pebble new-project`, so a
template the SDK changes is a template the wizard changes.

**One active platform per project.** A Pebble app is built for each of its target platforms, but a
developer looks at one at a time. That one choice — in the run widget, like a device selector —
is what the build, the compile database (so `#if defined(PBL_COLOR)` resolves the way the chosen
watch will see it), the run configuration and the emulator all read.

**Fixes go upstream.** pebble-tool is open source. Where it lacks something an IDE needs — a VNC
display number of its own, machine-readable output — the change is proposed there rather than
worked around here, and each workaround that does exist is listed in
[Notes on the Pebble SDK](pebble-sdk-notes.md) with a live test that turns red the day it is no
longer needed.

## Layout

```
sdk/        finding pebble-tool, its data directory, SDKs, platforms, clangd — no IDE API
project/    what a Pebble project is, settings, the active platform, the environment checklist,
            the compile database, new projects, running pebble-tool
build/      pebble build into the Build tool window, and reading its output
run/        the run configuration, the before-launch build, the log console and its links
emulator/   pebble-tool's record of running emulators, the RFB client, the tool window
lsp/        clangd through LSP4IJ, outside CLion — loaded only where LSP4IJ is installed
library/    SDK headers and Alloy typings under External Libraries
ui/         settings, the platform selector, the missing SDK banner, the new project wizard
```

`sdk/` has no IntelliJ API in it on purpose: it is a set of functions of a directory tree, which is
what makes it testable without an IDE, and what will let the live tests compare it against what
pebble-tool itself reports.
