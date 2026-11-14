# Design

IAM-protected MCP Streamable HTTP endpoints need SigV4 on each protocol request.
Signing belongs at the final MCP request customizer, where the method, endpoint, headers, and serialized body are available.
Spring AI and the MCP SDK retain ownership of initialization, sessions, tool discovery/invocation, and callback conversion.

## Modules and request flow

```text
core
  AwsSigV4McpRequestCustomizer
  AwsSigV4HeaderSigningPolicy + AwsSigV4HeaderSigningPolicies
  RoutingAwsSigV4McpRequestCustomizer
  package-private AwsV4HttpRequestAdapter

autoconfigure
  McpAwsProperties + McpSigV4AutoConfiguration
  OnAnyMcpAwsConnectionCondition + McpSigV4TransportCustomizer

starter
  core + autoconfigure + official Spring AI MCP client starter
```

The core depends on MCP core, Spring Core, and AWS credential/HTTP signing APIs, without Boot, Spring AI model types, service clients, or a JSON mapper.
Auto-configuration keeps Boot and Spring AI types optional in its publication; the starter supplies the runtime integration.

```text
Spring AI named transport
  -> existing application request customizers
  -> endpoint-bound signer, only if the connection name is configured
       -> validate method/endpoint and reject pre-existing SigV4-owned headers
       -> snapshot headers and select the signing projection
       -> resolve credentials on boundedElastic
       -> sign the exact UTF-8 body and eligible headers
       -> apply SigV4-owned output to the original JDK request builder
  -> HttpRequest.build()
  -> JDK HttpClient.send/sendAsync, including agent propagation
```

Credentials resolve for every request so the AWS provider can refresh temporary credentials.
The default provider is a Spring bean closed with the context; manual callers own their provider lifecycle.
Neither credentials nor generated authorization values are retained for diagnostics.

## Connection isolation and composition

Spring AI 2.0.2 collects request-customizer beans globally.
A global SigV4 router would also sign unsigned aliases of a configured endpoint, so this library appends a signer only through the named transport hook and SDK 2.1's additive API.
Names must match configured transports; each signer is also bound to the exact normalized endpoint derived from `URI.create(url).resolve(endpoint)`.
Connections retain independent regions, service names, and header exclusions, while sharing one standard credentials provider.
The public router is available for manual use.

Authentication properties remain in this library's `mcp.sigv4` namespace rather than extending transport properties owned by Spring AI.
No extra request-contributor SPI or reflection fallback is needed.
Existing request customizers run before signing; later transport customizers must preserve the signed request.
Spring AI 2.0.2 uses transport registration order, so `@Order` alone cannot guarantee that arbitrary third-party transport customization runs first.
See [configuration](configuration.md#request-customizer-composition) for supported composition.

## Security and header policy

Auto-configuration requires HTTPS by default, rejects malformed endpoints and unmatched names, and leaves public/unconfigured connections unsigned.
Pre-existing `Authorization`, `X-Amz-Date`, `X-Amz-Security-Token`, and `X-Amz-Content-Sha256` are rejected case-insensitively before credential lookup.
The adapter preserves application/MCP headers and applies only signer-owned output, including a signed payload-hash header.
Diagnostic strings omit private endpoints, credentials, and authorization values.

Wire headers and `SignedHeaders` are different sets.
The immutable header snapshot is projected into AWS signing input; excluded headers stay on the JDK builder.
The default policy excludes W3C, B3, and X-Ray propagation because HTTP instrumentation can inject a child-span context at send time, after ordinary Spring/MCP customization.
Stable application and MCP headers remain eligible automatically.
An exclusion removes SigV4 integrity protection for that header; use it only for legitimate mutation after signing.
[AWS SigV4 guidance](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html) likewise advises excluding volatile headers.
The public AWS signer retains its own exclusions; no internal AWS API or copied canonicalization algorithm is used.

`AwsSigV4HeaderSigningPolicy` is the sole policy SPI: thread-safe, case-insensitive, and independent of mutable request state.
Factory policies copy exclusions into immutable sets.
One application base policy composes with exact per-connection exclusions; there is no vendor registry or connection-specific provider/policy resolver.
See [header policies](configuration.md#header-signing-policies) for defaults and examples.

## Verification boundaries

Local protocol tests verify signed delivery, bodies, session teardown, and named-connection isolation.
The real agent regression recomputes signatures from captured wire requests with the public AWS signer and synthetic credentials.
It proves send-time propagation remains present but unsigned, stable-header/body tampering invalidates signatures, and the all-headers control reproduces the tracing conflict.
Independent consumers verify actual publications, dependency boundaries, and Boot integration.
Live Gateway tests provide separate service-level evidence; they do not establish session/SSE behavior the server did not exercise.
Commands and prerequisites are maintained in [CONTRIBUTING.md](../CONTRIBUTING.md).
