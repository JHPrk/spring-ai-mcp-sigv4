# Maintainer guide

This document covers repository administration that cannot be encoded in the build. It should be
completed after the final GitHub organization and Maven namespace are known.

## Repository setup

- Add at least one additional maintainer and split `.github/CODEOWNERS` by module when useful.
- Enable private vulnerability reporting and GitHub Discussions.
- Protect `main`; require the CI workflow, review, and resolved conversations before merge.
- Enable secret scanning, push protection, Dependabot alerts, and automatic branch deletion.
- Create `bug`, `enhancement`, `security`, `dependencies`, and `breaking-change` labels.
- Revisit provisional Maven coordinates after the hosting organization is decided.

The repository owner is the default code owner until additional maintainers are confirmed.
`SECURITY.md` and `SUPPORT.md` use GitHub's private reporting channels instead of publishing a
personal security address.

## Triage

- Redirect general Spring AI questions to the upstream discussion forum.
- Ask for a minimal, redacted reproduction for signing or auto-configuration defects.
- Treat credential exposure, signing bypass, unintended endpoint signing, and clear-text transport
  as security issues rather than public bug reports.
- Label changes to public Java API, property names, dependency baselines, or signing behavior as
  compatibility-sensitive.

## Release gate

Before a public release:

1. complete the checklist in `releasing.md`;
2. run the full local build and the live AgentCore integration test with short-lived credentials;
3. verify the release candidate from clean Maven and Gradle consumer projects;
4. review generated POM, source, Javadoc, signature, and checksum files;
5. publish release notes describing compatibility and security-relevant changes;
6. create the signed tag only from the exact commit used to build the staged artifacts.

## Documentation policy

Keep durable architecture, configuration, compatibility, and release documentation in the
versioned `docs/` directory so it is reviewed with code. A GitHub Wiki is not required for the
initial release. Add one only for frequently changing tutorials or operational FAQs, and link each
Wiki page back to the versioned canonical document where applicable.
