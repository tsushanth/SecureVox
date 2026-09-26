fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android deploy

```sh
[bundle exec] fastlane android deploy
```

Upload to the production track and complete the rollout immediately

### android deploy_internal

```sh
[bundle exec] fastlane android deploy_internal
```

Upload to the internal track for smoke testing before production

### android deploy_staged

```sh
[bundle exec] fastlane android deploy_staged
```

Stage a production rollout. Pass rollout: 0.1 for 10%, 0.5 for 50%.

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
