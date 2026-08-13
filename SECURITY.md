# Security policy

## Supported versions

Security fixes are provided for the latest released minor line.
This repository currently contains a pre-release `0.1.0-SNAPSHOT` implementation.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability.
Use GitHub private vulnerability reporting after the repository is published, or contact the
maintainer through the private security contact configured for the hosting organization.

Include the affected version, impact, reproduction steps, and a minimal redacted example.
Never send live AWS credentials or complete SigV4 `Authorization` headers.

## Safe diagnostics

MCP authorization error handling can expose request metadata to application code. Do not log a
complete request snapshot or header map, including through exception arguments. For example, avoid:

```java
log.error("Authorization failure: {}", requestSnapshot);
```

Treat `Authorization`, `X-Amz-Security-Token`, and all `X-Amz-*` headers as sensitive diagnostic
data. Log only a fixed error category or a deliberately allow-listed set of non-sensitive fields.
Applications must also redact private gateway URLs when they identify internal infrastructure.

The library must never expose AWS access keys, secret keys, session tokens, SigV4 authorization
values, private gateway URLs, or credential objects through logs, exception messages, or
`toString()` output.

## Security boundaries

- This project signs outbound MCP client requests; it does not validate signatures on servers.
- Auto-configuration requires HTTPS unless `allow-insecure-http=true` is explicitly set.
- Credential loading is delegated to the AWS SDK provider chain or an application provider.
- Signers are bound to exact normalized endpoints and leave all other endpoints unchanged.
- The library does not log credentials, private endpoints, or authorization header values.
- A request with any pre-existing SigV4-owned header is rejected before credential resolution so
  OAuth Bearer, stale session tokens, and other signing metadata cannot be combined silently with
  a new signature.
