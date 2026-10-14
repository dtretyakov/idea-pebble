# Without the IDE: pebble-tool on the command line

The same loop as the MCP tools, with `pebble` (pebble-tool 5) in the project's directory. Every
emulator command takes `--emulator <platform>`. Add `--vnc` so it uses the same emulator the IDE
shows.

| Step | Command |
|------|---------|
| What the app is | `package.json`'s `pebble` section: `projectType`, `targetPlatforms` |
| Build | `pebble build`; a debug build is `pebble build --debug` |
| Run | `pebble install --emulator emery --vnc`. It returns once the app is installed. A cold emulator may refuse the first attempt; run it again. |
| Logs | `pebble logs --emulator emery --vnc` never returns: run it in the background with its output to a file, and read the file |
| Screenshot | `pebble screenshot --emulator emery --vnc --no-open shot.png`; every platform: `pebble screenshot --all-platforms --no-open` (into `screenshots/`) |
| Buttons | `pebble emu-button click select --emulator emery --vnc`; `back`, `up` and `down` likewise |
| Time, battery, Bluetooth | `pebble emu-set-time 10:10:00`, `pebble emu-battery --percent 20`, `pebble emu-bt-connection --connected no` |
| Motion | `pebble emu-accel tilt-left`, `pebble emu-tap --direction x+` |
| A stuck emulator | `pebble kill`; if it will not boot again, `pebble wipe` |
| SDK | `pebble sdk list`, `pebble sdk install latest` |

API lookups without `pebble_api`: the headers are in
`~/.local/share/pebble-sdk/SDKs/current/sdk-core/pebble/<platform>/include/pebble.h`. Their `//!`
comments are the documentation. A function written as `#define name(...) (0)` there is a stub on
that platform. Alloy's typings are in `.../SDKs/current/toolchain/moddable/typings/`.
