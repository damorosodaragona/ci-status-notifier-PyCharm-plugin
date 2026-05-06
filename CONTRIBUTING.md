# CONTRIBUTING

This project is a JetBrains IDE plugin for showing CI status in PyCharm/IntelliJ. It supports GitHub commit-status polling and direct Jenkins polling, with optional experimental Keycloak/OIDC session recovery for Jenkins setups that require a browser login.

## Project Layout

- `src/main/kotlin/com/damorosodaragona/jenkinsnotifier/`: Kotlin production code.
- `src/main/resources/META-INF/plugin.xml`: JetBrains plugin registration for settings, services, startup activity, tool window, and notifications.
- `src/test/kotlin/com/damorosodaragona/jenkinsnotifier/`: unit, infrastructure, and smoke-oriented tests.
- `src/test/resources/jenkins-smoke/`: disposable Jenkins resources for controlled smoke tests.
- `.github/workflows/`: CI, plugin build, smoke test, and release workflows.
- `scripts/`: local helper scripts, currently focused on PIT mutation testing.

## Main Components

- `CiStatusSettings`: project-level persisted settings and Password Safe integration.
- `CiStatusConfigurable`: Settings UI under `Settings | Tools | Jenkins CI Notifier`.
- `CiStatusStartupActivity`: startup wiring, polling loop, notification decisions, and refresh events.
- `CiStatusToolWindowFactory`: Jenkins CI tool window, job tree, build details, stages, artifacts, and preview actions.
- `JenkinsStatusClient`: Jenkins API access, job discovery, build lookup, stages, artifacts, and diagnostics helpers.
- `GitHubStatusClient`: GitHub commit-status lookup.
- `CiStatusBuildLogic`: pure status transition and fingerprint logic.
- `KeycloakSessionService`: experimental browser-based Keycloak/OIDC login recovery.
- `AuthNotificationCoordinator`: pure decision logic for when auth recovery should show a notification.
- `LegacySettingsMigration`: migration from older settings and credential keys.

## Runtime Flow

The normal runtime path is:

```text
Settings -> Jenkins/GitHub client -> polling -> build logic -> notifications/UI refresh
```

For Jenkins mode:

```text
Settings
  -> JenkinsStatusClient
  -> branch/job discovery
  -> latest build summary
  -> CiStatusBuildLogic
  -> notification and Jenkins CI tool window refresh
```

For GitHub mode:

```text
Settings
  -> GitShaReader
  -> GitHubStatusClient
  -> CiStatusBuildLogic
  -> IDE notification
```

## Jenkins Artifacts and Preview

Artifact handling lives mainly in `JenkinsStatusClient` and `CiStatusToolWindowFactory`.

The client reads artifact metadata and downloads artifacts for a selected build. The tool window can open HTML report artifacts in an IDE preview when embedded browser support is available. Downloads preserve artifact paths so relative report assets such as CSS, scripts, images, and linked pages can continue to resolve locally.

## Authentication and OIDC

Jenkins API-token access is the preferred authentication model.

Some Jenkins installations sit behind Keycloak/OIDC and require a web session before API calls succeed. The experimental auth path is:

```text
Jenkins API auth failure
  -> KeycloakSessionService.attemptAutoLoginInBackground()
  -> recovered: retry/refresh without notification
  -> not recovered: AuthNotificationCoordinator may show login action
  -> interactive fallback if the user chooses it
```

Keep interactive login decisions out of polling code where possible. Polling should go through `AuthNotificationCoordinator` so notification behavior remains testable.

## Build and Run Locally

Use JDK 17.

Run the plugin in a development IDE:

```bash
./gradlew runIde
```

Build the plugin ZIP:

```bash
./gradlew buildPlugin
```

Run the full local build:

```bash
./gradlew build
```

Run JetBrains plugin verification:

```bash
./gradlew verifyPlugin
```

The configured verifier targets are declared in `build.gradle.kts`.

## Tests

Run the standard test suite:

```bash
./gradlew test
```

Run Settings-focused tests:

```bash
./gradlew test --tests "*CiStatusConfigurable*"
```

Run a single test class:

```bash
./gradlew test --tests "*CiStatusConfigurableApplyResetTest"
```

The default suite avoids requiring Docker and is suitable for regular local development and CI.

## Controlled Smoke Tests

Smoke tests use disposable local services and are opt-in.

Run them only when Docker is available:

```bash
JCN_CONTAINER_SMOKE_ENABLED=true ./gradlew test --tests "*SmokeTest"
```

The Jenkins Settings smoke test starts a local Jenkins container, creates a known user/job, verifies Settings persistence, and checks successful and failing Jenkins connection diagnostics.

New smoke tests should use the `*SmokeTest` naming convention so the PR smoke workflow can find them.

## PIT Mutation Testing

PIT is configured for pure decision logic classes:

```text
com.damorosodaragona.jenkinsnotifier.CiStatusBuildLogic
com.damorosodaragona.jenkinsnotifier.AuthNotificationCoordinator
```

Discover PIT-safe tests:

```bash
./scripts/discover-pit-safe-tests.sh
```

Run PIT with the safe list:

```bash
./scripts/safe-pitest.sh
```

Run PIT for one test class:

```bash
./gradlew pitest --no-configuration-cache --rerun-tasks -PpitTargetTests=com.damorosodaragona.jenkinsnotifier.SomeTest
```

Keep PIT focused on pure logic. UI-heavy, IDE-dependent, or unstable tests should stay in the normal JUnit suite rather than the PIT safe list.

## Contribution Guidelines

- Prefer tests around pure logic before changing UI or polling behavior.
- Keep Jenkins networking behavior in `JenkinsStatusClient` unless a UI concern genuinely belongs in the tool window.
- Keep Settings persistence in `CiStatusSettings` and Settings UI wiring in `CiStatusConfigurable`.
- Keep notification decisions testable through `CiStatusBuildLogic` or `AuthNotificationCoordinator`.
- Do not store tokens or web passwords in persisted XML state; use the JetBrains Password Safe helpers already present in `CiStatusSettings`.
- For release work, update `pluginVersion` in `gradle.properties`, verify with `./gradlew clean test verifyPlugin buildPlugin`, then create the annotated release tag only after the release PR is merged.
