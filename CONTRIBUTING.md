# Contributing

Bug reports and pull requests are both welcome. This file is the short version of what the
repository expects; [docs/developing.md](docs/developing.md) is how to build and test it.

## Reporting a bug

The things that make a Pebble bug reproducible are the ones nobody thinks to include:

* **The SDK and pebble-tool versions**: **Copy environment report** in **Settings | Languages &
  Frameworks | Pebble** has them and the rest of the checklist as text. And the platform you were
  building for or running on.
* **Your operating system**, and on Windows the WSL distribution and its version
  (`wsl -l -v`). Development happens on macOS; Linux and WSL get less exercise, and a bug on either
  is more likely to be real than a duplicate.
* **The IDE**: IntelliJ IDEA, CLion or WebStorm, and its version. C code insight comes from a
  different engine in each.

If the IDE said something, **Help | Show Log in Finder** (or Explorer) has the whole of it in
`idea.log`. For a build or a run, the Build window and the Run console have what pebble-tool printed.

## Before opening a pull request

```bash
./gradlew build buildPlugin   # compile, run the unit tests, build the zip
./gradlew runSelfCheck        # is the plugin whole, registered, and in the right menus?
./gradlew verifyPlugin -PverifySince
```

The last one is the one people forget. The plugin compiles against the newest IDE and promises to
run in an older one, and that check is the only thing that can tell you the promise is still true.
CI also verifies CLion and WebStorm (`-PverifyClion`, `-PverifyWebStorm`), on all three operating
systems; [.github/workflows/build.yml](.github/workflows/build.yml) is the whole list.

If you have the Pebble SDK set up, `./gradlew test -PliveTests` runs the other half of the suite
against the real pebble-tool, SDK and emulator. It is opt-in so a machine without the SDK still goes
green.

## Conventions

* **No per-file licence header.** The Apache 2.0 text at the root covers the work.
* **User-visible text is US English**, because that is what the platform's own labels use.
* **Comments say why, not what.** What is commented is the reasoning: a workaround for something
  pebble-tool or the SDK does, a threading constraint, a shape that was tried and did not work.
  Facts about pebble-tool and the SDK go in [Notes on the Pebble SDK](docs/pebble-sdk-notes.md).
* **The plugin drives pebble-tool and the IDE's own engines, and reimplements neither.**
  [Architecture](docs/architecture.md) says why; read it before adding a feature.
* **Nothing of Core Devices' is committed.** No SDK files, headers or typings, no templates, no
  documentation prose. Test fixtures are written for the test, in the SDK's *formats*. The README
  and the plugin's description both say the plugin redistributes nothing of theirs, and that has to
  stay true.
* **No `resource-bundle`.** Every string lives in the source that shows it.

## Commit messages

A subject line that says what changed, in the present tense, and a body that says why if the why is
not obvious. No attribution trailers: no `Co-Authored-By`, no "Generated with" lines.
