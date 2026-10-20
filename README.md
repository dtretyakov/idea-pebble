# Develop for Pebble

Build, run and deploy apps and watchfaces for Pebble smartwatches — in C or in Alloy (JavaScript and
TypeScript) — in IntelliJ IDEA, CLion and WebStorm.

The plugin drives the official [Pebble SDK](https://developer.repebble.com/sdk/) and pebble-tool
rather than replacing them: the build is `pebble build`, the emulator is the SDK's, and code
insight comes from the IDE's own engines fed with the SDK's headers and typings.

**Status: early, and working.** New projects, building, running in the emulator — whose screen
and buttons are in the IDE — or on a watch, logs, debugging Alloy and C in the IDE's debugger,
screenshots of every platform side by side, and C code insight for the platform you choose, with
checks for calls that platform only stubs out; and a release checked, then published to the Pebble
appstore with `pebble publish`. What is still to come is in the [roadmap](docs/roadmap.md).

## Getting started

1. Install **Develop for Pebble** from the JetBrains Marketplace: **Settings | Plugins**.
2. Open a folder whose `package.json` has a `pebble` section — or use **New Project | Pebble** once
   the SDK is set up.
3. Click **Set Up Pebble SDK** in the balloon the project opens with. It installs pebble-tool and the
   latest SDK in the background, and uv if you have none; anything that needs `sudo` or Homebrew is
   offered as a command to run in the terminal. **Tools | Pebble | Check Environment** shows the
   whole checklist.
4. Pick a platform beside the Run button and press Run. The emulator appears in the **Pebble
   Emulator** tool window; the app's logs in the Run console.

For C in IntelliJ IDEA or WebStorm, install the LSP4IJ plugin and clangd; CLion needs neither.

## Coding agents

The plugin adds Pebble tools to the IDE's own MCP server, so an agent such as Claude Code, Codex,
Junie or Copilot can build the app, run it in the emulator, read its logs, press its buttons,
look at its screen and check the SDK's API.

1. Connect the agent to the IDE: **Settings | Tools | MCP Server**, enable the server, then
   **Auto-Configure** for your agent.
2. Give the agent the `pebble` skill, which tells it how to use those tools. New projects have it
   already. For an existing project, use **Tools | Add Pebble Skill for Agents**, which writes it
   to `.agents/skills` and `.claude/skills`, where Claude Code, Codex, Junie, Cursor and Copilot
   look. For Claude Code in every project, install it from this repository:

   ```
   /plugin install pebble --marketplace dtretyakov/idea-pebble
   ```

   On Claude Code before 2.1.275, run `/plugin marketplace add dtretyakov/idea-pebble`, then
   `/plugin install pebble@idea-pebble`.

[Coding agents](docs/agents.md) has the details.

## Windows

The Pebble SDK does not run on Windows itself, only inside WSL, and the plugin runs it there: the
IDE stays on Windows, while pebble-tool, the build, the emulator, gdb and clangd run in a WSL
distribution. The emulator's screen, logs and debuggers are in the IDE as on a Mac or Linux.

1. No WSL yet: the balloon offers **Install Ubuntu in WSL**, which runs
   `wsl --install -d Ubuntu-24.04` in the IDE's terminal (22.04 is too old for the emulator).
   Windows may restart, and Ubuntu asks for a user name and password once.
2. Then one command in a terminal of that distribution, which the balloon also runs for you: the
   SDK's own list of Ubuntu packages — Node.js and npm, without which `pebble sdk install` fails,
   and the emulator's libraries — after `sudo` asks for the password.
3. Then **Set Up Pebble SDK**, as everywhere else.

Projects can be in the distribution — open them as `\\wsl.localhost\Ubuntu-24.04\home\…`, where
builds are several times faster — or on a Windows drive, which the distribution sees as `/mnt/c/…`.
With several distributions, **Settings | Languages & Frameworks | Pebble | WSL** chooses one; by
default it is the one the project is in, else WSL's default. In CLion, add a WSL toolchain so that
its C engine reads the compile database the SDK writes with Linux paths; IDEA and WebStorm run
clangd in the distribution (`sudo apt install clangd`). Installing on a watch over `--adb` needs
adb inside WSL, which sees no USB devices without usbipd-win.

## Documentation

- [Architecture](docs/architecture.md) — how the plugin is put together, and why.
- [Roadmap](docs/roadmap.md) — what comes in which phase.
- [Notes on the Pebble SDK](docs/pebble-sdk-notes.md) — what a client of pebble-tool needs to know.
- [Coding agents](docs/agents.md) — the MCP tools and the skill, and why they are what they are.
- [Developing](docs/developing.md) — building and testing the plugin.

## License

[Apache 2.0](LICENSE).

## Trademarks

Pebble is a trademark of Core Devices LLC. Develop for Pebble is an independent, open-source
project, not affiliated with or endorsed by Core Devices.
