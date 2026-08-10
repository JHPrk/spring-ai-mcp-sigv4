# Configuration reference

## Property tree

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        streamable-http:
          connections:
            <connection-name>:
              url: https://gateway.example
              endpoint: /mcp
        authorization:
          aws:
            connections:
              <connection-name>:
                region: ap-northeast-2
                service-name: bedrock-agentcore
                allow-insecure-http: false
```

| Property | Default | Meaning |
|---|---|---|
| `connections.<name>.region` | AWS region chain | SigV4 credential-scope region |
| `connections.<name>.service-name` | `bedrock-agentcore` | SigV4 service signing name |
| `connections.<name>.allow-insecure-http` | `false` | Allows HTTP only for trusted local tests |

The AWS connection name must exactly match a Streamable HTTP connection name.
An unknown name fails application startup instead of silently disabling authentication.
Removing an AWS connection entry disables signing for that transport connection.

## Endpoint matching

The auto-configuration resolves endpoints with the same base URI and endpoint semantics as
the MCP Java SDK: `URI.create(url).resolve(endpoint)`.
The default endpoint is `/mcp`.
The normalized exact URI becomes the signing route.

This enables the following topology:

```yaml
spring.ai.mcp.client.streamable-http.connections:
  korea-gateway:
    url: https://kr.example.com
  us-gateway:
    url: https://us.example.com
  public-server:
    url: https://public.example.com

spring.ai.mcp.client.authorization.aws.connections:
  korea-gateway:
    region: ap-northeast-2
  us-gateway:
    region: us-east-1
    service-name: execute-api
```

The two AWS endpoints are signed with their own scopes; `public-server` is not signed.
If two connection names resolve to the same endpoint, their effective signing scopes must
be identical.

## Credentials and region

If the application exposes exactly one `AwsCredentialsProvider` bean, it is reused.
With no bean, auto-configuration contributes a `DefaultCredentialsProvider` bean and Spring closes
it with the application context.
Multiple provider beans cause the standard Spring single-bean resolution failure; expose one
selected provider for MCP instead.

An explicit `region` property has priority.
When absent, an application-provided `AwsRegionProvider` or an auto-configured
`DefaultAwsRegionProviderChain` resolves the region.
The default credentials and region beans are conditional on at least one AWS-authenticated MCP
connection and back off when the application supplies the corresponding provider type.

## Request customizer composition

The Spring AI 2.0.0 release applies named
`McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>` beans but does not collect
`McpAsyncHttpClientRequestCustomizer` beans.
This library detects that capability and installs a connection-name-aware compatibility bridge.
The bridge collects ordered `McpSyncHttpClientRequestCustomizer` beans, adapts them to async,
then collects ordered `McpAsyncHttpClientRequestCustomizer` beans and installs a
`DelegatingMcpAsyncHttpClientRequestCustomizer`. SigV4 is appended last so headers contributed by
both sync and async customizers are included in the signature.

Current Spring AI upstream collects sync and async HTTP request-customizer beans in order.
On that shape, the compatibility bridge backs off and
`RoutingAwsSigV4McpRequestCustomizer` runs with lowest precedence, so application headers are
present before the request is signed.

For a header that must be signed, use a higher-precedence customizer:

```java
@Bean
@Order(0)
McpAsyncHttpClientRequestCustomizer tenantHeader() {
    return (builder, method, endpoint, body, context) ->
            Mono.just(builder.header("X-Tenant", "example"));
}
```

Do not add or change signed headers after the SigV4 customizer runs.
A separate named transport customizer that calls
`httpRequestCustomizer(...)` or `asyncHttpRequestCustomizer(...)` targets the same single builder
slot, which exposes no getter. Such direct builder mutations cannot be discovered and remain
last-writer-wins on both integration shapes. Prefer sync or async request-customizer beans, which
Spring AI or this library composes. If a direct transport customizer is unavoidable, it must
install one explicitly composed delegate.
Two customizers that both own the `Authorization` header, such as OAuth and SigV4, must not share
one connection.
SigV4 detects and rejects a pre-existing `Authorization` header before resolving credentials.

### Sync clients and Reactor

The MCP Java SDK 2.0 JDK HTTP transport is reactive internally even when Spring AI exposes a sync
client. `HttpClientStreamableHttpTransport.Builder.httpRequestCustomizer(sync)` adapts the sync
customizer with `McpAsyncHttpClientRequestCustomizer.fromSync(...)`, and `mcp-core` itself has a
compile dependency on Reactor. Selecting a sync Spring AI client therefore does not remove Reactor
from the application classpath.

SigV4 remains async because `AwsCredentialsProvider.resolveCredentials()` can block while reading
profiles, refreshing temporary credentials, or contacting container/instance metadata. Resolving
credentials on Reactor's bounded-elastic scheduler avoids blocking the transport pipeline. This
adds a small scheduling cost for static credentials, but does not introduce a reactive runtime
that the MCP HTTP transport did not already require.

## Manual mode

The core `AwsSigV4McpRequestCustomizer` is independent of Spring Boot and can be installed
directly on `HttpClientStreamableHttpTransport.Builder`.
Its constructor requires the exact endpoint that it may sign; unmatched endpoints remain
unchanged.
Manual mode does not enforce HTTPS and does not own a caller-provided credentials provider, so the
caller owns transport policy and provider lifecycle.
