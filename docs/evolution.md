# Evolution

## Initial implementation

The original repository targeted Spring AI 1.1 and Spring Boot 3.5.
That line exposed one effectively unique async HTTP request-customizer slot, so the implementation
created one global SigV4 signer and required applications to manually compose other headers.
All AWS connection entries also had to share a region and service name.

## Spring AI 2.0 redesign

Spring AI 2.0.0 moved named transport customization to `McpClientCustomizer<Builder>`.
Current upstream additionally collects ordered sync and async HTTP request-customizer beans.
The library contributes one routing request customizer and a capability-detected 2.0.0 transport
bridge, avoiding the bridge on the newer upstream shape.

Consequences:

- application customizers coexist without a manual delegating bean;
- each named AWS connection can use its own signing scope;
- public connections in the same application are not signed;
- an authentication/transport name mismatch is detected at startup;
- Java 17 is sufficient, matching current Spring AI.

## Ideas intentionally not used

### Authentication nested under transport properties

Adding `sig-v4` beneath Spring AI's `ConnectionParameters` would couple this extension to
properties it does not own and mix transport and authentication concerns.

### A new contributor SPI

Spring AI 2.0 already provides ordered request-customizer composition, so an extension-specific
contributor API would duplicate framework behavior.

### Named transport customizer replacement

Calling `asyncHttpRequestCustomizer(...)` from a named transport customizer replaces the
customizer Spring AI already installed.
The Spring AI 2.0.0 compatibility bridge must use that hook, so it first collects sync and async
request-customizer beans into one `DelegatingMcpAsyncHttpClientRequestCustomizer` and appends
SigV4 last. Direct mutations made by other named transport customizers cannot be inspected because
the builder exposes setters but no getter; those remain an explicit-composition case.

## Future consideration

Connection-scoped credentials-provider selection may be reconsidered if Community feedback shows
a concrete multi-principal requirement. The current API intentionally shares one application-level
`AwsCredentialsProvider`; no resolver SPI or qualifier-based connection API is defined in the
`0.1.x` line.
