# Notes on the Pebble SDK

What pebble-tool and the SDK do that a client needs to know, gathered from pebble-tool's source
(5.0.40) and the SDK documentation at <https://developer.repebble.com>. Most of it is documented
nowhere else.

## Installing

```bash
uv tool install pebble-tool      # puts `pebble` in ~/.local/bin, or $UV_TOOL_BIN_DIR / $XDG_BIN_HOME
pebble sdk install latest
```

pebble-tool needs Python 3.10–3.13. Builds that use PebbleKit JS or npm dependencies need Node.js.
The emulator on Linux needs glibc 2.38 or later. Windows is supported only inside WSL.

`pebble` asks no questions on the way: the analytics prompt of the original SDK is disabled, so
every command the plugin runs can run without a terminal.

On Python 3.12 and later every `pebble` command starts by printing a screenful of `SyntaxWarning`s
from libpebble2's docstrings to stderr. The plugin runs pebble-tool with `PYTHONWARNINGS=ignore`.

## Where things are

The data directory is `get_persist_dir()` in pebble-tool:

| System | Directory |
|---|---|
| macOS | `~/Library/Application Support/Pebble SDK` |
| Linux | `~/.pebble-sdk` if it exists, else `$XDG_DATA_HOME/pebble-sdk` (`~/.local/share/pebble-sdk`) |

Inside it:

```
SDKs/<version>/sdk-core/manifest.json            {"version": "4.33.1", "channel": …}
SDKs/<version>/sdk-core/pebble/<platform>/include/pebble.h
SDKs/<version>/sdk-core/pebble/<platform>/qemu/   firmware images, the debug ELF
SDKs/<version>/sdk-core/pebble/waf
SDKs/<version>/toolchain/arm-none-eabi/bin/       gcc, gdb
SDKs/<version>/toolchain/bin/                     qemu-pebble
SDKs/<version>/toolchain/moddable/typings/        Alloy's .d.ts files
SDKs/<version>/toolchain/moddable-tools/          mcrun, xsbug
SDKs/current -> SDKs/<version>                    the active SDK, a symbolic link
<version>/<platform>/                             the emulator's persistent state
```

pebble-tool lists SDKs exactly as `PebbleSdks.scan` does: every directory under `SDKs` with a
readable `sdk-core/manifest.json`, skipping the `current` link. `pebble sdk list` prints the same
thing as prose, so the plugin reads the files instead — and reads `pebble sdk list` only for what
the files cannot say, the versions available to install: the lines under `Available SDKs:` (or
`Available SDKs (<channel> channel):`), already without the installed ones.

## Projects

A directory is a project when its `package.json` has a `pebble` key, and it must also have a
`wscript`. The `pebble` section carries `projectType` (`native` by default; `moddable` for Alloy;
`package`; `rocky`), `targetPlatforms` (every platform when absent), `watchapp.watchface`,
`uuid`, `displayName`, `messageKeys`, `resources` and `capabilities`.

Sources: `src/c/` for C, `src/pkjs/` for PebbleKit JS on the phone, `src/embeddedjs/` for Alloy
(with its own `manifest.json`), `worker_src/c/` for a background worker.

## Platforms

| Platform | Watches | Screen | Alloy |
|---|---|---|---|
| aplite | Pebble Classic, Steel | 144×168, black and white | no |
| basalt | Pebble Time, Time Steel | 144×168, 64 colours | no |
| chalk | Pebble Time Round | 180×180 round, 64 colours | no |
| diorite | Pebble 2 | 144×168, black and white | no |
| flint | Pebble 2 Duo | 144×168, black and white | no |
| emery | Pebble Time 2 | 200×228, 64 colours, touch | yes |
| gabbro | Pebble Round 2 | 260×260 round, 64 colours, touch | yes |

## Resources and message keys

`resources.media` entries are `{"type", "name", "file"}`, with `file` relative to the project's
`resources/` directory. waf looks for the file by its name and for variants of it tagged for some
platforms, `images/logo~color.png` or `images/logo~bw~round.png` for `images/logo.png`, and takes the
variant whose tags best match the platform being built (`find_most_specific_filename`). A
resource whose file exists neither way does not fail with a message: waf ends in a Python
`FileNotFoundError` traceback naming the path.

`messageKeys` is a list of names — `"KEY[4]"` reserves four numbers under one name — or, in older
projects, an object from name to number. C sees each as `MESSAGE_KEY_<name>` from the generated
`message_keys.auto.h`; PebbleKit JS and Clay's configuration use the bare name. The template
`pebble new-project` writes declares a key, `dummy`, that its code never uses.

## Platform differences in the API

Every platform's `pebble.h` declares the whole API. What a platform's watches lack is a macro that
ignores its arguments — 60 of them on aplite, 44 on basalt, chalk and diorite, none on flint,
emery and gabbro in SDK 4.33.1:

```c
#define speaker_play_notes(...) (0)
#define health_service_register_metric_alert(...) (NULL)
#define app_glance_reload(...) do {} while(0)
#define persist_get_max_size(...) (4096)
```

So a call compiles on every platform and does nothing on those — the build never says. Most stubs
are `(0)`, `(false)`, `(NULL)` or an empty statement; a few return the platform's real answer, like
`persist_get_max_size` above, and calling those is no mistake.

The headers and the capability macros disagree in one place: flint's `pebble.h` declares the
speaker and touch functions, while `PBL_SPEAKER` and `PBL_TOUCH` are defined only for emery and
gabbro (`pebble_sdk_platform.py`). The plugin goes by the headers.

Code chooses a platform with the macros waf defines per platform — `PBL_PLATFORM_<NAME>`,
`PBL_COLOR`/`PBL_BW`, `PBL_ROUND`/`PBL_RECT`, `PBL_HEALTH`, `PBL_MICROPHONE`, `PBL_COMPASS`,
`PBL_SMARTSTRAP`, `PBL_SPEAKER`, `PBL_TOUCH`, `PBL_RGB_BACKLIGHT`, `PBL_DISPLAY_WIDTH` and
`_HEIGHT` — and with `pebble.h`'s `PBL_IF_<capability>_ELSE(a, b)` and `PBL_PLATFORM_SWITCH`.

## Building

`pebble build` runs `waf configure` and `waf build` from the SDK. `--debug` adds `-O0 -DPBL_DEBUG`.
The bundle is `build/<directory name>.pbw` and each platform's ELF is
`build/<platform>/pebble-app.elf`. Alloy sources are compiled per platform by `mcrun` first.

Diagnostics are gcc's own, with paths relative to `build/`, and `-Werror` is on:

```
../src/c/main.c:6:3: error: implicit declaration of function 'foo' [-Wimplicit-function-declaration]
```

waf stops at the first platform that fails, so one build reports one platform's errors.

After linking each platform waf prints its memory (`memory_reports.py`):

```
-------------------------------------------------------
EMERY APP MEMORY USAGE
Total size of resources:        4092 bytes / 256.0KB
Total footprint in RAM:         220 bytes / 128.0KB
Free RAM available (heap):      130852 bytes
-------------------------------------------------------
```

The RAM limit is the platform's memory for an app (24 KB on aplite, 64 KB on basalt, chalk,
diorite and flint, 128 KB on emery and gabbro); the resource limit is the app store's, and a
build over it says `WARNING: Your … app resources are too large`. A worker's block says `WORKER`.
Alloy apps report the same.

When an app exits — closed, or replaced by an install — the firmware logs its heap in the app's
log: `Heap Usage for App <Fixture C>: Total Size <130720B> Used <128B> Still allocated <104B>`.
What is still allocated was never freed; an allocation takes four bytes more than asked.

`pebble compile-commands --platform P` writes `compile_commands.json` from the waf cache, with the
absolute path of the SDK's `arm-none-eabi-gcc` — spelled through the `SDKs/current` link. It
needs the waf cache for that platform (`build/c4che/P_cache.py`), so it fails before the first
build. clangd reads the file cleanly when started with `--query-driver` matching that compiler,
which lets it ask gcc for newlib's headers; the glob has to match the `current` spelling, since
clangd compares it with the path as text. `pebble sdk include-path P` prints the directory
holding that platform's `pebble.h`.

## The emulator

`pebble install --emulator P` starts `qemu-pebble` and `pypkjs` in the background and records them
in `pb-emulator.json` in the system temporary directory, keyed by platform and SDK version: process
ids and the ports of QEMU's Pebble serial, gdb stub, monitor and VNC server, and pypkjs's
WebSocket. That file is how a second client finds a running emulator.

By default QEMU opens an SDL window. `--vnc` starts it with `-vnc :1` — port 5901 for every
emulator, so only one can have it at a time — and without the window. It also starts websockify
on port 6080 for a browser client.

Starting an emulator with `--vnc` stops any other emulator that has the display, so switching
platforms needs nothing from a client: the new one simply takes port 5901.

Every command that names an emulator — `install`, `logs`, `screenshot`, `emu-*` — has to say
`--vnc` if the emulator was started with it. pebble-tool restarts an emulator whose VNC setting
differs from the command's (`ManagedEmulatorTransport._find_ports`), so a bare
`pebble logs --emulator emery` in a terminal kills the emulator the IDE is showing and opens one in
a window of its own.

QEMU's VNC server needs no authentication and offers 32-bit true colour. The framebuffer is wider
than the watch's screen — rounded up to a multiple of 16 pixels — with the screen at the top left
and black beside it; basalt alone pads both ways and puts its screen two pixels in:

| Platform | Framebuffer | Screen at |
|---|---|---|
| aplite, diorite, flint | 144×168 | 0, 0 |
| basalt | 160×172 | 2, 2 |
| chalk | 192×180 | 0, 0 |
| emery | 208×228 | 0, 0 |
| gabbro | 272×260 | 0, 0 |

VNC pointer events are the touch panel on emery and gabbro: button 1 down is Touchdown, a move
while it is held a PositionUpdate, release Liftoff. QEMU stretches the pointer over the whole
framebuffer, so the watch sees x scaled by screen width over framebuffer width — x 100 of 208 is
x 96 on emery's 200 — and a client stretches the other way to touch where it means to.
 Key events are
the watch's buttons, as in the SDL window: Left is Back, Up is Up, Right is Select, Down is Down.

A first `pebble install --emulator` can fail with `[Errno 111] Connection refused` while the
emulator is in fact starting: pebble-tool tries pypkjs's WebSocket ten times, half a second
apart, and a cold start on a slow machine takes longer than that. The next run finds the emulator
up. The plugin says so after the error; a longer wait upstream is the fix.

pypkjs listens on `("", port)`, which gevent opens as IPv6. On a host without IPv6 — some
containers — it fails with `Address family not supported` and every `pebble install --emulator`
ends in `Connection refused`, while QEMU itself runs. An upstream fix to bind `0.0.0.0` is the
remedy.

`pebble screenshot --all-platforms --no-open` takes one screenshot per target platform: it starts
each platform's emulator in turn — without `--vnc`, so an emulator started with it is stopped
first — installs the built app, sets the clock to 10:10, and writes
`screenshots/<platform>_<version>_<yyyyMMdd-HHmmss>.png` at the watch's own resolution, the
framebuffer's padding cropped. Each line `=== Capturing <platform> ===` starts a platform; two
platforms take about half a minute. It needs no display. `--gif-all-platforms` does the same as an
animated GIF, but wipes each emulator's persistent state on the way, so the plugin does not offer
it.

Each emulator input is a command of its own, so a test scenario can be written in them:
`emu-button click|push|release <back|up|select|down> --duration <ms>` presses the buttons through
QEMU's serial (no screen needed); `emu-set-time HH:MM:SS`, `emu-battery --percent N [--charging]`,
`emu-bt-connection --connected yes|no`, `emu-accel <tilt-left|…>`, `emu-tap --direction <x+|…>`,
`emu-set-timeline-quick-view on|off`, and health values (`emu-steps`, `emu-heart-rate`, …). Touch
has no command: it is a VNC pointer event. `pebble screenshot --no-open <file>` saves the screen
at the watch's own size; the same app gives the same pixels run after run.

## Watches

`--phone IP` connects to the Developer Connection of the Pebble phone app on `ws://IP:9000`;
`--cloudpebble` goes through the cloud after `pebble login`; `--adb` forwards over adb.
`pebble logs`, `pebble screenshot` and `pebble install --logs` work over any of them.

## Logs

```
[14:02:11] main.c:42> Hello
[14:02:11] pkjs> Hello from the phone
```

The level is not in the text: pebble-tool shows it only as a colour (`PebbleLogPrinter.colour_scheme`:
error `\e[41m\e[1m\e[37m`, warning `\e[1m\e[31m`, info none, debug `\e[35m`, verbose `\e[36m`,
PebbleKit JS none), and only when its output is
a terminal. `pebble logs` has `--color` to force it; `pebble install --logs` has not, so the plugin
runs it on a pseudo-terminal and lets the console render the colours.

Crash reports name the PC and LR symbolised from the app's ELF. PebbleKit JS lines carry a
source-mapped `./src/pkjs/index.js:3:0` position.

## Debugging

`pebble gdb --emulator P` runs the toolchain's `arm-none-eabi-gdb` on the platform's
`<platform>_sdk_debug.elf`, connects to QEMU's gdb stub and adds `build/<platform>/pebble-app.elf`
at the app's load address. Alloy debugging is `pebble build --debug`, then `pebble install`, which
bridges the app's JavaScript engine to a debugger on `localhost:5002` — xsbug, which it starts,
or one already listening there.

Which it is depends on the last build: `pebble build --debug` leaves `build/.pebble_debug`, an
ordinary build removes it, and `pebble install` launches xsbug (waiting up to 15 seconds for it on
port 5002) whenever the marker is there and the project is Alloy. Without `--logs` such an install
does not return: it holds the bridge open until stopped. With no display xsbug cannot open, and
pebble-tool says `Alloy: failed to connect to debugger` and runs the app anyway.

When a session ends with xsbug simply gone, the debug build goes on waiting for it, and the
watch puts up "… is not responding". That dialog outlives the app: it stays over whatever runs
next — an ordinary build of the same app included — and takes its button presses and touches,
and the watch remembers the debug app across an emulator restart. Back dismisses it. A debugger
that logs out first (below) leaves none of this.

### Alloy: being the debugger

pebble-tool's bridge (`pebble_tool/util/xsbug.py`) launches xsbug only when nothing listens on
`localhost:5002`. With a server already there, it connects to it instead: twice to check the
port, hanging up at once, then — when the watch's first debugger bytes arrive — for real, as a
TCP client, and relays bytes both ways over Pebble protocol endpoint 51967, untouched. A NUL
byte from the watch ends a session; if the debugger hangs up, pebble-tool sends the watch
`<logout/>`. The firmware keeps the engine's debug flag only while a log listener is connected,
which is why the install runs with `--logs`. There is no option for another port.

The protocol is xsbug's, specified by the engine's `xsDebug.c` in the SDK's `toolchain/moddable`
(Moddable's `tools/xsbug-log` is a small client). Against emery with SDK 4.33.1:

- The engine sends `\r\n<xsbug>…</xsbug>\r\n`, first `<login name="main" value="XS 17.8.0 32-bit
  LE 16-bit ID" flags="e"/>`, and then waits: it disconnects unless a command comes within two
  seconds. `<set-all-breakpoints>…</set-all-breakpoints>` alone lets it go on.
- Commands are bare elements with a CRLF before and after; the engine acts on one only once the
  line after it ends. Attribute values are read as they are, without references.
- A breakpoint's `path` is the absolute path of the source on the machine that built it, as
  mcrun gave it to xsc, and the engine reports the same strings in `<break>` and `<frame>`. A
  TypeScript source is reported by its own `.ts` path and lines, through tsc's source map.
- A break is one message: `<frames>`, `<local>` (the top frame's variables), `<global>`,
  `<grammar>` (modules) and `<break path line># Break: breakpoint!</break>`. `<select id>` and
  `<toggle id>` are answered, in order, with a new `<local>`/`<global>`/`<grammar>` view; an
  expanded object's properties are nested in it, and stay expanded across breaks.
- `<step/>`, `<step-inside/>` and `<step-outside/>` step; breakpoints can be set and cleared while
  the app runs. To pause a running app, `<step-inside/>`: it stops at the next JavaScript
  statement — the next `draw()` of a watchface. `<step/>` while running does nothing, and the
  `start` breakpoint stops in a host frame with no source.
- `<instruments>` once, then `<samples>` every second, come unasked; they are not replies.
- While a debugger is connected, `console.log` goes to it as `<log>` (and a separate `<log>` for
  the line's end), not to pebble-tool's log stream.
- `<logout/>` lets the app run on, even after a break held for half a minute; pebble-tool hangs
  up half a second later, and the watch shows the app as it was, with no "not responding".
- With break on exceptions on, a watchface starts without breaking.

Expressions are evaluated by the engine but compiled on the host, by the SDK's own
`toolchain/moddable-tools/xsc` — `xsc expr.js -p -d -eval -e -o <dir> -r <name>`, as Moddable's
xsbug-log does — and sent as the `.xsb` file's bytes in upper-case hex. A syntax error is xsc's
exit code 22 with lines like `# error: missing )!`.

- `<eval id="@FRAME" path="HEX" line="N"/>` is answered by one message: `<result line="N">` — a
  `<property name="?" …>`, an object with its properties nested one level, or the text
  `# ReferenceError: …` — and the frame's views.
- A breakpoint takes `<breakpoint-condition path="HEX"/>`, `<breakpoint-hit-count path=">=3"/>`
  (`=`, `<`, `<=`, `>`, `>=`, `%`, written as they are) and `<breakpoint-trace path="HEX"/>`,
  nested in `<set-breakpoint … id="@1">`. A trace logs `<log path line>value</log>` and does not
  stop; a condition that throws logs the exception and does not stop either.
- Nested in the `<set-all-breakpoints>` that answers the login, a condition makes the app fail to
  start ("failed to allocate XS machine"). Sent as its own `<set-breakpoint>` right after, it
  works.

`pebble gdb --emulator P --vnc` works on a pseudo-terminal with commands written to its input:
it attaches to QEMU's gdb stub, adds the app's ELF at its load address, breaks on `app_crashed`,
and starts the app if another is running — but does not install a newer build, so a client
installs first. A breakpoint on a click handler stops when the button is pressed over VNC.

The SDK's gdb (xPack GNU Arm Embedded 15.2) is built without Python, so `gdb -i=dap` — gdb's own
debug adapter — is not available.

### C: gdb's machine interface

pebble-tool's `commands/sdk/project/debug.py` is the recipe, and gdb does the same in MI mode,
`arm-none-eabi-gdb --interpreter=mi3 -q <SDK>/sdk-core/pebble/<p>/qemu/<p>_sdk_debug.elf`:

- `-target-select remote :<gdb port>`, the port from `pb-emulator.json`. QEMU stops the watch
  and gdb reports a `*stopped` in the firmware; the stub takes one connection at a time, so a gdb
  left over keeps a new one out.
- `add-symbol-file "<build/<p>/pebble-app.elf>" *(void**)&g_app_load_address+<.text>` with
  `-s <section> <base>+<offset>` for every other section `objdump --headers --wide` flags `ALLOC`,
  through `-interpreter-exec console`; the worker's ELF likewise at `g_worker_load_address`. The
  load address is read when the command runs, so the app has to be running: after `pebble
  install` says `App install succeeded.`
- `-break-insert app_crashed`, as `pebble gdb` does.

Then, against emery with SDK 4.33.1:

- `-exec-interrupt` is ignored unless `-gdb-set mi-async on` came first; with it, the watch stops
  with `reason="signal-received",signal-name="SIGINT"`.
- Breakpoints cannot be set or deleted while the watch runs ("Cannot execute this command while
  the target is running"): interrupt, change them, continue.
- `-break-insert --source "<absolute path>" --line N` works, spaces in the path included, and so
  do `-c "<condition>"`, a function name and a path relative to the build directory. A line
  with no code is an error; an unknown function with `-f` is pending.
- Frames of the app carry `fullname` as the source's absolute path on this disk; the firmware's are
  `./build/../src/fw/…`, whose sources are not shipped.
- The SDK's functions are reached through stubs without line information, so `-exec-step` steps
  over them by itself. Stepping off the end of a handler lands in the firmware (`prv_dispatch_event`),
  and `-exec-finish --frame N` runs until frame N returns.
- `-stack-list-variables --no-values` names a frame's variables; `-var-create --thread T --frame N
  - * <expr>` gives a value, a type and a child count, and `-var-list-children --all-values` a
  struct's fields or a pointer's target. `-var-delete` frees them.
- `-target-detach` lets the watch run on; QEMU's stub accepts a new gdb afterwards.
