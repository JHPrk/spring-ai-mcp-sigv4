# Spring AI MCP SigV4

AWS Signature Version 4 authentication for Spring AI MCP Streamable HTTP clients.
It is designed for IAM-protected MCP endpoints such as Amazon Bedrock AgentCore Gateway.

> This repository is a proposal-ready community extension, not an official Spring AI
> artifact and not yet published to Maven Central.

## Project status

The code is already split into consumable library, auto-configuration, and starter artifacts.
`publishToMavenLocal` produces binary, source, and Javadoc JARs plus Maven POM and Gradle module
metadata for each published module.

| Target | Current assessment | What remains |
|---|---|---|
| Standalone open-source library | Published for community review | Stabilize the API through external feedback before the first release |
| Spring AI Community proposal | Ready to submit as an incubating project | Confirm project acceptance, Maven namespace ownership, and Central Portal credentials |
| Spring AI core contribution | Ready for an enhancement discussion, not a direct code PR | Agree on module/extension-point placement, then translate the accepted slice to the upstream Maven build and conventions |

The closest community precedents are `mcp-security`, which separates MCP security libraries from
Boot integration, and `spring-ai-agentcore`, which publishes AWS-specific Spring AI modules. The
latter does not provide an MCP Gateway client request signer, so this project has a distinct scope:
transport-level SigV4 authentication for any IAM-protected MCP Streamable HTTP endpoint.

## What it provides

- AWS SDK v2 SigV4 signing for MCP `GET`, `POST`, and `DELETE` requests
- Spring Boot auto-configuration with one AWS signing scope per named MCP connection
- Lifecycle-managed default AWS credentials and region provider chains, with optional
  application-provided `AwsCredentialsProvider` and `AwsRegionProvider` beans
- Safe coexistence with public and differently authenticated MCP connections
- Fail-fast validation for connection-name mistakes and insecure HTTP
- Source, Javadoc, and Maven publications for all library modules

## Compatibility

| Component | Baseline |
|---|---|
| Java runtime | 17+ |
| Build JDK | Liberica 17.0.19+ |
| Spring AI | 2.0.x |
| Spring Boot | 4.1.x |
| AWS SDK for Java | 2.42.x |

See [the compatibility policy](docs/compatibility.md) before changing dependency lines.

## Modules

| Module | Purpose |
|---|---|
| `spring-ai-mcp-sigv4` | Boot-independent request signing and endpoint routing |
| `spring-ai-mcp-sigv4-spring-boot-autoconfigure` | Properties, validation, and auto-configuration |
| `spring-ai-mcp-sigv4-spring-boot-starter` | One-dependency MCP client starter |
| `samples/agentcore-client` | Minimal consumer application |

## Quick start

Until a public release exists, publish the artifacts locally:

```shell
./gradlew publishToMavenLocal
```

Then add the starter:

```kotlin
dependencies {
    implementation("org.springaicommunity:spring-ai-mcp-sigv4-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

Maven:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-mcp-sigv4-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The `org.springaicommunity` coordinates are provisional until the project is accepted by that
organization. Do not publish those coordinates independently.

Configure transport and authentication with the same connection name:

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            agentcore:
              url: ${MCP_GW_URL}
              endpoint: ${MCP_GW_ENDPOINT:/mcp}
        authorization:
          aws:
            connections:
              agentcore:
                region: ${AWS_REGION:ap-northeast-2}
                # service-name: bedrock-agentcore
```

Credentials come from a single application `AwsCredentialsProvider` bean when present.
Otherwise, the auto-configuration creates a `DefaultCredentialsProvider` bean that Spring closes
with the application context.
If `region` is omitted, a lifecycle-scoped `DefaultAwsRegionProviderChain` bean resolves it.
Neither default bean is created when no AWS-authenticated MCP connection is configured.

Only the `agentcore` endpoint is signed.
Other configured MCP connections remain unchanged, and different AWS connections may use
different regions or service names.
The signer copies only the headers required by the signed request: `Authorization`, `X-Amz-Date`,
`X-Amz-Content-Sha256` when the signer adds a payload hash, and `X-Amz-Security-Token` for temporary
credentials. It does not replace application headers with the AWS signer's complete header map.

## Security defaults

The auto-configuration refuses to send signed credentials over clear-text HTTP.
For trusted local tests only, set this per connection:

```yaml
spring.ai.mcp.client.authorization.aws.connections.local.allow-insecure-http: true
```

On Spring AI versions that natively collect ordered MCP request customizers, SigV4 runs last so
headers added earlier are included in the canonical request.
Spring AI 2.0.0 uses a named transport compatibility bridge. The bridge adapts ordered sync
request-customizer beans to async, combines them with ordered async beans, and signs last. A
separate transport customizer that directly calls `httpRequestCustomizer(...)` or
`asyncHttpRequestCustomizer(...)` still targets the same write-only builder slot and must install
one explicitly composed delegate.
Do not combine OAuth Bearer authentication and SigV4 on the same connection because both write
the `Authorization` header.
The signer rejects a pre-existing `Authorization` header instead of silently replacing it.

Spring AI's sync MCP client does not imply a Reactor-free HTTP transport. MCP Java SDK 2.0 adapts
the sync request customizer to its internal async pipeline and already brings Reactor through
`mcp-core`. SigV4 resolves potentially blocking AWS credentials on `boundedElastic`; see the
[request customizer composition](docs/configuration.md#request-customizer-composition) notes.

## Manual use without Spring Boot

```java
var endpoint = URI.create(gatewayUrl).resolve("/mcp").normalize();
var signer = new AwsSigV4McpRequestCustomizer(
        DefaultCredentialsProvider.builder().build(),
        Region.AP_NORTHEAST_2,
        "bedrock-agentcore",
        endpoint);

var transport = HttpClientStreamableHttpTransport.builder(gatewayUrl)
        .endpoint("/mcp")
        .asyncHttpRequestCustomizer(signer)
        .build();
```

The manual signer is bound to the exact endpoint passed to its constructor and leaves every other
endpoint unsigned.
Manual callers that create a closeable credentials provider also own its lifecycle.

## Build and verify

```shell
./gradlew clean check publishToMavenLocal
```

The live `AgentCoreGatewaySigV4IT` test is skipped unless `MCP_GW_URL` is set.
When enabled, it uses the default AWS provider chain and can list and call an MCP tool.
See [.env.example](.env.example) for all inputs.
Run it separately from the default test lifecycle with:

```shell
./gradlew :spring-ai-mcp-sigv4:integrationTest
```

The test passed against a disposable IAM-authenticated AgentCore Gateway in `us-east-1` on
2026-08-10, covering initialize, tools/list, tools/call, and graceful session close.

The `check` lifecycle enforces Spring Java Format, Spring AI-aligned Checkstyle, Java compiler
warnings, Error Prone's locale check, and NullAway in JSpecify mode for main sources.

## Proposal strategy

Submit the copy-ready [Spring AI Community project request](docs/community-project-request.md)
first. If the Community leads recommend framework inclusion, open the focused Spring AI
enhancement issue in the [upstream proposal package](docs/upstream-proposal.md). Avoid a large
upstream code PR before that placement decision: current Spring AI `main` already has a more
natural request-customizer collection path than the Spring AI 2.0.0 compatibility bridge in this
repository, and the upstream implementation should target that path directly.

For a public release, the remaining blockers are operational rather than library-structure work:

1. obtain authorization for the final Maven group and configure Central Portal/GPG secrets;
2. obtain maintainers' direction on official Spring AI inclusion versus a community extension;
3. publish a release candidate and verify consumption from a clean Maven and Gradle project.

The Community application is in the
[Spring AI Community project request](docs/community-project-request.md). The alternative Spring
AI framework issue and expected upstream PR split are in the
[upstream proposal package](docs/upstream-proposal.md).

## Project and proposal documents

- [Design](docs/design.md)
- [Configuration reference](docs/configuration.md)
- [Repository review](docs/review-report.md)
- [Spring AI Community project request](docs/community-project-request.md)
- [Upstream proposal package](docs/upstream-proposal.md)
- [Release guide](docs/releasing.md)
- [Maintainer guide](docs/maintainer-guide.md)
- [Contributing](CONTRIBUTING.md) and [security policy](SECURITY.md)

## License

Apache License 2.0
