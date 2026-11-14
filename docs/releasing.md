# Releasing

## Before the first release

1. Confirm ownership of the Maven group and GitHub repository.
2. Agree whether the first release uses Community hosting or an independently owned namespace;
   keep that decision separate from the implementation/verification work.
3. Update all publication identity values in `gradle.properties`.
4. Change `projectVersion` from `-SNAPSHOT` to the intended release.
5. Add release notes to `CHANGELOG.md`.
6. Run the local and live validation commands below.

## Local validation

```shell
./gradlew clean check publishToMavenLocal
./gradlew verifyConsumers -PprojectVersion=0.1.0-RC1
```

`check` publishes to `build/verification-repository` and runs independent Maven and Gradle
consumer projects. `build/verification-artifacts.sha256` records the exact verified artifacts.
Run the same checks with `-PtestJavaVersion=21` before release. Python 3 and Maven 3.9+ are required.
The RC command exercises non-snapshot publication filenames and dependency resolution using only
the local verification repository. It does not publish externally or change `gradle.properties`.

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

