# Developing the plugin

Building it, and the kinds of test that catch different failures.

## Build

```bash
./gradlew build          # compile and run the unit tests
./gradlew runSelfCheck   # headless IDE: is the plugin whole, registered, and in the right menus?
./gradlew runIde         # sandbox IDE
./gradlew runIdeWithFixture                       # ...with a Pebble project open
./gradlew runIdeWithFixture -Pfixture=/path/to/project
./gradlew verifyPlugin   # compatibility check against the IDE the plugin is built on
./gradlew verifyPlugin -PverifySince              # ...and the oldest IDE sinceBuild promises
./gradlew verifyPlugin -PverifyClion              # ...and the newest CLion
./gradlew verifyPlugin -PverifyWebStorm           # ...and the newest WebStorm
./gradlew verifyPlugin -PverifyEap                # ...and the next IDEA, before it ships
./gradlew verifyPlugin -PverifyRecommended        # ...and every IDE JetBrains recommends
./gradlew verifyPlugin -PverifyAgainst=<unpacked> # ...against an IDE already on disk
./gradlew buildPlugin    # distributable zip
./gradlew test -PshowOutput                       # let the tests print to the console
```

The JDK is 21; use Temurin rather than the Microsoft build of OpenJDK, which Ant mistakes for the
Microsoft JVM of the 1990s and `instrumentCode` then fails on Windows.

`-PverifySince` is the one worth remembering. The code compiles against the newest IDE, so nothing
else can tell you whether the promise `sinceBuild` makes to users on an older one is still true.
`-PverifyClion` and `-PverifyWebStorm` matter for the same reason: both are named targets, and the
plugin is compiled against neither.

`runIdeWithFixture` copies `src/test/resources/fixture-c` under `build/` and opens the copy, so a
run never leaves the working tree dirty.

### Verifying against the real SDK

```bash
./gradlew test -PliveTests
```

Live tests drive a real pebble-tool and SDK. They opt in so a checkout on a machine without one
still goes green — a red test there would be reporting the machine rather than the code. A Gradle
property rather than an environment variable, because a test JVM inherits the Gradle daemon's
environment and not the shell's.

`runSelfCheck` catches the failures nothing else can: something named in `plugin.xml` that does
not take is silent at run time. An `add-to-group` naming a group this IDE has not got is one line
in `idea.log` and then a menu item that is simply not there. The check lives in `src/selfCheck` as
a plugin of its own, because it needs internal API and the Marketplace refuses a plugin that ships
any.

## Conventions

No per-file licence header. The Apache 2.0 text at the root covers the work.

User-visible text is US English, because that is what the platform's own labels use.

No `resource-bundle`: every string is in the source that shows it. The plugin ships in English
only, and a bundle would put every message a file away from the code that raises it.

Comments say why, not what. Where the plugin works around something in pebble-tool or the SDK, the
comment says so and [Notes on the Pebble SDK](pebble-sdk-notes.md) lists it.
