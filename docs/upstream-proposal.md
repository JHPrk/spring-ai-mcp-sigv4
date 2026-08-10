# Spring AI upstream proposal package

Do not open a large code PR first.
The feature adds an AWS dependency and an authentication policy surface, so start with a focused
enhancement issue or discussion and link this repository as the working implementation.

## Recommended destination

The current implementation is ready to propose as a Spring AI Community incubating project. It
matches two established patterns:

- `mcp-security` keeps MCP security concerns in separate core and Boot integration modules and is
  referenced by the Spring AI documentation;
- `spring-ai-agentcore` demonstrates that AWS-specific Spring AI integrations and AWS SDK
  dependencies are acceptable in the community organization.

There is no direct overlap with `spring-ai-agentcore`: that project provides AgentCore runtime,
memory, browser, code-interpreter, and related integrations, while this project authenticates an
outbound Spring AI MCP Streamable HTTP client. Collaboration or eventual placement as a narrowly
scoped AgentCore submodule is possible, but would make the reusable MCP/IAM boundary less clear.

For Spring AI core, the repository is issue-ready rather than PR-ready. Maintainers should first
decide whether a cloud-specific authentication implementation belongs upstream and whether named
connections need a first-class customizer hook. If they choose a community extension, this
repository can release without changing its public API. If they choose core, only the accepted
core and auto-configuration slices should be ported; the released-version compatibility bridge
must not be copied into current `main`.

## Copy-ready issue title

`Add AWS SigV4 authentication for MCP Streamable HTTP clients`

## Copy-ready issue body

Spring AI MCP clients can connect to Streamable HTTP endpoints protected by OAuth, but an
IAM-protected endpoint such as Amazon Bedrock AgentCore Gateway requires AWS Signature Version 4
on every MCP HTTP request.

I have a tested reference implementation that signs JDK HttpClient MCP requests through
`McpAsyncHttpClientRequestCustomizer` using AWS SDK v2 `AwsV4HttpSigner`.
It covers POST, resumable GET, and session-closing DELETE requests, request bodies, temporary
credentials, and per-connection region/service scopes.

Proposed configuration:

```yaml
spring.ai.mcp.client.streamable-http.connections.agentcore:
  url: https://gateway.example
  endpoint: /mcp
spring.ai.mcp.client.authorization.aws.connections.agentcore:
  region: ap-northeast-2
  service-name: bedrock-agentcore
```

The implementation contributes an ordered request customizer that signs only endpoints whose
connection names appear under `authorization.aws.connections`.
It leaves other MCP connections unchanged, rejects unknown connection names and conflicting
endpoint scopes, uses an application `AwsCredentialsProvider` when available, and otherwise uses
the lifecycle-managed AWS default provider chain.
HTTPS is required by default.
Each signer is bound to an exact endpoint, and a competing `Authorization` header is rejected
instead of being silently replaced.

Before preparing a framework PR, I would like maintainer guidance on placement:

1. official Spring AI core/autoconfiguration/starter modules;
2. the Spring AI Community organization; or
3. a documented extension point improvement first, followed by a community module.

The reference repository includes unit, auto-configuration, local HTTP lifecycle, and opt-in
AgentCore integration tests.
It also contains a compatibility bridge for Spring AI 2.0.0, whose released transport
auto-configuration does not yet collect request-customizer beans; that bridge would not be part of
an upstream implementation targeting current `main`.

## Recommended PR split after maintainer agreement

### PR 1: core signer

- Add the JDK HTTP to AWS SDK request adapter.
- Add `AwsSigV4McpRequestCustomizer` and unit tests.
- Cover GET, POST, DELETE, UTF-8 bodies, temporary credentials, restricted headers, and secret-free
  diagnostics.
- Do not add Boot configuration in this PR.

### PR 2: MCP client auto-configuration

- Add AWS authorization properties keyed by named Streamable HTTP connection.
- Add endpoint routing, HTTPS and name validation, metadata, and `ApplicationContextRunner` tests.
- Add optional AWS SDK dependencies to the autoconfiguration module.

### PR 3: starter and reference documentation

- Add the starter dependency wiring.
- Add reference documentation and one sample.
- Add an opt-in integration test profile if maintainers want it in the main repository.

## Mechanical changes for an official Spring AI PR

- Package: `org.springaicommunity.mcp.sigv4` → maintainer-selected
  `org.springframework.ai...` package.
- Build: translate Gradle module definitions to Spring AI Maven modules and BOM entries.
- Add Spring source headers, `@since` values matching the target Spring AI release, NullAway
  coverage, Spring Java Format, and Antora documentation.
- Run `./mvnw clean package`, relevant integration tests, and `./mvnw process-sources`.
- Use a human-reviewed commit with a DCO `Signed-off-by` trailer.

## Acceptance questions

- Should AWS SigV4 remain MCP-specific or use a general Spring AI AWS HTTP signing utility?
- Is endpoint routing acceptable, or should Spring AI expose a named request-customizer hook so
  authentication can be attached without URI matching?
- Should `AwsCredentialsProvider` be supplied by a connection-details abstraction instead of a
  single application bean?
- Is `spring.ai.mcp.client.authorization.aws` the preferred property namespace?

## Evidence to attach

- `./gradlew clean check publishToMavenLocal` passes on Java 17.
- Published library modules contain binary, source, and Javadoc JARs, POMs, and Gradle metadata.
- Pipeline tests cover no customizer, sync-only, async-only, mixed, and direct builder replacement.
- Signing tests cover request bodies, temporary credentials, and GET/POST/DELETE lifecycle calls.
- Spring Java Format, Spring AI-aligned Checkstyle, Error Prone, and NullAway run in `check`.
- The opt-in real-service test passed against a disposable IAM-authenticated AgentCore Gateway on
  2026-08-10, including initialize, tool discovery, tool invocation, and graceful close.
