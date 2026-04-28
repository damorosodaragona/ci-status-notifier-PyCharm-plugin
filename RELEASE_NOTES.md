# Jenkins CI Notifier Release Notes

## v1.0.0

Jenkins CI Notifier 1.0.0 is the first stable release for PyCharm and IntelliJ-based IDEs.

### Main features

- GitHub commit status polling for the current Git revision.
- Jenkins mode with CI Status tool window, job tree scanning, branch-aware job selection, and manual job fallback.
- Pipeline stage summary, build status notifications, artifacts list, and HTML artifact preview.
- Settings for GitHub token, Jenkins base URL, scan root, username/API token, polling interval, and notification preferences.
- JetBrains Password Safe storage for tokens and credentials.
- Experimental Keycloak/OIDC web-session recovery for Jenkins setups that require browser login.

### Known limits

- Keycloak/OIDC auto-login is experimental and depends on the Jenkins/identity-provider login flow.
- Root Jenkins scans require Jenkins permissions that can read the configured scan root.
