# Compatibility policy

## Current baseline

| Library release | Java runtime | Build JDK | Spring AI | Spring Boot | MCP Java SDK |
|---|---:|---:|---:|---:|---:|
| `0.1.x` | 17+ | Liberica 17.0.19+ | `2.0.x` | `4.1.x` | managed by Spring AI |

AWS SDK dependencies are managed by this repository's AWS SDK BOM.
Consumers may use a newer compatible AWS SDK v2 BOM.

Spring AI 2.0.0 is supported through a named transport compatibility bridge.
Later 2.0.x builds that natively collect MCP HTTP request customizers are detected at startup and
use the ordered request-customizer path instead.

## Policy

- A minor library line targets one Spring AI minor line.
- Patch releases do not intentionally break public API or configuration keys.
- Spring AI milestone or snapshot dependencies are not used in a stable release.
- The CI baseline is Liberica 17.0.19 so Error Prone and NullAway use the same supported compiler
  shape as Spring AI.
- Spring AI 1.1 / Boot 3.5 support should remain on a separate maintenance branch if needed;
  the different customizer composition model should not be hidden behind runtime reflection.
