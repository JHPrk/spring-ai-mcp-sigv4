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

mcp:
  sigv4:
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

The pre-release `spring.ai.mcp.client.authorization.aws.connections` namespace has moved to
`mcp.sigv4.connections`. Old settings fail startup with migration guidance, preventing an
accidentally unsigned connection. Spring AI transport properties retain their standard names.

## Endpoint matching

The auto-configuration resolves endpoints with the same base URI and endpoint semantics as
the MCP Java SDK: `URI.create(url).resolve(endpoint)`.
The default endpoint is `/mcp`.
Each named signer is bound to the normalized exact URI.

This enables the following topology:

```yaml
spring.ai.mcp.client.streamable-http.connections:
  korea-gateway:
    url: https://kr.example.com
  us-gateway:
    url: https://us.example.com
  public-server:
    url: https://public.example.com

mcp.sigv4.connections:
  korea-gateway:
    region: ap-northeast-2
  us-gateway:
    region: us-east-1
    service-name: execute-api
```

The two AWS endpoints are signed with their own scopes; `public-server` is not signed.
Named connections retain independent signing scopes and header exclusions even when their URLs
are identical. A connection without a SigV4 configuration entry remains unsigned.

## Credentials and region

All entries under `mcp.sigv4.connections` share one application-level
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

## Optional credential modules

`DefaultCredentialsProvider` discovers credentials; the `auth` module does not contain every
provider implementation. The starter intentionally does not force AWS service clients onto all
applications. Add the runtime modules required by the credential sources you actually use:

| Credential source | Additional AWS SDK v2 modules |
|---|---|
| Environment or static shared-profile credentials, ECS task roles, EC2 instance profiles | none |
| AssumeRole profiles (`role_arn` + `source_profile` / `credential_source`) | `sts` |
| EKS IRSA / Web Identity token files | `sts` |
| IAM Identity Center / SSO profiles | `sso`, `ssooidc` |

For example, with an AWS SDK BOM aligned to the starter:

```kotlin
runtimeOnly("software.amazon.awssdk:sts")
// For SSO-based local development:
runtimeOnly("software.amazon.awssdk:sso")
runtimeOnly("software.amazon.awssdk:ssooidc")
```

In Maven, declare the same modules with `<scope>runtime</scope>` and import the AWS SDK BOM
(current baseline `2.59.0`) in `dependencyManagement`. Do not put credentials in application
properties. A temporary credential already supplied through environment variables does not require
STS in this JVM, whereas a provider that must assume a role does.

See the AWS [default credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)
and [Web Identity provider contract](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/auth/credentials/WebIdentityTokenFileCredentialsProvider.html).

## Request customizer composition

Spring AI 2.0.2 installs ordered synchronous request-customizer beans, then ordered asynchronous
ones. This library appends its signer to each configured named transport using MCP SDK 2.1's
`addAsyncHttpRequestCustomizer(...)`. Existing customizers remain installed and their eligible
headers are included in the signature. There is no fallback bridge or global SigV4 bean.

For a stable header that must be signed:

```java
@Bean
@Order(0)
McpAsyncHttpClientRequestCustomizer tenantHeader() {
    return (builder, method, endpoint, body, context) ->
            Mono.just(builder.header("X-Tenant", "example"));
}
```

A named application transport customizer declared in ordinary user configuration may also add
request customizers with `addHttpRequestCustomizer(...)` or `addAsyncHttpRequestCustomizer(...)`.
This auto-configuration runs after the standard HTTP transport auto-configuration and appends
signing after already installed customizers. SDK 2.1 additionally exposes
`asyncHttpRequestCustomizers(Consumer<List<...>>)` for explicit inspection/reordering.
The deprecated singular setters still replace the list and should not be used for composition.

Request-customizer ordering and transport-customizer ordering are different. Spring AI 2.0.2
iterates transport customizers in registration order; `@Order` on another transport customizer
does not by itself guarantee that it runs before signing. Arbitrary third-party transport
customizers installed later must be explicitly integrated before signing. Never mutate a signed
body, URI, method, or header after the signer runs. Javaagent propagation is handled separately
by the volatile-header policy below.

Earlier customizers must also preserve the transport's endpoint and method: a mismatch between
the final JDK builder and the MCP customization arguments fails before credential resolution.
The serialized body must remain unchanged. The signer hashes the supplied UTF-8 body and does
not consume the JDK body publisher to inspect changes made by application code.

OAuth Bearer and SigV4 must not share one connection. SigV4 rejects pre-existing `Authorization`,
`X-Amz-Date`, `X-Amz-Security-Token`, and `X-Amz-Content-Sha256` case-insensitively before credential
resolution. Other headers stay on the request and eligible stable headers are signed.

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

SigV4 is installed only on connection names listed under `mcp.sigv4.connections`,
and each installed signer is also bound to an exact endpoint. An unsigned alias of the same URL
receives no signer and performs no AWS credential lookup. The same application-level credentials provider is shared by all IAM connections, but
each request to each endpoint receives a new signature. AWS SDK providers may internally refresh
or cache temporary credentials, which is separate from SigV4 request signing.

This library does not configure OAuth or fixed Bearer tokens. Scope global request-customizer
beans by endpoint. When different connection names alias one URL but use different authentication,
install their authentication through named transport customizers in user configuration instead.
A global Bearer customizer that also changes the IAM connection is rejected intentionally.

### Sync clients and Reactor

The MCP Java SDK 2.1 JDK HTTP transport is reactive internally even when Spring AI exposes a sync
client. `HttpClientStreamableHttpTransport.Builder.addHttpRequestCustomizer(sync)` adapts the sync
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
mcp:
  sigv4:
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
route distinct custom policy beans by connection. Each named connection has independent additional
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
short-lived credentials and required tool inputs documented in `.env.example`. The core task can
attach the same upstream agent itself, with exporters and wire logging disabled:

```shell
./gradlew --no-daemon :spring-ai-mcp-sigv4:integrationTest \
  -PtestJavaVersion=21 -PagentCoreItTracing=true

./gradlew --no-daemon :spring-ai-mcp-sigv4-spring-boot-starter:integrationTest
```

`-PagentCoreItTracing=true` sets `MCP_IAM_IT_TRACING=true`, which requires a recording span,
seeds propagation headers before signing,
and verifies that they remain present but unsigned. The test emits only aggregate request-method
and session-header counts. Set `MCP_IAM_IT_EXPECTED_RESULT_JSON` to additionally compare the tool's
structured or JSON text response without printing its contents. Keep HTTP wire logging disabled.
For ADOT instead, add `-PotelAgentJar=/absolute/path/aws-opentelemetry-agent.jar` to the core
command. The starter test uses the actual auto-configured asynchronous MCP client and standard
`AsyncMcpToolCallbackProvider`, and expects an echo tool accepting a JSON `message` string.
It requires `AWS_REGION`; it checks for credential and authorization leakage in captured output.
Both live tasks always execute when requested; build-cache hits and up-to-date results are disabled.

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
