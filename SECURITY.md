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

## Security boundaries

- This project signs outbound MCP client requests; it does not validate signatures on servers.
- Auto-configuration requires HTTPS unless `allow-insecure-http=true` is explicitly set.
- Credential loading is delegated to the AWS SDK provider chain or an application provider.
- Signers are bound to exact normalized endpoints and leave all other endpoints unchanged.
- The library does not log credentials or authorization header values.
- A request with an existing `Authorization` header is rejected so OAuth Bearer and SigV4 cannot
  be combined silently on one connection.
