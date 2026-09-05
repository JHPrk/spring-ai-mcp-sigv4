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
                signing:
                  additional-unsigned-headers: []
```

| Property | Default | Meaning |
|---|---|---|
| `connections.<name>.region` | AWS region chain | SigV4 credential-scope region |
| `connections.<name>.service-name` | `bedrock-agentcore` | SigV4 service signing name |
| `connections.<name>.allow-insecure-http` | `false` | Allows HTTP only for trusted local tests |
| `connections.<name>.signing.additional-unsigned-headers` | empty | Exact extra header names omitted from signing input, preserved on the wire |

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
If two connection names resolve to the same endpoint, their effective signing scopes
and additional unsigned-header sets must be identical after normalization.

## Credentials and region

All entries under `authorization.aws.connections` share one application-level
`AwsCredentialsProvider`. A connection may select its own endpoint, region, and service name, but
this release does not provide connection-scoped credentials-provider selection.

If the application exposes exactly one `AwsCredentialsProvider` bean, it is reused by every
AWS-authenticated MCP connection. With no bean, auto-configuration contributes a
`DefaultCredentialsProvider` bean and Spring closes it with the application context. Multiple
equally eligible provider beans cause the standard Spring single-bean resolution failure at
startup; expose one selected provider for MCP instead.

An explicit `region` property has priority.
When absent, an application-provided `AwsRegionProvider` or an auto-configured
`DefaultAwsRegionProviderChain` resolves the region.
The default credentials and region beans are conditional on at least one AWS-authenticated MCP
connection and back off when the application supplies the corresponding provider type.

The provider is resolved for every request. This supports normal AWS temporary-credential refresh
when the refreshed credentials continue to represent the same IAM principal. Changing the
effective IAM principal during an established stateful MCP session is an identity transition, not
a credential refresh. The library does not guarantee session continuity across that transition;
reconnect the client when intentionally changing principals, especially for IAM-authenticated
stateful endpoints such as AgentCore Gateway.

## Request customizer composition

The Spring AI 2.0.0 release applies named
`McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>` beans but does not collect
`McpAsyncHttpClientRequestCustomizer` beans.
This library detects that capability and installs a connection-name-aware compatibility bridge.
The bridge collects ordered `McpSyncHttpClientRequestCustomizer` beans, adapts them to async,
then collects ordered `McpAsyncHttpClientRequestCustomizer` beans and installs a
`DelegatingMcpAsyncHttpClientRequestCustomizer`. SigV4 is appended last so headers contributed by
both sync and async customizers are present before eligible headers are signed.

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
one connection. SigV4 detects and rejects pre-existing `Authorization`, `X-Amz-Date`,
`X-Amz-Security-Token`, and `X-Amz-Content-Sha256` headers before resolving credentials. Header
matching is case-insensitive. Other application and MCP headers remain on the request and are
included in the signature when eligible under the header signing policy.

### Mixed authentication topologies

Authentication is selected per connection, not once for the whole MCP client application. A
mixed application can use IAM, OAuth Bearer, a fixed Bearer token, public HTTP, and stdio servers
at the same time when each HTTP authorization customizer is restricted to its own connection name
or exact endpoint:

| Connection | Expected HTTP authorization |
|---|---|
| IAM Streamable HTTP | SigV4 on every request |
| OAuth Streamable HTTP | OAuth Bearer token only |
| Fixed-token Streamable HTTP | Configured Bearer token only |
| Public Streamable HTTP | No `Authorization` header |
| stdio | Not processed by HTTP request customizers |

The SigV4 router resolves credentials and signs only endpoints listed under
`authorization.aws.connections`; public and differently authenticated endpoints pass through
unchanged. The same application-level credentials provider is shared by all IAM connections, but
each request to each endpoint receives a new signature. AWS SDK providers may internally refresh
or cache temporary credentials, which is separate from SigV4 request signing.

This library does not configure OAuth or fixed Bearer tokens. On Spring AI 2.0.0, prefer a named
`McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>` that installs authorization only
for its selected connection. On a Spring AI integration shape that globally collects request
customizer beans, an OAuth/Bearer customizer must perform its own exact-endpoint routing. A global
customizer that adds `Authorization` to the IAM endpoint is rejected intentionally instead of
silently combining two authentication schemes.

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

## Header signing policies

Wire headers are what the HTTP client sends; `SignedHeaders` lists the subset integrity-protected
by SigV4. An excluded header is preserved on the actual request, but changes to it cannot be detected
by SigV4. Keep stable tenant, business, and MCP protocol/session headers signed whenever practical.
Configure an exclusion only for headers that legitimately mutate after signing.

The default policy excludes these exact names, case-insensitively:

| Propagation format | Excluded names |
|---|---|
| W3C | `traceparent`, `tracestate`, `baggage` |
| B3 single/multi | `b3`, `x-b3-traceid`, `x-b3-spanid`, `x-b3-parentspanid`, `x-b3-sampled`, `x-b3-flags` |
| AWS X-Ray | `x-amzn-trace-id` |

All other existing headers remain eligible. AWS SDK independently applies canonical-header
exclusions, including `connection`, `user-agent`, `expect`, `transfer-encoding`, and `x-forwarded-for`.
The library does not duplicate those AWS internals. `all()` restores only library-level eligibility;
it cannot override exclusions inside the AWS signer.

Add exact per-connection names using:

```yaml
spring:
  ai:
    mcp:
      client:
        authorization:
          aws:
            connections:
              agentcore:
                region: ap-northeast-2
                service-name: bedrock-agentcore
                signing:
                  additional-unsigned-headers:
                    - x-company-trace-id
```

Names are trimmed, normalized with `Locale.ROOT`, and deduplicated. Blank entries fail binding.
Matching is exact; wildcard/prefix patterns are not expanded. Vendor-specific formats belong in
these properties or an application policy, not a built-in vendor registry.

For advanced use, replace the global base policy with one `AwsSigV4HeaderSigningPolicy` bean.
The conditional default bean backs off, and every connection's additional exclusions are composed
over the replacement base. Multiple equally eligible policy beans cause standard Spring bean
resolution failure. The policy must be thread-safe. For example, preserve defaults while extending them:

```java
@Bean
AwsSigV4HeaderSigningPolicy headerSigningPolicy() {
    var defaults = AwsSigV4HeaderSigningPolicies.defaultPolicy();
    var extra = AwsSigV4HeaderSigningPolicies.excluding(Set.of("x-company-context"));
    return name -> defaults.shouldSign(name) && extra.shouldSign(name);
}
```

Both policy types are in `org.springaicommunity.mcp.sigv4`; the example also uses `java.util.Set`.
`excluding(...)` alone excludes only its arguments, so replacing the default bean with it alone
makes standard propagation headers eligible again. A bean returning `all()` intentionally restores
previous eligibility, which can reproduce the send-time tracing conflict.
The SPI does not control generated authentication/hash headers and cannot bypass rejection of
pre-existing SigV4-owned headers. Excluding application-supplied service-required headers may make
requests invalid; the caller remains responsible for service-specific signing requirements.

Manual core callers retain the existing constructor, which now uses the default policy, or supply
an explicit policy as the fifth argument:

```java
new AwsSigV4McpRequestCustomizer(credentialsProvider, region, serviceName, endpoint,
        AwsSigV4HeaderSigningPolicies.defaultPolicy());
```

This release supports one application base policy plus per-connection exact exclusions. It does not
route distinct custom policy beans by connection. Endpoint aliases must have identical additional
exclusions, region, and service name.

### Instrumented HTTP verification

The normal `check` lifecycle includes the collector-free upstream OTel regression:

```shell
./gradlew :spring-ai-mcp-sigv4:otelAgentTest
```

The forked JVM uses `-javaagent`, enables JDK HttpClient instrumentation, and sets trace, metric,
and log exporters to `none`. It creates a real client span at send time and recomputes signatures
from captured loopback requests. No production OTel/ADOT dependency is added.

For optional manual ADOT verification, download a release from the
[official ADOT Java agent releases](https://github.com/aws-observability/aws-otel-java-instrumentation/releases),
then run the same local checks with that agent:

```shell
./gradlew :spring-ai-mcp-sigv4:otelAgentTest \
  -PotelAgentJar=/absolute/path/aws-opentelemetry-agent.jar --rerun-tasks
```

This optional override is a local test JVM input only. ADOT verification is separate from the
upstream agent CI result. To exercise an already provisioned disposable AgentCore Gateway, use the
short-lived credentials and test inputs documented in `.env.example`, with JDK tracing enabled:

```shell
JAVA_TOOL_OPTIONS='-javaagent:/absolute/path/aws-opentelemetry-agent.jar' \
OTEL_TRACES_EXPORTER=none OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
OTEL_AWS_APPLICATION_SIGNALS_ENABLED=false OTEL_SDK_DISABLED=false \
OTEL_TRACES_SAMPLER=always_on OTEL_PROPAGATORS=tracecontext,baggage \
OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED=true \
OTEL_INSTRUMENTATION_OPENTELEMETRY_API_ENABLED=true MCP_IAM_IT_TRACING=true \
./gradlew --no-daemon :spring-ai-mcp-sigv4:integrationTest --rerun-tasks
```

`MCP_IAM_IT_TRACING=true` requires a recording span, seeds propagation headers before signing,
and verifies that they remain present but unsigned. The test emits only aggregate request-method
and session-header counts. Set `MCP_IAM_IT_EXPECTED_RESULT_JSON` to additionally compare the tool's
structured or JSON text response without printing its contents. Keep HTTP wire logging disabled.

On 2026-09-05, ADOT 2.30.0 passed all eight local wire regressions and the live test against a
Terraform-provisioned IAM Gateway in `us-east-1`, using temporary credentials for an ARN-scoped
caller role. Initialize, tools/list, tools/call, and the expected Lambda echo response passed with
active tracing. The signing hook observed four POST requests and one GET request; this is not an
assertion that the Gateway accepted SSE. No session header was issued, so session-aware DELETE
and SSE reconnect were not exercised live. The ten managed resources were destroyed afterward;
empty state, a no-op destroy plan, and independent AWS absence checks all passed.

These commands do not provision AWS resources. Without the documented Gateway environment, live
verification is not executed and must not be reported as passing. SSE/reconnect and DELETE depend
on the server's capabilities/session behavior; the local protocol and agent tests provide separate
coverage, not a claim about live Gateway behavior.
