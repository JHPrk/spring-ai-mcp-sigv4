# Changelog

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning after the first stable release.

## [Unreleased]

### Changed

- Consolidate user and contributor documentation, adopt the Spring code of conduct, and provide actionable support/security channels.
- Replace Python publication verification with Gradle/JDK tasks; keep artifact checks in `check` and run independent consumers explicitly with `verifyConsumers` in CI and releases.
- Move independent authorization settings to `mcp.sigv4.connections`; reject the old pre-release namespace with migration guidance.
  Spring AI connection properties keep their existing names.
- Narrow core dependencies to MCP core, Spring Core, and AWS APIs; keep Spring AI integration in the auto-configuration and starter modules.
- Select endpoint-bound signers directly by connection name.
- Require Spring AI 2.0.2, Spring Boot 4.1.2, and the aligned MCP SDK 2.1.0; use AWS SDK 2.59.0.
- Replace capability detection and the old fallback with named additive transport installation.
- Use OpenTelemetry Java Agent 2.32.0 and NullAway 0.14.3 for verification.
- Pin GitHub Actions to commit SHAs and the Gradle distribution to its official SHA-256 digest.

### Added

- Java 17/21 runtime verification and isolated Maven/Gradle published-starter consumer checks.
- Local non-snapshot RC consumer verification, with fresh library artifact resolution even when the same release version was previously present in the isolated Maven cache.
- AWS credential-source documentation for optional STS, SSO, and SSO OIDC runtime modules.
- Opt-in live starter coverage through Boot auto-configuration and the standard ToolCallback provider; instrumented core live verification with all telemetry exporters disabled.
- Boot-independent `AwsSigV4HeaderSigningPolicy` and default/all/excluding factories, preserving the existing request-customizer constructor.
- A replaceable Spring base policy bean and per-connection `signing.additional-unsigned-headers` with case normalization, blank rejection, and deduplication.
- Local `otelAgentTest` in `check`, using a real OpenTelemetry Java Agent, on-wire signature recomputation, and negative controls without AWS or a collector.
- AWS SigV4 signing for MCP Streamable HTTP GET, POST, and DELETE requests.
- Connection-scoped Spring Boot auto-configuration and starter.
- Per-connection region, service name, and secure HTTP validation.
- Unit, local lifecycle, auto-configuration, and opt-in AgentCore integration tests.
- Multi-connection coverage for shared AWS credentials, per-request signing, and unsigned public endpoints.
- Maven publication metadata, CI, governance, sample, and maintainer documentation.
- Spring Java Format, Spring AI-aligned Checkstyle, Error Prone, and NullAway quality gates.
- Gradle wrapper validation and repository guidance for maintainers and coding assistants.

### Fixed

- Track additional configuration metadata as a Java compilation input, including resource-only changes; verify generated descriptions and defaults in the published JAR.
- Publish resolved dependency versions in Gradle module metadata so clean Gradle consumers can resolve the starter without relying on another build's dependency-management plugin.
- Order verification publications after root cleanup so parallel `clean check` builds cannot delete the repository while subprojects are publishing into it.
- Keep unsigned connection aliases of a signed URL unsigned on the native Spring AI path.
- Preserve native and earlier named request customizers before signing; verify actual HTTP delivery.
- Reject rewritten URI/method metadata before credential lookup and redact invalid endpoint parsing diagnostics; reject configured user info, fragments, and missing hosts.
- Always execute requested live tests instead of reusing cached or up-to-date results, and require invocation inputs whenever live verification is enabled.
- Late-bound W3C, B3, and X-Ray propagation headers remain on the wire while being excluded from signing input by default, allowing instrumented JDK HTTP client propagation after signing.
  Stable application/MCP headers remain eligible; the AWS signer retains its own exclusions.
- Named connections at the same endpoint retain independent scopes and header exclusions.
- Reject pre-existing SigV4-owned headers case-insensitively before credential resolution, preventing stale session tokens or signing metadata from surviving request signing.
- Remove configured endpoint URLs from duplicate-route and conflicting-scope diagnostics.
- Declared bodyless MCP request payloads as nullable under the package-level JSpecify contract.
- Corrected and completed public JavaDoc for routing and configuration property types.
- Preserved the AWS signer's `X-Amz-Content-Sha256` header when it is part of `SignedHeaders`, fixing live AgentCore Gateway authentication for requests with a body.
- Bound direct and routed signers to exact endpoints and reject competing `Authorization` headers.
- Registered fallback AWS credentials and region providers as conditional Spring beans so their lifecycle and application overrides are handled consistently.
