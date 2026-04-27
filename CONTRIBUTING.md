# CONTRIBUTING

## Overview

The plugin monitors Jenkins CI status and handles authentication automatically when possible.

Core flow:

```
Polling → Jenkins API → Auth check → Auto-login → (optional) Notification → UI update
```

---

## Authentication Model

Authentication is handled by `KeycloakSessionService`.

There are three levels.

### 1. Background auto-login

Triggered automatically during polling.

```
recover-auth start → attemptAutoLoginInBackground()
```

Behavior:

* Uses stored credentials
* Runs in hidden browser (JCEF)
* Blocks until result (joins existing attempt if already running)

Result:

* `true` → session recovered
* `false` → fallback required

---

### 2. Interactive login

Triggered only when needed.

```
ensureLoggedIn()
```

Behavior:

* Opens browser dialog
* Autofills credentials
* User can intervene if needed

---

### 3. Notification fallback

Triggered only when auto-login fails.

Managed by `AuthNotificationCoordinator`.

Rules:

* DO NOT show notification if auto-login is running
* DO NOT show notification if auto-login succeeds
* SHOW notification only if auto-login fails

Flow:

```
Auth failure
  ↓
attemptAutoLoginInBackground()
  ↓
true  → SKIP notification
false → SHOW notification
```

---

## Logging

All auth-related behavior is logged via `CiStatusDebugLog`.

Key markers:

```
recover-auth start
auto-login requested
auto-login finished result=...
auth-notify CHECK
auth-notify SKIP
auth-notify SHOW
auth-notify EMIT
auth-notify CLICK
```

Use logs to understand execution order.

---

## Key Components

### KeycloakSessionService

Handles:

* Auto-login
* Interactive login
* Credential management

Important:

```
attemptAutoLoginInBackground() joins ongoing attempts
```

---

### AuthNotificationCoordinator

Encapsulates the decision:

“Should we notify the user?”

Pure logic:

* Calls auto-login
* Decides notification
* No UI dependencies

Fully testable.

---

### CiStatusStartupActivity / ToolWindow

Entry points:

* Polling
* UI-triggered actions

They must:

* NEVER directly trigger interactive login
* ALWAYS go through coordinator logic

---

## Testing Strategy

Tests are pure (no IDE required).

Covered cases:

* Auto-login success → no notification
* Auto-login failure → notification shown
* Notification click → interactive login
* Concurrent auto-login → waits correctly

---


## Tests and Verification

### Standard test suite

Run the normal test suite with:

```bash
./gradlew test
```

Smoke tests are skipped by default, so this command is safe for normal local development and for the standard CI test workflow.

For Settings-related tests only:

```bash
./gradlew test --tests "*CiStatusConfigurable*"
```

For a specific test class:

```bash
./gradlew test --tests "*CiStatusConfigurableApplyResetTest"
```

### Controlled smoke tests

Controlled smoke tests are real integration checks against local, disposable services.

They are opt-in and must be enabled explicitly:

```bash
JCN_CONTAINER_SMOKE_ENABLED=true ./gradlew test --tests "*SmokeTest"
```

Current smoke coverage includes the Jenkins Settings flow:

```bash
JCN_CONTAINER_SMOKE_ENABLED=true ./gradlew test --tests "*CiStatusConfigurableContainerSmokeTest"
```

The controlled Jenkins smoke test:

* starts a local Jenkins container through Testcontainers
* uses a Jenkins Docker image configured for the test environment
* creates a known user and job
* verifies Settings save/reload
* verifies the `Test Jenkins connection` action with valid credentials
* verifies failure reporting with invalid credentials or invalid Jenkins URL

Docker must be available. On local macOS development machines, the test may try to start Docker Desktop if it is installed but not running. If Docker is unavailable, the smoke test should be skipped with a clear message rather than failing unexpectedly.

New smoke test classes should follow this naming convention:

```text
*SmokeTest
```

The PR smoke workflow runs all classes matching this pattern.

### Build plugin

To build the plugin ZIP locally:

```bash
./gradlew clean buildPlugin
```

The generated plugin artifact is produced under:

```text
build/distributions/
```

### Plugin verifier

Run JetBrains plugin verification with:

```bash
./gradlew verifyPlugin
```

The verifier checks the plugin against the IDE targets declared in `build.gradle.kts` under `intellijPlatform.pluginVerification.ides`.

Current verification targets:

```text
PyCharm Community 2023.3.7
PyCharm Professional 2023.3.7
PyCharm Professional 2026.1
```

`2023.3.7` covers the pre-unified PyCharm model, where Community and Professional are separate IDE products.

`2026.1` covers the newer unified PyCharm distribution model, represented through the PyCharm Professional verifier target in the current Gradle configuration.

Before release, run:

```bash
./gradlew test
JCN_CONTAINER_SMOKE_ENABLED=true ./gradlew test --tests "*SmokeTest"
./gradlew clean buildPlugin
./gradlew verifyPlugin
```

---

## Rules for Contributors

* Do not bypass `AuthNotificationCoordinator`
* Do not call `ensureLoggedIn()` directly from polling paths
* Keep auth logic centralized
* Add logs for every decision branch
* Prefer pure, testable logic over UI-coupled code

---

## Cleanup Notes

Migration / legacy code:

```
LegacySettingsMigration*
```

Can be removed after stable release (1.0.0+).

---

