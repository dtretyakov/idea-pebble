# Pebble for JetBrains IDEs

Build, run and deploy apps and watchfaces for Pebble smartwatches — in C or in Alloy (JavaScript and
TypeScript) — in IntelliJ IDEA, CLion and WebStorm.

The plugin drives the official [Pebble SDK](https://developer.repebble.com/sdk/) and pebble-tool
rather than replacing them: the build is `pebble build`, the emulator is the SDK's, and code
insight comes from the IDE's own engines fed with the SDK's headers and typings.

**Status: early.** This version finds pebble-tool and the SDK, checks that everything a build
needs is installed, and installs or activates an SDK when it is missing. Code insight, building,
the emulator inside the IDE, installing on a watch and logs are next — see the
[roadmap](docs/roadmap.md).

## Getting started

1. Install pebble-tool: `uv tool install pebble-tool`.
2. Open a folder whose `package.json` has a `pebble` section — `pebble new-project` makes one.
3. If anything is missing the editor says so and offers to fix it. **Tools | Pebble | Check
   Environment** shows the whole checklist.

On Windows the Pebble SDK runs only inside WSL, which this version does not drive yet.

## Documentation

- [Architecture](docs/architecture.md) — how the plugin is put together, and why.
- [Roadmap](docs/roadmap.md) — what comes in which phase.
- [Notes on the Pebble SDK](docs/pebble-sdk-notes.md) — what a client of pebble-tool needs to know.
- [Developing](docs/developing.md) — building and testing the plugin.

## License

[Apache 2.0](LICENSE).
