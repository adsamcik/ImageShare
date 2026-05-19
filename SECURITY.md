# Security Policy

## Supported versions

ImageShare is pre-launch. There is no released version yet.

Once released, the latest minor of the most recent major will receive security updates.

| Version | Supported |
|---------|-----------|
| `main` (HEAD) | ✅ |
| Released versions | (TBD post-launch) |

## Reporting a vulnerability

**Do NOT open a public issue for security vulnerabilities.**

Instead:

1. Use GitHub's [private vulnerability reporting](https://github.com/adsamcik/ImageShare/security/advisories/new) (preferred).
2. Or email the maintainer at `imageshare-security@protonmail.com` (or via the GitHub profile contact for `adsamcik`).

Please include:

- Affected component (app, SDK, Transform API ContentProvider, etc.)
- Affected version / commit SHA
- Reproduction steps
- Impact assessment
- Proposed remediation if any

## Disclosure timeline

- **Acknowledge** within 5 business days
- **Triage and confirm** within 14 days
- **Patch + advisory** within 90 days for high-severity; sooner if critical
- **Public disclosure** coordinated with reporter

## Scope

In scope:
- Confused-deputy / privilege-escalation attacks against the Transform API
- Resource-exhaustion / DoS against the Transform API
- Information leaks (EXIF/GPS leaking past stripping)
- Local file-permission escapes

Out of scope:
- Rooted devices
- Hardware-level attacks
- Social engineering
- Outdated Android versions below `minSdk` (currently 26)

## Hall of fame

Security researchers who follow responsible disclosure will be credited (with permission) in advisories and the CHANGELOG.

See also: [`docs/transform-api/threat-model.md`](./docs/transform-api/threat-model.md).
