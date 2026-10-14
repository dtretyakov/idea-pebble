# Coding agents and the IDE

How a coding agent (Claude Code, Codex, Junie, Copilot, Cursor) develops a Pebble app, where it
struggles, and what the plugin should hand it through the IDE's MCP server and a skill. A plan;
nothing here is built yet. Facts were read from IntelliJ IDEA 2026.1 and 2026.2's bundled MCP
server and from the Claude Code documentation (October 2026).

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

## Tools the plugin should add

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
| `pebble_emulator_input(press \| long_press \| tap, …)` | 4 | Done, through the RFB client, in milliseconds; optionally a screenshot after | P0 |
| `pebble_emulator_set(time \| battery \| bluetooth \| tilt \| tap \| quick_view \| content_size, …)` | 4 | Done, through `pebble emu-*` | P1 |
| `pebble_ui_tests(file?, platforms?)` | 7 | Each screen's verdict, and the actual and diff images of a failure | P1 |
| `pebble_ui_test_accept(file, screen, platform)` | 7 | The new reference written | P1 |
| `debug_start(platform?)`, `debug_stop` | 8 | A debug session of the Pebble run configuration: gdb for C, xsbug's protocol for Alloy | P1 |
| `debug_breakpoint(file, line, condition?, remove?)` | 8 | Verified or invalid, with gdb's or the engine's reason | P1 |
| `debug_wait(timeout)` | 8 | Where it stopped and why, the stack, and the top frame's variables | P1 |
| `debug_evaluate(expression, frame?)`, `debug_step(over \| into \| out)`, `debug_resume` | 8 | A value, or the next stop | P1 |
| `pebble_emulator_reset(kill \| wipe)` | 3 | Done; for an emulator that stopped booting | P2 |
| `pebble_sdk(list \| install \| activate)` | 10 | SDKs, or done | P2 |

The `debug_*` tools work through `XDebuggerManager` and `XDebugSession`, not through gdb or
xsbug directly, so they serve both debuggers — and any other `XDebugProcess`. They are worth
offering to the platform for every language.

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
   Pebble Skill for Agents" — to existing ones, into `.claude/skills/pebble/` and, by its
   format, wherever else the project's agents look. In version control, the whole team's agents
   get it.
2. A Claude Code plugin from this repository: a marketplace, `.claude-plugin/marketplace.json`,
   listing a plugin whose `skills/pebble/SKILL.md` is the same file.
   `claude plugin marketplace add dtretyakov/idea-pebble`, then
   `claude plugin install pebble@idea-pebble`. The plugin ships no `.mcp.json`: Auto-Configure
   already adds the IDE's server, and a second copy would only duplicate its tools.
3. By hand: the directory copied to `~/.claude/skills/pebble/`, or another agent's skills
   directory.

## Order

1. `projectTaskRunner` for `build_project`; the P0 tools; the skill and its install action.
2. UI tests and the emulator's settings as tools.
3. The `debug_*` tools.

Each with the same tests as the rest of the plugin: the tools called in an `IdeTestCase`, and a
live test that has the MCP server answer a real client.
