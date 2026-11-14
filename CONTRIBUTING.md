# Contributing

Search existing [issues](https://github.com/JHPrk/spring-ai-mcp-sigv4/issues) and [discussions](https://github.com/JHPrk/spring-ai-mcp-sigv4/discussions) before starting a change.
Discuss public API, configuration, dependency baselines, and upstream scope before implementation.
Follow the [code of conduct](CODE_OF_CONDUCT.md) and use [private reporting](SECURITY.md) for suspected vulnerabilities.
Never include credentials, signed headers, account IDs, private endpoints, tool payloads, or Terraform state in contributions.

## Development

Use the Liberica Java 17 toolchain in `.sdkmanrc` and install Java 21 to reproduce the runtime matrix.
Maven 3.9+ is required for independent consumer verification; the Gradle wrapper supplies Gradle.

```shell
./gradlew clean check verifyConsumers publishToMavenLocal
./gradlew check verifyConsumers -PtestJavaVersion=21
./gradlew verifyConsumers -PprojectVersion=0.1.0-RC1
```

Use focused module tasks while iterating, then run the full commands before submitting a pull request.
`-PtestJavaVersion` selects the test and consumer JVM; published bytecode remains Java 17.
The RC command tests non-snapshot artifacts locally without changing the project version or publishing externally.

`check` runs unit and local protocol tests, Spring Java Format, Checkstyle, Error Prone, NullAway, source-header checks, Javadoc generation, publication checks, and the real OpenTelemetry agent regression.
`verifyPublication` checks binary/source/Javadoc JARs, SHA-256 sidecars, POM identity, resolved dependency versions, Java 17 bytecode, auto-configuration registration, and generated property descriptions/defaults.
Its manifest is `build/verification-artifacts.sha256`.

`verifyConsumers` additionally runs independent Maven and Gradle starter projects against `build/verification-repository`, with no project substitution.
Both load Boot auto-configuration and deliver signed MCP initialization to a loopback server using synthetic credentials.
A third Maven consumer delivers a signed UTF-8 request using only the core artifact and verifies that its runtime classpath contains no Spring AI, Boot, or JSON mapper.
Maven uses an isolated cache under `build/consumer-verification/maven-local`; each run evicts the tested library version, including RC versions, while retaining third-party downloads.
Consumer logs are in `build/consumer-verification/<kind>/verification.log`.
CI runs these checks on Java 17/21 and tests an RC on Java 17.
All generated artifacts stay in ignored build directories.

### Instrumented HTTP tests

The normal `check` includes `:spring-ai-mcp-sigv4:otelAgentTest`, using a real upstream agent and a loopback server with all exporters disabled.
It verifies send-time propagation, signed stable headers and bodies, and negative controls without AWS or a collector.
All telemetry dependencies are test-only.
For an optional [ADOT agent](https://github.com/aws-observability/aws-otel-java-instrumentation/releases), run:

```shell
./gradlew :spring-ai-mcp-sigv4:otelAgentTest \
  -PotelAgentJar=/absolute/path/aws-opentelemetry-agent.jar --rerun-tasks
```

An ADOT override is separate from the upstream agent result in CI.

### Live AWS tests

Live tests are opt-in, tagged `integration`, and excluded from default tests and `check`.
Use short-lived credentials and an authorized disposable IAM Gateway; see [.env.example](.env.example) for required endpoint, tool, arguments, region, and optional expected-result inputs.
These tasks do not provision resources:

```shell
./gradlew --no-daemon :spring-ai-mcp-sigv4:integrationTest \
  -PtestJavaVersion=21 -PagentCoreItTracing=true
./gradlew --no-daemon :spring-ai-mcp-sigv4-spring-boot-starter:integrationTest
```

The core test verifies initialization, discovery, and invocation.
`-PagentCoreItTracing=true` attaches the agent, disables exporters, and requires active propagation that remains present but unsigned.
Add `-PotelAgentJar=/absolute/path/aws-opentelemetry-agent.jar` to use ADOT instead.
The starter test uses Boot's actual asynchronous MCP client and `AsyncMcpToolCallbackProvider`; it expects an echo tool accepting a JSON `message` string and requires `AWS_REGION`.
Set `MCP_IAM_IT_EXPECTED_RESULT_JSON` to compare the core tool response without printing its contents.
Keep wire logging disabled; never publish raw request headers, session IDs, tool data, or endpoints.

Live tasks always execute when requested, without cached or up-to-date success.
Without the required environment, live verification is skipped and must not be reported as passing.
Session-aware DELETE and SSE reconnect depend on the server issuing sessions and accepting streams; local coverage does not establish live Gateway support.
Run live tests when AWS integration behavior changes and before release, with explicit authorization for any infrastructure mutations and full teardown afterward.

## Style and review

Follow [Spring AI's contribution conventions](https://github.com/spring-projects/spring-ai/blob/v2.0.2/CONTRIBUTING.md).
Use UTF-8, LF, no trailing whitespace, tabs in Java, explicit imports, and one top-level Java type per file.
Write one sentence per line in Markdown prose and keep examples copyable.
Apply Spring Java Format with:

```shell
./gradlew format
```

Use `*Tests` for local tests and `*IT` for live tests, and prefer focused behavioral assertions.
Main sources compile with Error Prone and NullAway in JSpecify mode; suppressions must be narrow and explain a framework constraint.
New public API needs useful Javadoc, `@since`, compatibility review, and a changelog entry.
Follow [Spring Javadoc conventions](https://github.com/spring-projects/spring-framework/blob/main/CONTRIBUTING.md#javadoc), including imperative method summaries, parameter/return contracts, and paragraphs starting with `<p>`.
Javadoc warnings fail the build.
New properties need binding tests, generated/additional metadata, and an update to [configuration.md](docs/configuration.md).

A pull request should explain the problem, resulting behavior, security/compatibility impact, and validation.
AI-assisted work requires review by a human contributor who remains accountable; automated checks do not satisfy that review.
Use imperative commit titles without `feat:` or `docs:` prefixes, preferably under 50 characters, and include a DCO `Signed-off-by` trailer (`git commit --signoff`).
See [AGENTS.md](AGENTS.md) for repository invariants.
Keep durable user/developer documentation here and in the configuration/design references; avoid dated review reports, generated logs, and duplicate operational checklists in Git.

## Maintainer and release workflow

Maintain review protection and required CI on `main`, private vulnerability reporting, and redacted issue triage.
Revisit code ownership and policy links when maintainers or hosting change.
Changes to public API, property names, baselines, or signing behavior need compatibility review.

Before the first publication, confirm repository and Maven namespace ownership and update identity values in `gradle.properties`.
The current `org.springaicommunity` coordinates are provisional and must not be published without authorization.
Select the release version, update [CHANGELOG.md](CHANGELOG.md), and run the Java 17/21, local RC, and authorized live checks above.
Review binary/source/Javadoc JARs, POM/module metadata, signatures, and checksums.

The remote Maven-compatible repository is configured only when `MAVEN_REPOSITORY_URL` is present:

```text
MAVEN_REPOSITORY_URL
MAVEN_REPOSITORY_USERNAME
MAVEN_REPOSITORY_PASSWORD
PGP_SIGNING_KEY
PGP_SIGNING_PASSWORD
```

The manually dispatched release workflow uses the `release` environment and runs `./gradlew --no-daemon clean check verifyConsumers publish`.
For Maven Central, use the namespace owner's current Central Portal deployment process.
Validate remote staging before creating a signed `v<version>` tag from the exact build commit.
Do not rebuild between staging and release; publish compatibility/security release notes and restore the next snapshot version afterward.
