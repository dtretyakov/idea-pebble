# Publishing

Everything the repository can automate is automated: `.github/workflows/release.yml` checks the tag,
verifies against every IDE the plugin claims, builds, tests, signs, uploads and cuts a GitHub
release. What is left here is the part that needs an account, a secret, or a human — the first
upload, the signing certificate, and the listing itself.

## Once, before the first release

### 1. The repository is public

The listing links to the source, and an open-source licence is only accepted with a source link
behind it. Every external link on the plugin page is checked for being reachable, and the vendor URL
in `plugin.xml` is this repository: while it is private, both are a 404, and a 404 is a rejection,
not a warning.

Before making it public, scan the history for anything that should not be in it:

```bash
gitleaks detect --source . --log-opts="--all"
```

### 2. A signing certificate

The Marketplace accepts an unsigned upload and signs it with its own key, so this is optional. It is
worth doing anyway: an unsigned plugin installs behind a warning dialog, and that dialog is the first
thing a new user sees.

The certificate is the author's, not the plugin's: use the same one as
[idea-monkeyc](https://github.com/dtretyakov/idea-monkeyc), so every plugin from this vendor is
signed by one key. Only when there is none yet:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Keep `private_encrypted.pem` and its password somewhere that survives a lost laptop. A certificate
that cannot be reproduced means every future release is signed by a different key, which is exactly
what the signature exists to rule out.

### 3. A Marketplace token

Sign in at <https://plugins.jetbrains.com>, then **Profile | My Tokens | Generate Token**. The token
is scoped to the account, not to a plugin, so the one idea-monkeyc publishes with works here too. A
leak is worth revoking immediately.

### 4. The secrets

GitHub secrets belong to one repository and cannot be read back, so they are set again here from the
same files:

```bash
gh secret set PRIVATE_KEY < private_encrypted.pem
gh secret set CERTIFICATE_CHAIN < chain.crt
gh secret set PRIVATE_KEY_PASSWORD
gh secret set PUBLISH_TOKEN
```

| Secret | Contents |
|---|---|
| `PRIVATE_KEY` | the whole of `private_encrypted.pem`, `-----BEGIN` line included |
| `PRIVATE_KEY_PASSWORD` | the password given to `genpkey` |
| `CERTIFICATE_CHAIN` | the whole of `chain.crt` |
| `PUBLISH_TOKEN` | the Marketplace token |

### 5. The first upload, by hand

The Marketplace only creates a listing through the web form; `publishPlugin` can update a plugin
that exists but cannot bring one into being. So the first release goes up manually:

1. **Actions | Release | Run workflow**, with *Build and sign, but do not upload* checked. It runs
   every check the tag would and keeps the signed zip as the `plugin` artifact.
2. Download it and upload `idea-pebble-<version>-signed.zip` at
   <https://plugins.jetbrains.com/plugin/add>. The archive is named after `rootProject.name` in
   `settings.gradle.kts`, not after the plugin's display name.
3. Do not tag that version. A tag runs `publishPlugin`, and the Marketplace refuses a version it has
   already seen; the first tag is the release after the one uploaded by hand.

Then, on the listing page:

- **License** — Apache 2.0, matching `LICENSE`.
- **EEA trader / non-trader declaration** — it belongs to the vendor profile, which idea-monkeyc's
  listing has already answered: an individual publishing a free plugin is a non-trader.
- **Tags** — `Build`, `Debugging`, `Internet of Things`, `Tools Integration`, `Testing`. Only the
  Marketplace's own list is offered (`curl https://plugins.jetbrains.com/api/tags?family=intellij`):
  there is no tag for embedded development or wearables, and `Programming Language` is for plugins
  that add a language, which this one does not.
- **Source code** and **Issue tracker** — <https://github.com/dtretyakov/idea-pebble> and its issues.
- **Screenshots** — 1280×800, one aspect ratio across all of them, taken in `runIdeWithFixture`
  against a real SDK: a 1560×975 window (16:10) on a Retina display, scaled to 1280×800 and
  quantised to an adaptive 256-colour palette. They go in `docs/images/marketplace/`, in file-name
  order, chosen for what a Pebble developer does every day:

  | | |
  |---|---|
  | `01-emulator.png` | the app running in the Pebble Emulator tool window beside its code, the platform chosen beside Run |
  | `02-all-platforms.png` | Capture Every Platform: the app on every platform it targets, side by side |
  | `03-alloy-debugger.png` | an Alloy watchface stopped on a breakpoint: frames, and the locals with their real values |
  | `04-alloy-completion.png` | Alloy completion from the SDK's typings: the events a watchface can listen to |
  | `05-build-memory.png` | the Build window after a build: each platform's memory |
  | `06-ui-tests.png` | UI test scenarios in the test runner, a screen compared with its reference |
  | `07-new-project.png` | New Project \| Pebble, with Alloy chosen |
  | `08-c-completion.png` | C completion over the SDK's headers, with a platform-stub warning in the same editor |

  Alloy leads where the two languages differ, the debugger and completion, because it is where new
  Pebble apps are being written; C, which every platform runs, carries the shots that span all
  seven. They are taken on two demo projects written for the purpose: Focus, a C timer for every
  platform with three UI test scenarios, and Orbit, an Alloy watchface for emery and gabbro.

- **What's new** — read the rendered release notes on the listing before publishing. They are
  generated from `CHANGELOG.md`, and the Marketplace sanitises HTML more strictly than the plugin
  descriptor does; `<h4>`, which `### ` becomes, is the tag to look at.

Review takes a few working days for a first submission. If nothing has come back after four, write
to <marketplace@jetbrains.com>.

### What a reviewer is likely to ask

**The name.** *Pebble* is Core Devices' mark, and the approval guidelines forbid third-party
trademarked names used without authorization. The case for it is nominative use: Core Devices'
rules allow "Pebble" in a name only in reference, which is why the plugin is *Develop for* Pebble
rather than Pebble — and the Marketplace's "Pebble" is another plugin's, for Pebble Templates. The
description carries a Trademarks section naming Core Devices as the owner and denying affiliation,
and the plugin displays no Core Devices logo; its icon is a generic watch.

**What it downloads and runs.** Nothing until the user asks. *Set Up Pebble SDK* — a button, never a
startup task — downloads uv from its GitHub release, checks it against a SHA-256 the plugin carries
(`UvRelease`), and installs it in JetBrains' shared data directory; uv installs pebble-tool from
PyPI, and pebble-tool installs the SDK. A uv already on the machine is used instead. The commands
that need `sudo` or Homebrew are never run by the plugin: they open in the IDE's terminal for the
user to read and run. The plugin's own network traffic is that download and the SDK release list
from `sdk.repebble.com`; there is no telemetry and nothing about the user is sent anywhere.

**Internal API.** None, and `verifyPlugin` fails the build if any appears. The self-check does need
internal API — an application starter, the dynamic-unload verdict — so it is a plugin of its own
under `src/selfCheck`, which only `runSelfCheck` installs and which is never verified or shipped.

## Every release after that

1. Write the release's section at the top of `CHANGELOG.md` — it becomes the Marketplace's release
   notes verbatim, through `latestChangeNotes` in `build.gradle.kts`. That converter understands
   `### ` subheadings, `- ` bullets with wrapped continuation lines, and inline backticks, and
   nothing else: a `**bold**` or a markdown link ships to the Marketplace as literal punctuation.
2. Set `pluginVersion` in `gradle.properties`. It only ever goes up: the Marketplace refuses a
   version it has already seen, including one it rejected.
3. Tag it and push the tag.

```bash
git tag v0.9.1 && git push origin v0.9.1
```

The workflow refuses to publish if the tag and `pluginVersion` disagree, which is the mistake that
otherwise ships one version under another's name.

### Betas

A version with a suffix — `0.10.0-beta.1`, `0.10.0-eap.1` — publishes to a channel of that name
instead of to everybody. Users opt in by adding `https://plugins.jetbrains.com/plugins/beta/list`
under **Settings | Plugins | Manage Plugin Repositories**. The GitHub release is marked pre-release
and carries the same zip, for anyone who would rather install from disk than add a repository.

## What the checks are for

The plugin depends on `com.intellij.modules.platform` and nothing else it cannot do without, so the
Marketplace offers it to every IDE from `sinceBuild` (2026.1) on. The checks are the IDEs it names
and the ends of that range:

- `./gradlew verifyPlugin` — against the IDE the plugin is compiled with.
- `./gradlew verifyPlugin -PverifySince` — against the *oldest* IDE `sinceBuild` promises. Nothing
  else can tell you the promise is true, because the code compiles against the newest.
- `./gradlew verifyPlugin -PverifyClion` and `-PverifyWebStorm` — the other two named targets, which
  the plugin is compiled against neither of.
- `./gradlew verifyPlugin -PverifyRecommended` — the whole sweep JetBrains recommends, the next
  IDEA in EAP among them. Several gigabytes of IDE downloads, so it runs weekly.
- `./gradlew verifyPlugin -PverifyAndroidStudio` — the product the sweep never reaches, because it
  only picks the one the plugin is built against. Weekly too.
- `./gradlew runSelfCheck` — a headless IDE that starts the plugin and checks every extension it
  declares is registered and every action is in the menu it names, which are both silent at run
  time when they are wrong.

The build workflow runs the first four and the self-check on every change, the release workflow
runs them again before signing, and each verification has a runner of its own: two whole IDEs do not
fit on one runner's disk.

What the verifier reports, and what it means:

**Deprecated API.** The Marketplace does not refuse a plugin for deprecated API the way it does for
internal API, and what is left is deliberate — the forms that exist in every IDE the plugin
supports:

- `FilePosition(File, …)` and `FileMessageEvent.builder` — the two the verifier reports as scheduled
  for removal include the first. 2026.2 added `FilePosition(Path, …)`; 2026.1 has only the `File`
  one.
- `ProjectTaskRunner.canRun(ProjectTask)` — the other one scheduled for removal, and abstract in
  2026.1, so it has to be overridden there.
- `XCompositeNode.tooManyChildren(int)` — abstract in 2026.1 as well. Its replacement takes a
  callback that loads the next page of children, which the gdb debugger does not page yet.
- `TerminalToolWindowManager.createShellWidget` — the terminal's replacement API is not public in
  2026.1.
- `ToolWindowFactory.isApplicable` and `isDoNotActivateOnStart` — not called by the plugin's source:
  the Kotlin compiler's bridges to the interface's defaults are, and the verifier reports those.

When `sinceBuild` moves up, these are the calls to revisit, and the verifier's own report is the
list:

```bash
./gradlew verifyPlugin
open build/reports/pluginVerifier/*/plugins/com.github.dtretyakov.pebble/*/deprecated-usages.txt
```

**Experimental API.** `ToolWindowFactory`'s `manage`, `getIcon` and `getAnchor`,
`AdditionalLibraryRootsListener` and `TaskRunnerResults`: platform API that is still marked
experimental, with nothing stable in its place. Reported, not refused.
