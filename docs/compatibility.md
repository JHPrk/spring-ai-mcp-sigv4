# Compatibility policy

## Current baseline

| Library release | Java runtime | Build JDK | Spring AI | Spring Boot | MCP Java SDK |
|---|---:|---:|---:|---:|---:|
| `0.1.x` | 17+ | Liberica 17.0.19+ | `2.0.2+` within `2.0.x` | `4.1.2+` within `4.1.x` | `2.1.0`, aligned with Spring AI |

The first public release requires Spring AI 2.0.2 and MCP SDK 2.1's additive request-customizer
API. Spring AI 2.0.0/2.0.1 and the former capability-detected fallback are no longer supported.
No runtime reflection is used to detect or bridge transport API shapes.

AWS SDK dependencies use the repository's 2.59.x BOM. Consumers may use a newer compatible v2 BOM.
Credential providers such as AssumeRole, IRSA, and IAM Identity Center require their optional
runtime modules; see [configuration](configuration.md#optional-credential-modules).

## Verification

CI compiles Java 17 bytecode with Liberica 17.0.19 and runs the complete checks on Java 17 and 21.
`-PtestJavaVersion=21` selects the test/consumer JVM without changing published bytecode.
`check` includes real OpenTelemetry javaagent tests, publication metadata checks, and independent
Maven/POM and Gradle/module-metadata consumer projects. Consumers start Boot auto-configuration
and deliver a signed MCP initialization request to a loopback server; no AWS credentials are needed.

## Policy

- A minor library line targets one Spring AI minor line; the table states the supported minimum.
- Patch releases do not intentionally break public API or configuration keys.
- Milestone, RC, and snapshot framework dependencies are not used in a stable library release.
- Spring AI 2.1 RCs may be evaluated separately; they are not part of the current support claim.
- Spring AI 1.1 / Boot 3.5 support would require a separate maintenance line if there is demand.
- The provisional Maven namespace must be confirmed before any public publication.
