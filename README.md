# Pebble for JetBrains IDEs

Build, run and deploy apps and watchfaces for Pebble smartwatches — in C or in Alloy (JavaScript and
TypeScript) — in IntelliJ IDEA, CLion and WebStorm.

The plugin drives the official [Pebble SDK](https://developer.repebble.com/sdk/) and pebble-tool
rather than replacing them: the build is `pebble build`, the emulator is the SDK's, and code
insight comes from the IDE's own engines fed with the SDK's headers and typings.

**Status: early, and working.** New projects, building, running in the emulator — whose screen
and buttons are in the IDE — or on a watch, logs, debugging Alloy and C in the IDE's debugger,
screenshots of every platform side by side, and C code insight for the platform you choose, with
checks for calls that platform only stubs out. What is still to come is in the
[roadmap](docs/roadmap.md).

## Getting started

1. Install pebble-tool: `uv tool install pebble-tool`.
2. **New Project | Pebble**, or open a folder whose `package.json` has a `pebble` section.
3. If anything is missing the editor says so and offers to fix it. **Tools | Pebble | Check
   Environment** shows the whole checklist.
4. Pick a platform beside the Run button and press Run. The emulator appears in the **Pebble
   Emulator** tool window; the app's logs in the Run console.

For C in IntelliJ IDEA or WebStorm, install the LSP4IJ plugin and clangd; CLion needs neither.

On Windows the Pebble SDK runs only inside WSL, which this version does not drive yet.

## Documentation

- [Architecture](docs/architecture.md) — how the plugin is put together, and why.
- [Roadmap](docs/roadmap.md) — what comes in which phase.
- [Notes on the Pebble SDK](docs/pebble-sdk-notes.md) — what a client of pebble-tool needs to know.
- [Developing](docs/developing.md) — building and testing the plugin.

## License

[Apache 2.0](LICENSE).
