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
thing as prose, so the plugin reads the files instead.

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

## Building

`pebble build` runs `waf configure` and `waf build` from the SDK. `--debug` adds `-O0 -DPBL_DEBUG`.
The bundle is `build/<directory name>.pbw` and each platform's ELF is
`build/<platform>/pebble-app.elf`. Alloy sources are compiled per platform by `mcrun` first.

`pebble compile-commands --platform P` writes `compile_commands.json` from the waf cache, with the
absolute path of the SDK's `arm-none-eabi-gcc`. `pebble sdk include-path P` prints the directory
holding that platform's `pebble.h`.

## The emulator

`pebble install --emulator P` starts `qemu-pebble` and `pypkjs` in the background and records them
in `pb-emulator.json` in the system temporary directory, keyed by platform and SDK version: process
ids and the ports of QEMU's Pebble serial, gdb stub, monitor and VNC server, and pypkjs's
WebSocket. That file is how a second client finds a running emulator.

By default QEMU opens an SDL window. `--vnc` starts it with `-vnc :1` — port 5901 for every
emulator, so only one can have it at a time — and without the window.

## Watches

`--phone IP` connects to the Developer Connection of the Pebble phone app on `ws://IP:9000`;
`--cloudpebble` goes through the cloud after `pebble login`; `--adb` forwards over adb.
`pebble logs`, `pebble screenshot` and `pebble install --logs` work over any of them.

## Logs

```
[14:02:11] main.c:42> Hello
[14:02:11] pkjs> Hello from the phone
```

The level is not in the text: pebble-tool shows it only as a colour (`--color` keeps the colours
when the output is not a terminal).

## Debugging

`pebble gdb --emulator P` runs the toolchain's `arm-none-eabi-gdb` on the platform's
`<platform>_sdk_debug.elf`, connects to QEMU's gdb stub and adds `build/<platform>/pebble-app.elf`
at the app's load address. Alloy debugging is `pebble build --debug`, then `pebble install`, which
starts xsbug and bridges it on `localhost:5002`. Neither has a debug adapter.
