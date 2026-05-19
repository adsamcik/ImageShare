# Contributing to ImageShare

Thanks for your interest! This guide covers the contribution process.

## Code of Conduct

This project follows the [Contributor Covenant](./CODE_OF_CONDUCT.md). By participating you agree to uphold it.

## Reporting bugs

1. Search [existing issues](https://github.com/adsamcik/ImageShare/issues) first.
2. Open a [bug report](./.github/ISSUE_TEMPLATE/bug_report.md) with reproducible steps, device + Android version, and logs (scrub PII).

**Security vulnerabilities**: see [SECURITY.md](./SECURITY.md). Do not file public issues.

## Suggesting features

1. Search existing issues + discussions.
2. Open a [feature request](./.github/ISSUE_TEMPLATE/feature_request.md) describing the user problem first, then the proposed solution.

## Development setup

- Android Studio Iguana+ or Hedgehog with AGP 8.x
- JDK 17
- Android SDK 36

```bash
git clone https://github.com/adsamcik/ImageShare.git
cd ImageShare
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The native JPEG and AVIF prebuilts are skipped by default. To work on native paths, see `core/processing/README.md` (TBD).

## Pull requests

1. Fork + branch from `main`. Branch naming: `feature/short-name`, `fix/short-name`, `docs/short-name`.
2. Make changes with **tests**. Unit tests live in `<module>/src/test/`, instrumented in `<module>/src/androidTest/`.
3. Run lint + tests before pushing:
   ```bash
   ./gradlew :app:lintDebug :app:testDebugUnitTest :sdk:imageshare-api:testDebugUnitTest
   ```
4. Commit messages: imperative mood, ~70 char first line, optional body explaining "why". Include `Co-authored-by:` if pair-programmed.
5. Open a PR against `main` using the [PR template](./.github/PULL_REQUEST_TEMPLATE.md).
6. CI must pass. Reviewer will request changes or merge.

## Code style

- Kotlin: official Kotlin style. Run `./gradlew ktlintFormat` if/when ktlint is added.
- 4-space indents, no tabs.
- `// comment` for explanation, KDoc `/** */` for public API.
- No emoji in code; emoji OK in commit messages and docs.

## Architectural decisions

Significant changes should follow the RFC pattern in [`docs/RFCs/`](./docs/RFCs/). Start with a draft, iterate via PR, mark `Approved` once merged.

## License

By contributing you agree your contributions will be licensed under [GPL v3](./LICENSE).
