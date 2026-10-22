# Security

## Reporting a vulnerability

Report privately, not as a public issue: open a
[security advisory](https://github.com/dtretyakov/idea-pebble/security/advisories/new), or email
<dtretyakov@gmail.com>.

Please include what an attacker would be able to do and the smallest steps that show it. You should
get a first reply within a week. This is a single-maintainer project, so a fix may take longer than
that, and you will be told where it stands rather than left waiting.

## What this plugin touches

Worth knowing when judging whether something is a vulnerability at all:

* **It goes on the network only when asked, and twice at most.** There is no telemetry, no
  analytics and no update check. **Set Up Pebble SDK** — a button, never a startup task — downloads
  [uv](https://github.com/astral-sh/uv) from its GitHub release when there is none on the machine,
  at a version fixed in the plugin, and installs it only if it matches the SHA-256 also fixed in
  the plugin. The list of SDK releases comes from `sdk.repebble.com`. Everything else is fetched by
  the tools the plugin runs, not by the plugin: uv installs pebble-tool from PyPI, and pebble-tool
  installs the SDK.
* **It runs nothing that needs `sudo` or Homebrew.** Those commands — Node.js, the SDK's Linux
  packages, Ubuntu in WSL — open in the IDE's terminal for the user to read and run.
* **It bundles nothing of Core Devices'.** pebble-tool, the SDK, its headers and typings, the
  emulator and gdb are all read from what Set Up installed, or from what the user set in the
  settings.
* **It runs pebble-tool, the SDK's programs, uv, clangd and, on Windows, `wsl.exe`**, as argument
  lists. On Windows the SDK runs inside a WSL distribution, and the plugin passes commands to it
  through `wsl.exe`.
* **Its sockets are on loopback.** It reads the emulator's screen over VNC on `127.0.0.1`; gdb and
  pebble-tool, which it runs, reach the emulator there too. To debug an Alloy app it listens on a
  loopback port for the SDK's debugger to connect to; under WSL's default networking a small relay
  inside the distribution, bound to its own `127.0.0.1`, carries that connection across.
* **Its tools for coding agents are on the IDE's own MCP server**, which the user enables in
  **Settings | Tools | MCP Server** and which is off until then. Those tools can do what the plugin's
  actions do: build, run in the emulator, take screenshots, debug.
* **It writes** uv into `<JetBrains shared data directory>/pebble/uv`; the compile database and
  build output under the project; screenshots and UI test references where the user chose or
  under the project; and, when asked, the `pebble` agent skill into the project's `.agents/skills`
  and `.claude/skills`.
* **It stores no secret.** Publishing to the Pebble appstore runs `pebble publish` in the terminal,
  where pebble-tool handles the login itself.

## Supported versions

The most recent release. Fixes go out as a new version through the Marketplace.
