# Test fixtures

`fixture-c` and `fixture-alloy` are what `pebble new-project --simple --javascript` and
`pebble new-project --alloy` produce, taken from pebble-tool's own templates (MIT, © Pebble
Technology) with the placeholders filled in. They are real projects: `pebble build` builds them,
and `./gradlew runIdeWithFixture` opens a copy of the first.

Kept as the SDK writes them rather than trimmed, so a change in what the plugin reads from a
project is tested against what users actually have on disk.
