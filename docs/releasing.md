# Releasing

## Before the first release

1. Confirm ownership of the Maven group and GitHub repository.
2. Update all publication identity values in `gradle.properties`.
3. Change `projectVersion` from `-SNAPSHOT` to the intended release.
4. Add release notes to `CHANGELOG.md`.
5. Run the local and live validation commands below.

## Local validation

```shell
./gradlew clean check publishToMavenLocal
```

Verify that every published module contains a binary JAR, sources JAR, Javadoc JAR, Gradle module
metadata, and a POM with license and SCM data.

## Remote publication inputs

The Gradle build creates a remote `release` repository only when
`MAVEN_REPOSITORY_URL` is present.
The following environment variables are supported:

```text
MAVEN_REPOSITORY_URL
MAVEN_REPOSITORY_USERNAME
MAVEN_REPOSITORY_PASSWORD
PGP_SIGNING_KEY
PGP_SIGNING_PASSWORD
```

Run:

```shell
./gradlew clean check publish
```

The repository endpoint must implement a Maven-compatible deployment flow.
For Maven Central, configure the current Central Portal workflow used by the owning organization;
do not publish under `org.springaicommunity` without namespace authorization.

## Tagging

- Create a signed `v<version>` tag only after the remote staging repository validates.
- Do not rebuild artifacts between staging and release.
- Restore the next `-SNAPSHOT` version after release.

