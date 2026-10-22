# Test fixtures

`fixture-c` and `fixture-alloy` are what `pebble new-project --simple --javascript` and
`pebble new-project --alloy` produce, taken from pebble-tool's own templates (MIT, © Pebble
Technology) with the placeholders filled in. They are real projects: `pebble build` builds them,
and `./gradlew runIdeWithFixture` opens a copy of the first.

Kept as the SDK writes them rather than trimmed, so a change in what the plugin reads from a
project is tested against what users actually have on disk.

The others are recordings and excerpts, each for the tests of one part of the plugin:

- `agent/pebble-excerpt.h` — a few declarations of the SDK's `pebble.h`, as `pebble_api` reads
  them: `//!` comments, enums, and a function defined as a stub.
- `gdb/` — a session with the SDK's gdb over its machine interface, and `objdump -h` of an app,
  recorded from the emulator, for the C debugger.
- `xsbug/` — xsbug's protocol as pebble-tool relays it from the watch, recorded, for the Alloy
  debugger.
