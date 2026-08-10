# Contributing

Thank you for helping improve Spring AI MCP SigV4.

## Before opening a change

- Search existing issues and pull requests.
- Use an issue or discussion first for public API, configuration, dependency, or upstream-scope
  changes.
- Never include AWS credentials, signed authorization headers, account IDs, or private gateway
  URLs in code, logs, fixtures, screenshots, or commits.

## Development workflow

Use the Java 17.0.19 Liberica toolchain declared in `.sdkmanrc` and run:

```shell
./gradlew clean check publishToMavenLocal
```

Use the focused module test task while iterating, but run the full command before opening a pull
request. Java test classes use `*Tests`; live tests use `*IT` and remain disabled by default.

To apply the repository's Spring Java Format rules, run:

```shell
./gradlew format
```

`check` also runs the Spring AI-aligned Checkstyle rules and compiles main sources with Error Prone
and NullAway in JSpecify mode.
Do not suppress a rule unless the framework requires a construct that the rule cannot model; keep
such suppressions narrow and explain the reason in code or build configuration.

Changes should include focused tests.
Live tests must remain opt-in and use short-lived credentials.
Java sources follow Spring style: tabs, LF line endings, explicit imports, one top-level type per
file, and one sentence per line in Markdown where practical.

Public API changes require JavaDoc, an `@since` tag, compatibility analysis, and a changelog entry.
New configuration properties require generated/additional metadata, a binding test, and updates to
`docs/configuration.md`.

## Pull requests

- Explain the problem and why the chosen design belongs in this library.
- Describe security and compatibility impact.
- Update configuration metadata and documentation when adding properties.
- Keep core signing, Boot auto-configuration, and documentation changes separable where possible.
- Confirm that AI-assisted code was reviewed by a human who remains accountable for it.

Use a Developer Certificate of Origin `Signed-off-by` trailer on contributed commits. It is also
required if a change is later proposed to Spring AI upstream.

See `AGENTS.md` for repository invariants that also apply to AI-assisted changes.
