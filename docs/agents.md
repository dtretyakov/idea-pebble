# Coding agents and the IDE

How a coding agent (Claude Code, Codex, Junie, Copilot, Cursor) develops a Pebble app, where it
struggles, and what the plugin hands it through the IDE's MCP server and a skill. Since 0.9.0
the first part is built: the `projectTaskRunner`, the P0 tools and the skill; the rest is the plan
at the end. Facts were read from IntelliJ IDEA 2026.1 and 2026.2's bundled MCP server and from
the Claude Code documentation (October 2026).

## What an agent does, how often, and how much it hurts

Frequency is per working session on one app; pain is for an agent with a terminal and
pebble-tool but nothing from the IDE, 1 (none) to 5 (blocks it or makes it guess).

| # | Scenario | Frequency | Pain | Why it hurts today |
|---|----------|-----------|------|--------------------|
| 1 | Edit, build, fix compiler errors | every change, dozens | 3 | `pebble build` compiles every platform; one error is printed once per platform in waf's noise, and the memory report is buried in it |
| 2 | Write against the Pebble API: the right function, header, or Alloy module | every change | 4 | Models remember SDK 3 and invent APIs; whether a call exists or is a stub on aplite/chalk/emery is only in each platform's `pebble.h` |
| 3 | Run in the emulator and see that it works | every UI change, 10–30 | 5 | `pebble install --logs` never returns, a cold emulator refuses the first install, there is one VNC display for all; the agent juggles background processes and sleeps |
| 4 | Drive the app into a state: buttons, taps, time, battery, Bluetooth | most runs | 3 | `pebble emu-*` works but costs seconds a call, and the agent cannot tell what it pressed into without a screenshot after each |
| 5 | Read logs, find a crash or a leak | whenever something is off, several | 4 | The log is an endless stream; crash reports are bare PC/LR addresses; "Still allocated" is easy to miss |
| 6 | Check every platform: round chalk, black-and-white aplite, emery's size | per feature, 2–5 | 4 | Seven emulators, booted one after another, each with its own quirks; stub calls show only at run time |
| 7 | Keep screens from regressing: write and run UI scenarios | per feature and before a commit | 3 | Nothing in pebble-tool compares screenshots; the plugin's `.pbtest` runner is reachable only from the IDE's test runner |
| 8 | Debug: break in a handler, read variables, step | a few times a week, but when stuck | 5 | `pebble gdb` is an interactive terminal; agents drive it badly or not at all, and Alloy's xsbug is a GUI |
| 9 | Fit the memory and size budget (aplite: 24 KB) | per build that grows | 3 | The numbers are in the build's report, per platform, unexplained |
| 10 | Set up: SDK, project type, platforms, emulator state | once per project or machine | 2 | `pebble sdk`, `package.json` and `pb-emulator.json` are three places to look |
| 11 | `package.json`: resources and message keys | per resource change | 2 | A missing file or an unused key is found only by building or by the plugin's inspection |
| 12 | Install on a phone or watch | rarely; needs a person | 1 | Hardware and pairing are a person's job |

The order of value is roughly frequency × pain: 3, 2, 5, 1, 8, 6, 4, 7.

## What the IDE's MCP server already gives

The MCP server is bundled with IntelliJ IDEA, CLion and WebStorm 2026.1+ (plugin `com.intellij.mcpServer`, the same
`mcpToolset` extension point in all three), on
`http://127.0.0.1:64342` by default, with `/stream` (HTTP) and `/sse` endpoints. In 2026.2 it
offers `build_project`, `get_file_problems`, `lint_files`, `get_symbol_info`,
`get_run_configurations`, `execute_run_configuration`, `search_text`, `search_regex`,
`search_symbol`, `search_file`, `read_file`, `apply_patch`, `create_new_file`, `reformat_file`,
`rename_refactoring`, `list_directory_tree`, `open_file_in_editor`, `get_all_open_file_paths`,
`get_project_modules`, `get_project_dependencies`, `analyze_calls`, `git_status`,
`get_repositories` and `execute_terminal_command`. No debugger tools, and no tool result but text.

Some of these already do Pebble work, and should simply be made to:

- `get_file_problems` and `lint_files` run the plugin's inspections (stub calls per platform,
  `package.json` resources and keys) and, outside CLion, clangd's diagnostics through LSP4IJ.
- `get_run_configurations` and `execute_run_configuration` see the Pebble run configuration —
  but a run with logs never ends, so the call times out (scenario 3 needs its own tool).
- `build_project` does nothing for a Pebble project yet. A `projectTaskRunner` that runs
  `pebble build` for it would make the built-in tool, Build Project and Ctrl+F9 all build — the
  IntelliJ way, and no new tool.

## Tools the plugin adds

A `mcpServer.mcpToolset` extension (`McpToolset`, functions with `@McpTool` and
`@McpDescription`, the project from the call's coroutine context), loaded through an optional
dependency on `com.intellij.mcpServer` the way the LSP4IJ part is. The API is the same in 2026.1
and 2026.2. Every tool calls code the plugin already has; none reimplements pebble-tool.

| Tool | Scenarios | What it returns | Priority |
|------|-----------|-----------------|----------|
| `pebble_project` | 10, all | Project type, target and active platforms, SDK, UUID, worker, running emulators | P0 |
| `pebble_build(platforms?)` | 1, 9 | Success; problems per file and line, merged across platforms with the platforms each occurs on; RAM, heap and resources per platform | P0 |
| `pebble_api(symbol, platform?)` | 2 | The declaration and doc comment from the SDK's headers or Alloy typings, and the platforms where it is missing or a stub | P0 |
| `pebble_run(platform)` | 3 | Builds if needed, installs on the emulator, returns once pebble-tool says the app is installed; logs keep coming into the run's console | P0 |
| `pebble_logs(platform?, after?, level?, limit?)` | 5 | Log lines since a cursor, with crash addresses resolved to functions and lines, and leaked memory called out | P0 |
| `pebble_screenshot(platform \| all)` | 3, 6 | Paths of PNG files (tool results are text only), with each screen's size and shape | P0 |
| `pebble_emulator(steps, platform?, screenshot?)` | 4 | Steps in the `.pbtest` language — `press`, `tap`, `wait`, `time`, `battery`, `bluetooth`, `quick-view`, `motion`, `shake` — what each did, and a screenshot after | P0 |
| `pebble_ui_tests(file?, platforms?)` | 7 | Each screen's verdict, and the actual and diff images of a failure | P1 |
| `pebble_ui_test_accept(file, screen, platform)` | 7 | The new reference written | P1 |
| `pebble_debug_start(platform?)`, `pebble_debug_stop` | 8 | A debug session of the Pebble run configuration: gdb for C, xsbug's protocol for Alloy | P1 |
| `pebble_debug_breakpoint(file, line, condition?, log?, remove?)` | 8 | Verified, invalid with gdb's or the engine's reason, or pending until the debugger attaches | P1 |
| `pebble_debug_wait(timeout)` | 8 | Where it stopped, the stack, and the frame's variables one level deep | P1 |
| `pebble_debug_evaluate(expression, frame?)`, `pebble_debug_control(over \| into \| out \| resume \| pause)` | 8 | A value with its fields, or the next stop | P1 |
| `pebble_emulator_reset(kill \| wipe)` | 3 | `pebble kill`, waiting for the emulators to go (`--force` if they will not), then `pebble wipe` — never `wipe --everything`, which deletes the SDKs too | P2 |
| `pebble_sdk(list \| install \| activate, version?)` | 10 | The current, installed and installable SDKs; or pebble-tool's last words after installing or activating | P2 |

The `pebble_debug_*` tools work through `XDebuggerManager` and `XDebugSession`, not through gdb or
xsbug directly, so they serve both debuggers — and any other `XDebugProcess`. They are worth
offering to the platform for every language; until it has its own, they carry the plugin's prefix,
so a later tool of the platform's cannot clash with them.

Tools that act — install, input, debug — say so in `@McpToolHints`, so a client can ask before
calling them. None touches anything outside the project and its emulators.

## The skill

What tools cannot carry — the loop, the platforms' quirks, the SDK's idioms — goes in one Agent
Skill, `pebble` (a `SKILL.md` in the open format of agentskills.io, which Claude Code, Codex,
Junie, Copilot, Cursor and others read):

- The loop: `pebble_build` → `pebble_run` → `pebble_screenshot` → `pebble_logs`, and when to
  reach for `pebble_api` before writing a call.
- The platforms: round chalk (`PBL_IF_ROUND_ELSE`), black-and-white aplite and diorite
  (`PBL_IF_COLOR_ELSE`), touch on emery and gabbro only, aplite's 24 KB.
- C and Alloy idioms, and the SDK's documentation links.
- The same loop with plain pebble-tool, for an agent without the IDE: `pebble install --logs` in
  the background, `pebble screenshot --no-open`, `pebble emu-button`.

Under 500 lines; details in `references/` beside it, loaded only when needed.

## Installing it for agents

**The tools** come with the IntelliJ plugin: they register in the IDE's own MCP server, so there
is nothing else to install. The agent connects to that server once:

- Settings | Tools | MCP Server → Enable MCP Server → Auto-Configure, for Claude Code, Claude
  Desktop, Codex, Cursor, GitHub Copilot, Junie, VS Code or Windsurf (per user, or per project).
- Or by hand, with the configuration the same page copies, for example
  `claude mcp add --transport http idea http://127.0.0.1:64342/stream`.

**The skill**, three ways, from most to least automatic:

1. The plugin writes it: the new-project wizard adds it to new projects, and an action — "Add
   Pebble Skill for Agents" — to existing ones. It goes into two directories, which between them
   every agent below reads (from each agent's own documentation, October 2026):

   | Agent | Project skill directories it reads |
   |-------|------------------------------------|
   | Claude Code | `.claude/skills` |
   | Codex | `.agents/skills` |
   | Junie | `.junie/skills`, `.agents/skills` |
   | Cursor | `.agents/skills`, `.cursor/skills`, `.claude/skills` |
   | GitHub Copilot | `.github/skills`, `.claude/skills`, `.agents/skills` |

   So: `.agents/skills/pebble` and `.claude/skills/pebble`. Cursor and Copilot see the same
   skill twice, by the same name and with the same text. In version control, the whole team's
   agents get it.
2. A Claude Code plugin from this repository: a marketplace, `.claude-plugin/marketplace.json`,
   listing a plugin whose `skills/pebble/SKILL.md` is the same file.
   `/plugin install pebble --marketplace dtretyakov/idea-pebble` (Claude Code 2.1.275 and later),
   or `claude plugin marketplace add dtretyakov/idea-pebble`, then
   `claude plugin install pebble@idea-pebble`. Its version is the IDE plugin's. The plugin ships no `.mcp.json`: Auto-Configure
   already adds the IDE's server, and a second copy would only duplicate its tools.
3. By hand: the directory copied to `~/.agents/skills/pebble/` (Codex, Junie, Cursor, Copilot)
   or `~/.claude/skills/pebble/` (Claude Code).

## What building them showed

- One tool for the emulator rather than two. The UI test scenarios' language already covers
  buttons, touches and the emulator's settings, so `pebble_emulator` takes its steps: an agent
  that has learned one has learned the other.
- An agent has to see the screen to check its work, and the IDE's server returns text only: it
  turns a tool's result into MCP text content and nothing else, in 2026.1 and 2026.2. So the
  tools give paths to images, which agents open with their own image tools (Claude Code's Read,
  Codex's `view_image`). A watch's screen is 144 to 260 pixels across, too small for a model to
  read its text, so each screenshot has a `view` beside it — the same pixels as square blocks,
  about 600 pixels high, under `build/pebble-agent/` — and a failed UI test's `view` is the
  reference, the screen now and their difference side by side. The skill says to look after
  every visible change, and what to look for.
- `pebble_screenshot` saves where Capture Every Platform does, `screenshots/<platform>_<sdk>_<time>.png`,
  so what an agent looked at is in the emulator tool window's Platforms tab too.
- `pebble_run` uses a run configuration of its own (the user's are left alone), shows in the Run
  window like any run, and returns when pebble-tool prints `App install succeeded.`; a cold
  emulator that refuses the first install is tried once more, as the live tests do.
- `pebble_logs` reads every Pebble run's output — the user's runs too — from a buffer of the last
  5,000 lines, taken before the console's level filter, so each line keeps its level.
- `pebble_api` reads `pebble.h` for each target platform: its `//!` comments are the SDK's
  documentation, and a function defined as a macro that does nothing is a stub there. Suggestions
  rank the header's names by shared words, `snake_case` and `CamelCase` alike, then by edits.
- The MCP server calls a tool off the UI thread, and the tools need that thread free: saving
  documents and refreshing `build/` happen on it. A test that holds it waits forever, so the
  live test calls the tools from another thread, as an agent does.
- The Build Project runner is called off the UI thread, sometimes under a read lock: it saves
  documents and starts the build from the UI thread later, rather than waiting for it.
- `pebble_ui_tests` runs the Pebble UI Tests configuration, so the run is in the IDE's test
  runner as the user's would be, and reads each screen's verdict back from the service messages
  it gives the test runner.
- The IDE's server drops a Streamable HTTP session about 15 seconds after it starts unless the
  client has opened the standing event stream (a `GET` on the same URL). Agents do; a client of
  one's own must, or long tool calls fail with 404.
- The debugger tools read the session as the Debug window does — stack frames, values and their
  presentations through the platform's callbacks — so the same code reads gdb's and xsbug's.
  Whether a breakpoint could be set is shown only as its icon; the Pebble debuggers also record
  it in `BreakpointStates`, where `pebble_debug_breakpoint` reads it.
- `@JvmDefaultWithoutCompatibility` on the toolset: otherwise Kotlin writes bridges to
  `McpToolset` methods that only 2026.2 has, and 2026.1 cannot load the class.

## Order

1. Built in 0.9.0: `projectTaskRunner` for `build_project`, the P0 tools, and the skill with its
   install action and Claude Code marketplace.
2. Built in 0.9.0: UI tests as tools, `pebble_ui_tests` and `pebble_ui_test_accept`.
3. Built in 0.9.0: the `pebble_debug_*` tools, then the P2 tools, `pebble_emulator_reset` and
   `pebble_sdk`.

Each with the same tests as the rest of the plugin: the tools called in an `IdeTestCase`, and a
live test that has the MCP server answer a real client.
