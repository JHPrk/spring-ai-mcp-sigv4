# Design

## Problem

IAM-protected MCP Streamable HTTP endpoints require an AWS SigV4 `Authorization` header and
related signing headers on every protocol request, including initialize/tool calls, SSE
reconnects, and session-closing `DELETE` requests.
Spring AI's JDK HTTP transport exposes the request body and final endpoint immediately before
transmission, which is the correct point to sign.

## Module boundaries

```text
core
  AwsSigV4McpRequestCustomizer
  RoutingAwsSigV4McpRequestCustomizer
  package-private AwsV4HttpRequestAdapter

autoconfigure
  McpAwsProperties
  McpSigV4AutoConfiguration
  OnAnyMcpAwsConnectionCondition
  OnLegacyMcpHttpClientIntegrationCondition
  LegacyMcpSigV4TransportCustomizer

starter
  core + autoconfigure + official Spring AI MCP client starter
```

The core has no Spring Boot dependency.
The autoconfigure module keeps Boot and Spring AI autoconfiguration types optional in its
published dependency metadata, while the starter supplies them at runtime.

## Runtime flow

```text
Spring AI named MCP connection
  -> application request customizers (ordered)
  -> RoutingAwsSigV4McpRequestCustomizer
       -> exact endpoint configured? no: unchanged request
       -> yes: AwsSigV4McpRequestCustomizer
            -> resolve credentials on boundedElastic
            -> adapt JDK request metadata to SdkHttpRequest
            -> sign body and headers with AwsV4HttpSigner
            -> apply signed headers to HttpRequest.Builder
  -> JDK HttpClient
```

Credentials are resolved for each request so rotating or temporary credentials remain valid.
Blocking provider-chain work is isolated on Reactor's bounded-elastic scheduler.
Credential values and generated authorization headers are not retained or logged.
The auto-configured default credentials provider is a Spring bean, so its closeable resources are
released with the application context.

## Why exact endpoint routing

Spring AI 2.0.0 exposes a named transport builder customizer, while current upstream also composes
request-customizer beans globally.
The library uses capability detection: it installs a named transport bridge only for the 2.0.0
shape, then backs that bridge off when native request-customizer collection is present. On 2.0.0,
the bridge converts ordered sync request customizers to async, adds ordered async customizers, and
appends SigV4 to an MCP SDK delegating customizer.
Routing by the actual endpoint passed to the request hook supports different signing scopes per
connection without signing public endpoints.

The route is not inferred from arbitrary request hosts.
It is built from Spring AI's configured `url` and `endpoint`, and configuration-name mismatches
fail startup.

## Security decisions

- HTTPS is required by auto-configuration unless explicitly relaxed per connection.
- Authentication configuration with no matching transport is rejected.
- Duplicate endpoint routes with conflicting signing scopes are rejected.
- Public/unconfigured endpoints are intentionally left unsigned.
- Both routed and directly installed signers are bound to exact normalized endpoint URIs.
- Diagnostic strings report counts and scopes but never credentials or endpoint URLs.
- A pre-existing `Authorization` header is rejected so OAuth and SigV4 cannot silently overwrite
  each other on one endpoint.

## Upstream shape

For official Spring AI inclusion, package names would move from `org.springaicommunity` to
`org.springframework.ai`, and build files would be translated into Spring AI's Maven reactor.
The feature should remain split into signing logic and Boot auto-configuration to avoid
forcing AWS dependencies on MCP users who do not need IAM authentication.
