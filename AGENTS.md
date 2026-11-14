# Repository instructions

Human contributors remain responsible for every change.
See [README.md](README.md) for module responsibilities and supported versions, and [CONTRIBUTING.md](CONTRIBUTING.md) for style, verification, and release requirements.

## Architecture and security

- Keep signing in the Boot-independent core and Boot/Spring AI integration in auto-configuration.
- Use standard AWS provider interfaces; do not add AWS service clients or cache resolved credentials.
- Resolve credentials for each request on bounded-elastic and sign the exact serialized UTF-8 body.
- Install endpoint-bound signers only through named transports and the SDK's additive API; never expose the router as a global request-customizer bean.
- Keep unsigned connection aliases unsigned, and require HTTPS by default.
- Run signing after application request customization; transport registration order and request-customizer ordering are distinct.
- Reject pre-existing SigV4-owned headers and preserve application/MCP wire headers.
- Copy only signer-owned output back to the JDK builder, including the payload hash when signed.
- Keep header policy in the core, telemetry dependencies test-only, and excluded propagation headers on the wire.
- Do not add vendor detection, production request observation, or HttpClient wrappers.
- Never log credentials, signed headers, session IDs, tool payloads, or private infrastructure identifiers.
- Keep bodyless GET/DELETE payloads nullable under JSpecify.

## Change discipline

- Preserve Apache headers, Spring Java Format, useful public Javadoc, and `@since` tags.
- Update metadata, binding tests, and the configuration reference with property changes.
- Prefer public behavioral or wire-level assertions to tests of private implementation details.
- Keep live AWS tests opt-in and avoid long-lived credentials.
- Do not restore the removed reflection fallback or change provisional publication coordinates without confirmed namespace ownership.
- Keep generated evidence and dated review diaries outside Git.

## Verification

```shell
./gradlew clean check verifyConsumers publishToMavenLocal
./gradlew check verifyConsumers -PtestJavaVersion=21
```

`check` includes artifact/metadata checks and collector-free agent tests; `verifyConsumers` runs independent published-artifact builds.
Neither requires AWS.
When AWS integration behavior changes, also run the authorized opt-in live tests documented in CONTRIBUTING.md with short-lived credentials and a disposable gateway.
