## What this changes, and why

<!-- The why is the part that is hard to recover later. -->

## Checks

- [ ] `./gradlew build buildPlugin`
- [ ] `./gradlew runSelfCheck`
- [ ] `./gradlew verifyPlugin -PverifySince` — the promise `sinceBuild` makes to users on an older
      IDE; nothing else can tell you it still holds
- [ ] `./gradlew test -PliveTests`, if you have the SDK set up and touched anything that drives it
- [ ] Nothing of the Pebble SDK added to what the plugin ships — see CONTRIBUTING.md
