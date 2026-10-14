---
name: pebble
description: Build, run, look at and fix Pebble smartwatch apps and watchfaces, in C or in Alloy (JavaScript), with Pebble SDK 4.x. Use when the project has a package.json with a "pebble" section, when writing Pebble C (pebble.h, Window, Layer, TextLayer, APP_LOG) or Alloy (Poco, Piu, src/embeddedjs) code, or when asked to run, screenshot or test an app on a Pebble emulator.
---

# Pebble apps

A Pebble app is built once per hardware platform, and each platform has its own screen and
limits. Check every change on more than one of them.

## Tools

With the Pebble plugin in an IntelliJ IDE (IntelliJ IDEA, CLion, WebStorm), the IDE's MCP server
offers these tools. Prefer them to the command line: they keep the IDE's emulator, Build window
and Run window in step with what you do.

| Tool | Use it to |
|------|-----------|
| `pebble_project` | Learn the app's type (`native` = C, `moddable` = Alloy), target platforms and their screens, and the running emulators. Call it first. |
| `pebble_api` | Look a name up in the SDK before you use it: declaration, documentation, the platforms where it is missing or only a stub, and the nearest real names. |
| `pebble_build` | Build every platform. Errors come once each, with the platforms they happened on, plus RAM and heap per platform. |
| `pebble_run` | Build, install and start the app on one platform's emulator. Returns `logsAfter` for `pebble_logs`. |
| `pebble_logs` | Read the app's `APP_LOG` / `console.log` lines, crashes and memory notes after a cursor. Pass the returned `next` the next time. |
| `pebble_screenshot` | Save the emulator's screen as a PNG and return its path; read the image. Use `all: true` for every platform (slow). |
| `pebble_emulator` | Press buttons, tap, set the time, battery or Bluetooth, tilt or shake, one step per line. Returns a screenshot after. |
| `pebble_ui_tests` | Run the UI test scenarios (`tests/ui/*.pbtest`) on each platform. Every expected screen passes, fails with a diff image, or is recorded as a new reference. |
| `pebble_ui_test_accept` | Make a run's screenshot the reference, when a screen changed on purpose. |
| `pebble_release_check` | Before a release: a release build checked against what the Pebble appstore takes. |
| `pebble_emulator_reset` | Stop the emulators (`kill`), or stop them and forget their installed apps (`wipe`), when one hangs, will not boot or keeps failing to install. |
| `pebble_sdk` | List the SDKs, install one, or make one current. |
| `pebble_debug_start` | Run the app under the IDE's debugger: gdb for C, the JavaScript engine for Alloy. |
| `pebble_debug_breakpoint` | Set, condition or remove a line breakpoint, and learn whether the debugger could set it. |
| `pebble_debug_wait` | Wait for the app to stop. Returns the file and line, the stack and the variables. |
| `pebble_debug_control` | Step `over`, `into` or `out`, `resume`, or `pause`. Steps return the next stop. |
| `pebble_debug_evaluate` | Evaluate an expression where the app stopped, to look deeper into a value. |
| `pebble_debug_stop` | End the session. The app runs on. |

The IDE's own tools work on Pebble projects too: `get_file_problems` runs the plugin's checks
(calls a platform only stubs out, missing resources, unused message keys), and `build_project`
runs `pebble build`.

Without the IDE, do the same with `pebble` on the command line: see
[references/without-ide.md](references/without-ide.md).

## The loop

1. `pebble_project`, once.
2. Before calling an API you are not sure of, `pebble_api` it. Pebble SDK 4.x is not 3.x, and a
   function may exist on one platform and be a stub that does nothing on another.
3. Edit, then `pebble_build`. Fix every error, and warnings too: the SDK builds with `-Werror`.
4. `pebble_run` on the platform you are working on: `emery` for a 200×228 colour touch screen,
   `basalt` for 144×168 colour.
5. Look at it with `pebble_screenshot`. Drive it with `pebble_emulator` (`press select`,
   `press back`, `tap 100 120`), and read what it logged with `pebble_logs`.
6. Before you call it done, check a round screen (`chalk`, `gabbro`) and a black-and-white one
   (`aplite`, `diorite`, `flint`) if the app targets them: `pebble_run` each, or
   `pebble_screenshot` with `all: true`.
7. When the logs do not explain a wrong value or a crash, debug.
   - Set `pebble_debug_breakpoint` on the line that matters, then `pebble_debug_start`.
   - Make the app reach it with `pebble_emulator`, then `pebble_debug_wait`.
   - Look deeper with `pebble_debug_evaluate`, step with `pebble_debug_control`.
   - `pebble_debug_stop` when done.
8. Before a release, run `pebble_release_check` and fix every `error`. Missing screenshots come
   from `pebble_screenshot`; look at each one first.
9. If the project has `tests/ui`, run `pebble_ui_tests`. Read the diff of every failure. Accept a
   new screen with `pebble_ui_test_accept` only when the change is the one you meant. Look at
   recorded screens before committing them.

## UI test scenarios

A `.pbtest` file is the `pebble_emulator` steps plus `expect <screen>` lines. Each `expect` line
compares the screen with `screenshots/<platform>/<screen>.png` beside the file:

```
platforms emery basalt        # the app's target platforms when left out
install
wait 1000
press select
expect menu
```

## Seeing the screen

Every screenshot comes with a `view`: the same pixels enlarged, since a watch's screen is too small
to read at its own size. Open it as an image — the Read tool in Claude Code, `view_image` in
Codex — after every change you can see. Do not assume a screen is right because the build passed.
Check that:

- every piece of text is there, whole and legible;
- nothing is cut off by a round screen's edge (chalk, gabbro) or hidden under the status bar;
- on black-and-white platforms (aplite, diorite, flint) everything still shows, with no colour
  turned to black on black;
- what changed after a step is what the step should have changed.

A failed UI test's `view` puts the reference, the screen now and their difference side by side.

## Platforms

| Platform | Watches | Screen | Alloy | App RAM |
|----------|---------|--------|-------|---------|
| aplite | Pebble Classic, Steel | 144×168 black and white | no | 24 KB |
| basalt | Pebble Time, Time Steel | 144×168, 64 colours | no | 64 KB |
| chalk | Pebble Time Round | 180×180 round, 64 colours | no | 64 KB |
| diorite | Pebble 2 | 144×168 black and white | no | 64 KB |
| flint | Pebble 2 Duo | 144×168 black and white | no | 64 KB |
| emery | Pebble Time 2 | 200×228, 64 colours, touch | yes | 128 KB |
| gabbro | Pebble Round 2 | 260×260 round, 64 colours, touch | yes | 128 KB |

The platforms an app builds for are `targetPlatforms` in `package.json`. How to write for all of
them in C — screen shapes, colour, missing hardware, memory — is in
[references/platforms.md](references/platforms.md).

## Mistakes to avoid

- Hard-coded coordinates. Lay out from `layer_get_unobstructed_bounds(window_layer)` and centre
  with `GRect`.
- Text pointers that do not outlive the `TextLayer`: `text_layer_set_text` keeps the pointer, not
  the text. Use a static or heap buffer.
- Memory that is never freed. Destroy in `window_unload` what was created in `window_load`. When
  an app exits with memory still allocated, `pebble_logs` shows it.
- `pebble install --logs` on the command line never returns; run it in the background, or use
  `pebble_run`.
- Pebble projects are built by `pebble build` (waf), not CMake or make. Do not add build files of
  your own.
