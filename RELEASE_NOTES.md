# Release Notes

## v1.0.0

This is the first stable MVP release of Jenkins CI Notifier.

### Highlights

- Poll GitHub commit statuses for the current Git commit.
- Query Jenkins directly and display the selected job in the Jenkins CI tool window.
- Match Jenkins jobs against the current Git branch, with a manual job-tree fallback.
- Show latest build state, Pipeline stages, and build artifacts.
- Preview HTML report artifacts inside the IDE when embedded browser support is available.
- Configure GitHub and Jenkins providers from `Settings | Tools | Jenkins CI Notifier`.
- Store GitHub, Jenkins, and Keycloak credentials in the JetBrains Password Safe.
- Provide experimental Keycloak/OIDC interactive login and auto-login support for Jenkins installations that require a web session.

### Verification

Before tagging the release, run:

```bash
./gradlew clean test verifyPlugin buildPlugin
```

Then create the annotated tag after the release PR is merged:

```bash
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```
