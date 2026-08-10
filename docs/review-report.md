# Repository review report

Review date: 2026-08-10 (Asia/Seoul)

Reference points:

- attached prototype: Spring AI 1.1.4 / Spring Boot 3.5.0 / Java 21;
- Spring AI upstream `main`: commit `192de91547137281bbdc57012526156c46308ad0`;
- Spring AI Community `mcp-security`: commit
  `79bacd0a4ee87af46c1e1c4f5e42f51329486767`.
- Spring AI Community `spring-ai-agentcore`: current `main` reviewed for module scope, AWS SDK
  usage, build conventions, and Maven Central publication shape.

## Findings and resolutions

| Severity | Finding | Resolution |
|---|---|---|
| P0 | Standalone artifacts used official `org.springframework.ai` group/package names | Moved to `org.springaicommunity`; upstream rename is documented separately |
| P0 | A global signer could sign public MCP connections unintentionally | Added exact connection endpoint routing |
| P1 | All AWS connections had to share one region/service | Added per-connection delegates and conflict validation |
| P1 | HTTP could carry reusable SigV4 headers in clear text | HTTPS required by default; explicit local-test escape hatch |
| P1 | No Maven publication, sources, Javadoc, SCM, license, or signing metadata | Added reproducible Maven publications and optional in-memory PGP signing |
| P1 | Starter did not include the official MCP client starter | Starter now provides the complete runtime dependency set |
| P1 | Target versions were behind current upstream | Migrated to Spring AI 2.0.0, Boot 4.1.0, Java 17 |
| P1 | The 2.0.0 bridge replaced the builder's single request-customizer slot and could drop application sync/async beans | The bridge now adapts ordered sync beans, appends ordered async beans, adds SigV4 last, and installs an MCP SDK delegating customizer |
| P1 | Pipeline validation covered only no-customizer and mixed-customizer cases | Added real transport tests for none, sync-only, async-only, mixed, and direct-builder replacement behavior |
| P1 | Copying only three authentication headers dropped `X-Amz-Content-Sha256` even though the AWS signer included it in `SignedHeaders` | Preserve the payload hash header conditionally and cover it with unit and live AgentCore tests |
| P1 | A directly registered signer could receive requests for an unintended endpoint | Bound every signer instance to one exact normalized endpoint and leave unmatched requests unchanged |
| P1 | OAuth or another customizer could silently lose ownership of `Authorization` | Reject a pre-existing authorization header before resolving AWS credentials |
| P1 | The fallback closeable AWS credentials provider was not managed by Spring | Register conditional fallback credentials and region provider beans and let the context manage their lifecycle |
| P1 | `check` enforced formatting but not the rest of Spring AI's source-quality rules | Added Spring AI-aligned Checkstyle plus Error Prone and NullAway main-source compilation |
| P2 | Authentication-name typos silently produced ineffective configuration | Added fail-fast name and URL validation |
| P2 | Live test required `AWS_SESSION_TOKEN`, excluding long-lived/default-chain credentials | Live test now gates on gateway URL and uses the default provider chain |
| P2 | Spring AI 2.0.0 and current `main` use different HTTP customizer integration | Added a capability-detected 2.0.0 transport bridge plus full transport and signed-header integration tests |
| P2 | No CI, contribution, security, release, or proposal workflow | Added repository governance and automation files |
| P3 | Public documentation described obsolete `ifUnique` manual composition | Rewritten for ordered Spring AI 2.0 customizers |

## Proposal readiness

| Destination | Verdict | Reason |
|---|---|---|
| Independent repository | Ready | The library boundaries, publication artifacts, docs, CI, and governance files are present |
| Spring AI Community | Proposal-ready | `mcp-security` and `spring-ai-agentcore` establish compatible security/module and AWS integration precedents |
| Spring AI core | Discussion-ready | The implementation proves the feature, but upstream placement and the named-customizer extension point need maintainer agreement before a Maven-native PR |

## Documentation and code-quality audit

The repository keeps canonical documentation under version control rather than in a GitHub Wiki.
This is sufficient for the initial release and ensures architecture and compatibility changes are
reviewed with code. `AGENTS.md` now records the security, module, nullability, compatibility, and
verification invariants required for safe AI-assisted changes. `docs/maintainer-guide.md` records
the hosting tasks that must wait for confirmed maintainer identities.

The audit added Spring Java Format and Spring AI-aligned Checkstyle as build-enforced quality
gates, together with Error Prone's locale check and NullAway in JSpecify mode for main sources.
CI and release workflows run these gates through `check` and validate the Gradle wrapper.
Java compilation also enables broad `-Xlint` warnings, JavaDoc uses doclint, source archives are
reproducible, and every Java source is checked for the project license header.
The package-level JSpecify contract explicitly permits bodyless MCP request payloads.
Import ordering, lambda style, package documentation, JavaDoc paragraph tags, and public
configuration comments follow the corresponding Spring AI conventions.

The following setup remains intentionally deferred:

- module-specific CODEOWNERS entries until additional maintainers are confirmed;
- named security and conduct contacts;
- Maven Central namespace and signing-secret configuration.

## Remaining decisions before public release

1. Confirm the final Maven namespace.
2. Obtain maintainers' direction on official Spring AI inclusion versus a community extension.
3. Configure a Maven Central-compatible repository endpoint and signing secrets.
5. If accepted into Spring AI Community, decide whether to adopt its optional parent/reusable
   release workflow or keep the equivalent Gradle publication pipeline.

## Live AgentCore validation

On 2026-08-10, `AgentCoreGatewaySigV4IT` ran in `us-east-1` against a disposable Gateway using
`AWS_IAM` inbound authorization and a one-tool Lambda target. It completed MCP initialize,
tools/list, tools/call, and graceful close. The first run exposed a real signing defect: the AWS
signer included `x-amz-content-sha256` in `SignedHeaders`, but the JDK request adapter did not copy
that payload hash header to the outgoing request. After preserving that required header, the same
live test passed. The test was repeated after exact-endpoint binding, competing-authorization
rejection, and default-provider lifecycle changes; initialize, tool discovery, tool invocation, and
graceful close still passed. All temporary AWS resources were removed after validation.

## Known extension-point limitation

An application `McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>` can call a request
customizer setter after Spring AI or this library has installed a delegate. The MCP SDK builder
stores one async customizer and exposes no getter, so separate builder-level setters are
last-writer-wins and cannot be safely merged by an extension. Register request-customizer beans or
install one explicitly composed delegate instead.
