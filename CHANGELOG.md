# Changelog

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic
versioning after the first stable release.

## [Unreleased]

### Added

- AWS SigV4 signing for MCP Streamable HTTP GET, POST, and DELETE requests.
- Connection-scoped Spring Boot auto-configuration and starter.
- Per-connection region, service name, and secure HTTP validation.
- Unit, local lifecycle, auto-configuration, and opt-in AgentCore integration tests.
- Multi-connection coverage for shared AWS credentials, per-request signing, and unsigned public
  endpoints.
- Maven publication metadata, CI, governance, sample, and maintainer documentation.
- Spring Java Format, Spring AI-aligned Checkstyle, Error Prone, and NullAway quality gates.
- Gradle wrapper validation and repository guidance for maintainers and coding assistants.

### Fixed

- Reject pre-existing SigV4-owned headers case-insensitively before credential resolution,
  preventing stale session tokens or signing metadata from surviving request signing.
- Remove configured endpoint URLs from duplicate-route and conflicting-scope diagnostics.
- Declared bodyless MCP request payloads as nullable under the package-level JSpecify contract.
- Corrected and completed public JavaDoc for routing and configuration property types.
- Preserved the AWS signer's `X-Amz-Content-Sha256` header when it is part of `SignedHeaders`,
  fixing live AgentCore Gateway authentication for requests with a body.
- Bound direct and routed signers to exact endpoints and reject competing `Authorization`
  headers.
- Registered fallback AWS credentials and region providers as conditional Spring beans so their
  lifecycle and application overrides are handled consistently.
