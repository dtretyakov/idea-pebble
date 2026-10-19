# Notes for coding agents

- Read [docs/architecture.md](docs/architecture.md) before adding a feature: the plugin drives
  pebble-tool and the IDE's own engines, and does not reimplement either.
- Facts about pebble-tool and the SDK go in [docs/pebble-sdk-notes.md](docs/pebble-sdk-notes.md);
  the plan is [docs/roadmap.md](docs/roadmap.md).
- `./gradlew build runSelfCheck verifyPlugin` is what CI runs on every change.
- Releasing — the Marketplace's rules, the first upload, the secrets, what the verifier's warnings
  mean — is [PUBLISHING.md](PUBLISHING.md).
