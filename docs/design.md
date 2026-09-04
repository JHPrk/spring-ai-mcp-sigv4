# Design

## Problem

IAM-protected MCP Streamable HTTP endpoints require an AWS SigV4 `Authorization` header and
related signing headers on every protocol request, including initialize/tool calls, SSE
reconnects, and session-closing `DELETE` requests.
Spring AI's JDK HTTP transport exposes the request body and final endpoint immediately before
transport transmission, which is the last ordinary MCP customization boundary. Javaagents can
still inject propagation at JDK HTTP send time.

## Module boundaries

```text
core
  AwsSigV4McpRequestCustomizer
  AwsSigV4HeaderSigningPolicy + AwsSigV4HeaderSigningPolicies
  RoutingAwsSigV4McpRequestCustomizer
  package-private AwsV4HttpRequestAdapter

autoconfigure
  McpAwsProperties
  McpSigV4AutoConfiguration
  OnAnyMcpAwsConnectionCondition
  OnMissingNativeMcpRequestCustomizerSupportCondition
  FallbackMcpSigV4TransportCustomizer

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
            -> SigV4-owned header present? yes: reject
            -> snapshot wire headers once; select eligible signing headers
            -> adapt only signing headers to SdkHttpRequest
            -> resolve credentials on boundedElastic
            -> sign exact UTF-8 body and eligible headers with AwsV4HttpSigner
            -> apply only SigV4-owned output to original HttpRequest.Builder
  -> HttpRequest.build()
  -> JDK HttpClient.send/sendAsync (instrumentation may inject propagation)
  -> wire request
```

Credentials are resolved for each request so temporary credentials can refresh normally.
Blocking provider-chain work is isolated on Reactor's bounded-elastic scheduler.
Credential values and generated authorization headers are not retained or logged.
The auto-configured default credentials provider is a Spring bean, so its closeable resources are
released with the application context.

## Why exact endpoint routing

Spring AI 2.0.0 exposes a named transport builder customizer, while current upstream also composes
request-customizer beans globally.
The library uses capability detection: it installs a fallback named transport bridge only when
native request-customizer composition is absent, then backs that bridge off when the native
capability is present. The fallback bridge converts ordered sync request customizers to async,
adds ordered async customizers, and appends SigV4 to an MCP SDK delegating customizer. It is a
Spring AI 2.0.x integration path, not a Spring AI 1.x compatibility layer.
Routing by the actual endpoint passed to the request hook supports different signing scopes per
connection without signing public endpoints.

The route is not inferred from arbitrary request hosts.
It is built from Spring AI's configured `url` and `endpoint`, and configuration-name mismatches
fail startup.

## Security decisions

- HTTPS is required by auto-configuration unless explicitly relaxed per connection.
- Authentication configuration with no matching transport is rejected.
- Duplicate endpoint routes with conflicting signing scopes or additional header exclusions are rejected.
- Public/unconfigured endpoints are intentionally left unsigned.
- Both routed and directly installed signers are bound to exact normalized endpoint URIs.
- Diagnostic strings report counts and scopes but never credentials or endpoint URLs.
- Pre-existing SigV4-owned headers (`Authorization`, `X-Amz-Date`, `X-Amz-Security-Token`, and
  `X-Amz-Content-Sha256`) are rejected case-insensitively before credential resolution. This
  prevents both competing authorization and stale SigV4 metadata.

## Upstream shape

For official Spring AI inclusion, package names would move from `org.springaicommunity` to
`org.springframework.ai`, and build files would be translated into Spring AI's Maven reactor.
The feature should remain split into signing logic and Boot auto-configuration to avoid
forcing AWS dependencies on MCP users who do not need IAM authentication.

## Wire headers versus signing headers

A header can be sent without appearing in `Authorization`'s `SignedHeaders` list.
The policy selects a projection of the immutable header snapshot for `SdkHttpRequest`.
Unsigned headers stay on the original JDK builder with their values intact.
Only signer-owned authentication/hash output is applied back; the policy does not suppress
signer-generated host, date, session-token, or payload-hash metadata.
Pre-existing signer-owned headers are rejected before both policy evaluation and credential lookup.
The serialized body, method, endpoint, credential lookup schedule, and endpoint router are unchanged.

For example, signing `traceparent=A` and then sending `traceparent=B` fails verification if that
header participates in the signature. Instrumentation legitimately creates an HTTP client child
span at send time. Excluding that header permits propagation while retaining integrity for stable
MCP and application headers. The default is deliberately an exclusion list: newly introduced
stable headers remain protected automatically. A minimal allowlist would silently leave them unsigned.

`AwsSigV4HeaderSigningPolicy` is the sole SPI. It is case-insensitive; the customizer supplies
lower-case names with `Locale.ROOT`. Implementations must be thread-safe and independent of mutable
request state. Factory policies copy their exclusions into immutable sets.
The four-argument constructor delegates to the default policy; a five-argument overload accepts
an explicit policy. `all()` restores previous library-level eligibility, subject to AWS exclusions.
A global Spring policy plus exact per-connection exclusions covers the supported extension model.
It does not select arbitrary different policy beans by connection or detect observability vendors.

Spring `LOWEST_PRECEDENCE` still places SigV4 after application/MCP customizers. It cannot reorder
Java-agent advice at `HttpClient.send/sendAsync`. Disabling OTel/ADOT JDK instrumentation loses useful
observability and is not the library solution; neither an HttpClient wrapper nor agent-ordering
assumptions are required.

## Precedents and verification

- [AWS SigV4 guidance](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html)
  recommends excluding volatile transport headers that intermediaries may mutate.
- [AWS SDK Java 2.54.6 canonical headers](https://github.com/aws/aws-sdk-java-v2/blob/2.54.6/core/http-auth-aws/src/main/java/software/amazon/awssdk/http/auth/aws/internal/signer/V4CanonicalRequest.java)
  exclude `connection`, `x-amzn-trace-id`, `user-agent`, `expect`, `transfer-encoding`, and
  `x-forwarded-for`. The library explicitly excludes X-Ray as a propagation format and leaves
  the remaining AWS exclusions to `AwsV4HttpSigner`. No internal AWS API is imported.
- [Botocore](https://github.com/boto/botocore/blob/develop/botocore/auth.py) uses a signed-header
  blacklist; [Smithy TypeScript](https://github.com/smithy-lang/smithy-typescript/blob/main/packages/signature-v4/src/getCanonicalHeaders.ts)
  offers signable/unsignable header selection.
- [MCP Java SDK 2.0.0 transport](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.0/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java)
  runs the customizer, builds the request, then calls `sendAsync` for GET, POST, and DELETE.
- [OpenTelemetry JDK HTTP instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/v2.31.1/instrumentation/java-http-client/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/javahttpclient)
  starts a client span around send and injects context on request-header access.
- [Spring AI AgentCore OTel extension](https://github.com/spring-ai-community/spring-ai-agentcore/blob/main/spring-ai-agentcore-otel-extension/README.md)
  documents ADOT Java-agent deployment as a supported observability model.

`otelAgentTest` forks a JVM with the actual upstream agent and disabled exporters. A loopback server
captures GET, POST, and DELETE over both synchronous and asynchronous JDK sends. Tests verify
client-span propagation differs from the signing-time parent, excluded headers remain present,
and stable headers remain signed. The public AWS signer recomputes the signature using captured
wire values, original signing time, and test credentials; no canonicalization/HMAC code is copied.
The all-headers control reproduces the mismatch and restoring the original propagation value
restores signature validity. Tampering with a stable header or payload invalidates the signature.
All OpenTelemetry dependencies are test-only; this regression does not need AWS or a collector.
