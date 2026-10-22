# Notes for coding agents

- Read [docs/architecture.md](docs/architecture.md) before adding a feature: the plugin drives
  pebble-tool and the IDE's own engines, and does not reimplement either.
- Facts about pebble-tool and the SDK go in [docs/pebble-sdk-notes.md](docs/pebble-sdk-notes.md);
  the plan is [docs/roadmap.md](docs/roadmap.md).
- CI runs `./gradlew build buildPlugin runSelfCheck` on Linux, macOS and Windows, and
  `verifyPlugin` against the newest IDE, the oldest supported one (`-PverifySince`), CLion
  (`-PverifyClion`) and WebStorm (`-PverifyWebStorm`); [build.yml](.github/workflows/build.yml) is
  the whole of it.
- Releasing — the Marketplace's rules, the first upload, the secrets, what the verifier's warnings
  mean — is [PUBLISHING.md](PUBLISHING.md).
