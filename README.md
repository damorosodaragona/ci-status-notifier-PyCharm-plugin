# CI Status Notifier

Small PyCharm/IntelliJ plugin that polls GitHub commit statuses for the current Git commit and shows IDE notifications when CI status changes.

It is designed for repositories where Jenkins publishes commit statuses to GitHub, so the IDE does not need direct Jenkins access or a Jenkins plugin.

## Local Development

Open this folder as a Gradle project in PyCharm or IntelliJ IDEA:

```bash
ide-plugins/ci-status-notifier
```

Useful Gradle tasks:

```bash
./gradlew runIde
./gradlew buildPlugin
```

If the Gradle wrapper is not present, import the project in the IDE and let JetBrains create/use a Gradle runtime, or install Gradle locally.

## Installation from ZIP

Build the plugin ZIP:

```bash
./gradlew buildPlugin
```

The generated ZIP is created under:

```text
build/distributions/
```

You can also download the ZIP from the latest GitHub Release:

```text
https://github.com/damorosodaragona/ci-status-notifier-PyCharm-plugin/releases/latest
```

Install it in PyCharm or IntelliJ IDEA:

1. Open `Settings | Plugins`.
2. Click the gear icon.
3. Select `Install Plugin from Disk...`.
4. Choose the ZIP file from `build/distributions/` or from the GitHub Release assets.
5. Restart the IDE when prompted.

## Configuration

After installing/running the plugin:

```text
Settings | Tools | CI Status Notifier
```

Configure GitHub status mode:

- GitHub repository in `owner/name` format.
- Optional GitHub token. Required for private repositories or high polling frequency.
- Poll interval in seconds.

Configure Jenkins mode:

- Jenkins base URL.
- Optional Jenkins scan root. Leave blank to scan from the Jenkins root, or set a raw (`job/Folder/job/project`) or slash-separated (`Folder/project`) path to narrow the scan. Root scans require Jenkins permissions that allow reading the global Jenkins root.
- Optional Jenkins username and API token.
- Poll interval in seconds.

Jenkins mode adds a `CI Status` tool window that scans the configured Jenkins root, auto-selects the build job that best matches the current Git branch, and still shows the Jenkins job tree as a manual fallback. Selecting a job shows its latest build, Pipeline stages, artifacts, and an in-IDE preview for HTML report artifacts when the IDE supports embedded browser rendering. HTML previews download all artifacts for the selected build into the IDE cache while preserving artifact paths, so linked CSS, scripts, images, and relative links keep working locally.

Tokens are stored in the JetBrains Password Safe.
