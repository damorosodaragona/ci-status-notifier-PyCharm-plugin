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

