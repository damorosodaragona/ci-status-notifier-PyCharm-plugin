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

## Configuration

After installing/running the plugin:

```text
Settings | Tools | CI Status Notifier
```

Configure:

- GitHub repository in `owner/name` format.
- Optional GitHub token. Required for private repositories or high polling frequency.
- Poll interval in seconds.

The token is stored in the JetBrains Password Safe.
