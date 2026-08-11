# Spring AI Community project request

This document is the copy-ready application for the Spring AI Community
[New Project Request](https://github.com/spring-ai-community/community/issues/new?template=application.yml).
Submit it before opening a large Spring AI framework pull request.

## Issue title

`[Project Request] spring-ai-mcp-sigv4`

## Project Name

`spring-ai-mcp-sigv4`

## Project Description

Spring AI MCP SigV4 provides AWS Signature Version 4 authentication for outbound Spring AI MCP
Streamable HTTP clients. It enables Spring applications to call IAM-protected MCP endpoints, such
as Amazon Bedrock AgentCore Gateway, without application-specific signing code.

The project contains a Boot-independent signer and endpoint router, Spring Boot auto-configuration,
a consumer starter, and a minimal AgentCore client sample. Authorization is configured per named
Spring AI MCP connection. The implementation signs POST requests, resumable GET requests, and
session-closing DELETE requests; supports request bodies and temporary AWS credentials; preserves
existing MCP and application headers; requires HTTPS by default; and rejects conflicting Bearer and
SigV4 authorization.

The integration composes with ordered synchronous and asynchronous MCP request customizers and
ensures that SigV4 runs last so headers contributed by earlier customizers are covered by the
signature. It supports Spring AI 2.0.x, including a compatibility bridge for the 2.0.0 transport
configuration shape. The bridge is bypassed when the framework provides native request-customizer
collection.

The intended users are teams connecting Spring AI applications to IAM-protected MCP services. The
scope is deliberately limited to outbound MCP HTTP authentication; it does not add AWS service
clients or manage AgentCore resources.

## Exising Repository URL with POC implementation

https://github.com/JHPrk/spring-ai-mcp-sigv4

## Integration with Spring AI

The project extends Spring AI's named MCP Streamable HTTP client configuration with connection-
scoped AWS authorization properties. Its auto-configuration contributes the Spring AI MCP request
customizer types used by the JDK HTTP transport and routes each request only to an exactly matched,
configured endpoint.

This fills a gap between OAuth-protected MCP clients and MCP endpoints protected by AWS IAM. It
does not replace Spring AI's existing customizers: sync-only, async-only, and mixed customizer
pipelines are composed in order, with SigV4 applied last. Unconfigured connections are unchanged.

The repository includes focused signing and auto-configuration tests, local HTTP lifecycle tests,
and an opt-in live test. The live test was successfully run against a disposable IAM-authenticated
Amazon Bedrock AgentCore Gateway, covering initialization, tool discovery, tool invocation, and
graceful session close.

## Existing Documentation

https://github.com/JHPrk/spring-ai-mcp-sigv4#readme

Additional design and configuration documents are available at:

https://github.com/JHPrk/spring-ai-mcp-sigv4/tree/main/docs

## Development Team

- Jaehyeon Park ([@JHPrk](https://github.com/JHPrk)) — project lead and maintainer

## Project Requirements

- [x] Has working proof of concept that demonstrates integration with Spring AI
- [x] Includes unit and integration tests
- [x] Uses or will use Apache 2 license
- [x] All development will occur in a public repository
- [x] Agrees to follow the Spring AI code of conduct
- [x] Will provide clear contribution guidelines
- [x] Will follow semantic versioning (MAJOR.MINOR.PATCH)

## Preferred Packaging Method

Select **Using GitHub's process with io.github.spring-ai-community as the groupId** in the issue
form. The exact publication coordinate should be confirmed during onboarding as described under
Additional Information.

## Commercial Relationship

Leave **This project has commercial ownership/control (single-vendor)** unchecked.

## Additional Information

This project is complementary to `spring-ai-agentcore`. That project provides integrations for
AgentCore runtime, memory, browser, code interpreter, and related services. `spring-ai-mcp-sigv4`
has a narrower reusable boundary: it authenticates outbound Spring AI MCP HTTP requests to any
matching IAM-protected endpoint and does not provision or operate AgentCore resources.

The repository currently uses the provisional `org.springaicommunity` publication group and has
not been released to Maven Central. The Community application form and release guide currently
show different spellings for the preferred GitHub-managed group. The project will migrate to the
coordinate confirmed by the Community leads during onboarding before its first public release.
No namespace ownership is assumed by the current coordinates.

The implementation targets Spring AI 2.0.x and Java 17. CI runs formatting, Checkstyle, Error
Prone, NullAway, unit and local integration tests, and publication-to-Maven-local verification.
Live AWS tests are opt-in, use the default AWS credential provider chain, and require disposable
resources and short-lived credentials.

If the maintainers prefer an official Spring AI framework contribution, the accepted core and
auto-configuration portions can be translated to Spring AI's Maven build and current native
request-customizer collection path. The compatibility bridge for the released Spring AI 2.0.0
shape would remain outside that upstream change.
