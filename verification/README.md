# Published artifact verification

Run from the repository root with Java 17.0.19+, Python 3, and Maven 3.9+:

```shell
./gradlew clean check publishToMavenLocal
./gradlew check -PtestJavaVersion=21
./gradlew verifyConsumers -PprojectVersion=0.1.0-RC1
```

`verifyConsumers` is part of `check`. It publishes the three library modules to
`build/verification-repository`, checks binary/source/Javadoc JARs, POM identity, resolved dependency
versions in Gradle metadata, auto-configuration registration, Java 17 bytecode, and SHA-256 digests.
It also checks generated connection/property descriptions and defaults in the auto-configuration JAR.
It then creates independent Maven and Gradle builds under `build/consumer-verification`.
The Maven build uses an isolated local repository; neither consumer uses project substitution.
Before each Maven run, the verifier removes the tested library version from that isolated cache.
This also guarantees fresh resolution for non-snapshot RC artifacts, which `mvn -U` can otherwise
reuse. Third-party dependency downloads are retained. CI covers snapshot artifacts on both Java
runtimes and a local RC publication on Java 17; no external release is made.

Both consumers load the actual published starter through Boot auto-configuration and deliver a
SigV4-signed MCP initialization request to a loopback server. Test credentials are supplied by a
local bean. No AWS service or credential lookup is involved. The shared consumer source is also
part of the starter's test source set so Spring Java Format and Checkstyle cover it.

A third, core-only Maven consumer sends a signed UTF-8 request using the published core JAR.
Its actual test classpath is checked for the absence of Spring AI, Boot, and JSON mapper artifacts.
Its source is included in the core test source set for the same format and Checkstyle checks.
Provider coexistence with Spring Cloud AWS and classpath backoff are maintained in the
auto-configuration module tests, using synthetic credentials without AWS requests.

Consumer output is saved to each generated project's `verification.log`. Publication checksums are
in `build/verification-artifacts.sha256`. All generated projects, repositories, and logs stay in
ignored build directories. A public release additionally needs authorized namespace ownership,
signing, live AWS verification, and remote staging as described in `docs/releasing.md`.
