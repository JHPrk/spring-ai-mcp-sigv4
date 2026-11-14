# Spring AI MCP SigV4

AWS Signature Version 4 authentication for Spring AI MCP Streamable HTTP clients, including IAM-protected Amazon Bedrock AgentCore Gateway endpoints.
The library signs the transport's serialized requests and reuses Spring AI and the MCP Java SDK for initialization, sessions, discovery, invocation, and tool callbacks.

> This independent extension was proposed to Spring AI Community in [Project Request #38](https://github.com/spring-ai-community/community/issues/38).
> It is not an official Spring AI artifact and is not yet published to Maven Central.
> Publication coordinates remain provisional until namespace ownership is confirmed.

## Features

- AWS SDK v2 signing for MCP `GET`, `POST`, and `DELETE` requests, using the exact UTF-8 body.
- Per-connection region, service name, endpoint boundary, and header exclusions.
- Standard AWS credentials and region provider chains with application bean overrides.
- HTTPS by default and fail-fast validation of mismatched connection names.
- Independent public, OAuth, and IAM connections in the same application.

## Compatibility

| Component | Baseline |
|---|---|
| Java runtime | 17+; CI verifies 17 and 21 |
| Build JDK | Liberica 17.0.19+ |
| Spring AI | 2.0.2+ within 2.0.x |
| Spring Boot | 4.1.2+ within 4.1.x |
| MCP Java SDK | 2.1.0, aligned with Spring AI |
| AWS SDK for Java | 2.59.x |

The `0.1.x` line uses Spring AI's native named transport hook and the SDK's additive request-customizer API.
Spring AI 2.0.0/2.0.1 and the old reflection-based fallback are unsupported.
A library minor line targets one Spring AI minor line; patch releases do not intentionally break public API or property names.
Other framework lines require separate evaluation, and stable releases must use stable framework dependencies.

## Quick start

Until a public release exists, build the artifacts locally with `./gradlew publishToMavenLocal` and enable `mavenLocal()` in your consuming Gradle build.
Add the starter:

```kotlin
dependencies {
    implementation("org.springaicommunity:spring-ai-mcp-sigv4-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

For Maven:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-mcp-sigv4-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Do not publish the provisional `org.springaicommunity` coordinates without namespace authorization.
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

mcp:
  sigv4:
    connections:
      agentcore:
        region: ${AWS_REGION:ap-northeast-2}
```

Supply AWS credentials through the standard provider chain or one application `AwsCredentialsProvider` bean.
AssumeRole, Web Identity, and SSO credential sources need [optional AWS modules](docs/configuration.md#optional-credential-modules).
The default service name is `bedrock-agentcore`; when `region` is omitted, the standard region provider chain is used.
The [sample](samples/agentcore-client/README.md) contains a runnable application.

Only configured named connections are signed, even when another connection aliases the same URL.
Keep OAuth/Bearer customization scoped to its own connection and preserve the serialized body after signing.
The default policy leaves tracing headers on the wire while excluding them from `SignedHeaders`; see [header policies](docs/configuration.md#header-signing-policies) before adding exclusions.

## Modules

| Module | Purpose |
|---|---|
| `spring-ai-mcp-sigv4` | Boot-independent signing and endpoint routing |
| `spring-ai-mcp-sigv4-spring-boot-autoconfigure` | Properties, validation, and Spring AI integration |
| `spring-ai-mcp-sigv4-spring-boot-starter` | Core, auto-configuration, and the official Spring AI MCP client starter |
| `samples/agentcore-client` | Minimal consumer application |

## Contributing and documentation

See [CONTRIBUTING.md](CONTRIBUTING.md) for development, verification, and release commands.
The default `./gradlew check` requires no AWS access; independent published-artifact consumers run with `./gradlew verifyConsumers`.

- [Configuration reference](docs/configuration.md): providers, request composition, manual use, and header policies.
- [Design](docs/design.md): module boundaries and security decisions.
- [Changelog](CHANGELOG.md), [support](SUPPORT.md), [security policy](SECURITY.md), and [code of conduct](CODE_OF_CONDUCT.md).

## License

[Apache License 2.0](LICENSE).
