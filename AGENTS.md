# AGENTS.md

This file gives coding assistants the repository-specific constraints needed to make safe,
reviewable changes. Human contributors remain responsible for every change.

## Scope and architecture

| Module | Responsibility |
|---|---|
| `spring-ai-mcp-sigv4` | Boot-independent JDK HTTP request signing and endpoint routing |
| `spring-ai-mcp-sigv4-spring-boot-autoconfigure` | Properties, validation, and Spring AI integration |
| `spring-ai-mcp-sigv4-spring-boot-starter` | Consumer-facing dependency aggregation |
| `samples/agentcore-client` | Minimal usage example; never place reusable logic here |

Keep signing logic out of auto-configuration. Keep Spring Boot dependencies out of the core
module. Do not add AWS service clients: this library depends only on credentials, region, and HTTP
signing APIs.

## Security invariants

- Sign only exact endpoints configured under
  `spring.ai.mcp.client.authorization.aws.connections`.
- Require HTTPS by default. `allow-insecure-http` is only for trusted local tests.
- Resolve credentials for every request and never cache, log, serialize, or expose them.
- Never log endpoint URLs, `Authorization`, `X-Amz-Date`, `X-Amz-Security-Token`, account IDs, or
  session tokens.
- Copy only the SigV4 headers required by the signed request back from the AWS signer, including
  `X-Amz-Content-Sha256` when it appears in `SignedHeaders`. Preserve application and MCP headers
  already present on the JDK request builder.
- SigV4 must run after other ordered request customizers so their headers are covered by the
  signature.
- Do not combine an OAuth Bearer customizer and SigV4 on the same connection.

## Compatibility invariants

- Java 17 is the baseline.
- The `0.1.x` line targets Spring AI `2.0.x` and Spring Boot `4.1.x`.
- Spring AI 2.0.0 needs the named transport compatibility bridge. Later releases with native
  request-customizer collection must bypass that bridge.
- A direct transport builder call to `httpRequestCustomizer(...)` or
  `asyncHttpRequestCustomizer(...)` remains last-writer-wins because the MCP SDK exposes no
  getter. Do not claim that this case is automatically composed.
- Request bodies are nullable for bodyless GET and DELETE calls, even though the repository uses
  package-level `@NullMarked`.

## Change rules

- Preserve Apache 2.0 headers on every Java source.
- Add `@since` and useful JavaDoc to new public API. Comments must explain contracts or rationale,
  not restate code.
- Add configuration metadata and documentation with every new property.
- Keep unit tests focused. Use `*Tests` for local tests and `*IT` for opt-in live integration tests.
- Never make a live AWS test run by default or require long-lived credentials.
- Do not change provisional `org.springaicommunity` publication coordinates without confirmed
  namespace ownership.

## Verification

Run before handing off a change:

```shell
./gradlew clean check publishToMavenLocal
```

When AWS integration behavior changes, also run `AgentCoreGatewaySigV4IT` with the environment
documented in `.env.example`, using short-lived credentials and a disposable test gateway.
