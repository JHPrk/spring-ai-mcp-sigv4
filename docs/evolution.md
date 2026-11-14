# Evolution

## Initial implementation

The original repository targeted Spring AI 1.1 and Spring Boot 3.5.
That line exposed one effectively unique async HTTP request-customizer slot, so the implementation
created one global SigV4 signer and required applications to manually compose other headers.
All AWS connection entries also had to share a region and service name.

## Spring AI 2.0.2 / MCP SDK 2.1 baseline

The first public release now requires Spring AI 2.0.2 with MCP SDK 2.1.0.
Spring AI supplies native ordered request-customizer collection. The SDK supplies additive
builder APIs and access to the customizer list. The former reflection-based fallback has been removed.

SigV4 is appended only to configured named transports. It is not a global request-customizer
bean, so public and Bearer-authenticated aliases of the same endpoint stay independent.
Each AWS connection retains its region/service scope, HTTPS validation, and exact endpoint boundary.
Local HTTP tests verify native sync/async headers, earlier named customizers, session DELETE,
multiple scopes, and signed/unsigned aliases without inspecting private transport fields.

## Ideas intentionally not used

### Authentication nested under transport properties

Adding `sig-v4` beneath Spring AI's `ConnectionParameters` would couple this extension to
properties it does not own and mix transport and authentication concerns.

### A new contributor SPI

Spring AI 2.0 already provides ordered request-customizer composition, so an extension-specific
contributor API would duplicate framework behavior.

### Replacing the transport request customizer

SDK 2.1's deprecated singular setters replace the existing list. This library instead calls
`addAsyncHttpRequestCustomizer(...)`, preserving prior entries. Applications that install later
transport customizers must still arrange signing last; the SDK cannot preserve a signature across
subsequent request mutation.

## Future consideration

Connection-scoped credentials-provider selection may be reconsidered if Community feedback shows
a concrete multi-principal requirement. The current API intentionally shares one application-level
`AwsCredentialsProvider`; no resolver SPI or qualifier-based connection API is defined in the
`0.1.x` line.
