# CI Status Notifier

CI Status Notifier is a PyCharm/IntelliJ plugin that keeps the current project's CI state visible inside the IDE.

It can read GitHub commit statuses or query Jenkins directly, then shows notifications when the build changes state. In Jenkins mode it also adds a **Jenkins CI** tool window with the detected job, latest build status, Pipeline stages, build artifacts, and HTML report previews when embedded browser rendering is available.

## Configure the Plugin

Open:

```text
Settings | Tools | Jenkins CI Notifier
```

Enable **Poll CI statuses**, choose a provider, then fill in the fields for your setup.

### GitHub Status Mode

Use this when Jenkins or another CI service publishes commit statuses to GitHub.

- **GitHub repository**: repository in `owner/name` format.
- **GitHub token**: optional, but recommended for private repositories or frequent polling.
- **Poll interval seconds**: how often the plugin checks for status changes.
- **Notifications**: choose whether pending, successful, and failed/error statuses should trigger IDE notifications.

### Jenkins Mode

Use this when the plugin should talk to Jenkins directly.

- **Jenkins URL**: base URL for your Jenkins instance.
- **Jenkins scan root**: optional path used to narrow job discovery. Leave it blank to scan from the Jenkins root, use a raw path such as `job/Folder/job/project`, or use a slash-separated path such as `Folder/project`.
- **Jenkins username** and **Jenkins API token**: optional credentials for Jenkins API access.
- **Test Jenkins connection**: checks the current Settings values and reports what the plugin can reach.
- **Poll interval seconds** and **Notifications**: control polling cadence and notification types.

Jenkins mode tries to match the current Git branch to the best Jenkins job. If automatic detection is not enough, the tool window still shows the Jenkins job tree so you can select a job manually.

## Authentication

Tokens and web passwords are stored in the JetBrains Password Safe.

The recommended Jenkins setup is API access with a Jenkins API token that does not require an active browser/OIDC session.

If your Jenkins instance requires Keycloak or another OIDC web session, the plugin includes experimental options:

- **Keycloak interactive login fallback** opens a browser login flow when API access fails.
- **Keycloak auto-login** tries to restore the web session in the background using stored web credentials.
- **Keycloak authentication debug log** adds diagnostic log entries for auth troubleshooting.

These options are experimental because OIDC and Jenkins gateway behavior can vary by server and IDE version.

## Artifacts and Preview

When Jenkins mode finds a build, the tool window can list artifacts for that build. HTML report artifacts can be opened in an IDE preview when supported. The plugin downloads the selected build's artifacts into the IDE cache while preserving relative paths, so linked CSS, scripts, images, and report pages can render locally.

## Install from ZIP

Build the plugin ZIP:

```bash
./gradlew buildPlugin
```

The ZIP is created under:

```text
build/distributions/
```

Install it from the IDE:

1. Open `Settings | Plugins`.
2. Click the gear icon.
3. Select `Install Plugin from Disk...`.
4. Choose the ZIP from `build/distributions/`.
5. Restart the IDE if prompted.

You can also use the latest GitHub Release when one is available:

```text
https://github.com/damorosodaragona/ci-status-notifier-PyCharm-plugin/releases/latest
```
