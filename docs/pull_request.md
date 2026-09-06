# Pull requests

Contributions should target `develop`; `main` is reserved for releases. External contributors may
open a pull request from a fork, while maintainers may use a branch in this repository.

Before requesting review:

- Keep the change focused and explain its user impact and verification in the PR description.
- Run the SecureChat configuration audit and the relevant unit, lint, or screenshot tests.
- Add or update tests for behavior changes.
- For UI changes, include screenshots for light and dark themes and update visual baselines when
  necessary.
- Choose one applicable `PR-` changelog label and confirm Git LFS tracks required binary fixtures.
- Do not commit credentials, signing keys, access tokens, production user data, or private logs.

At least one maintainer approval and passing required checks are expected before merge. Resolve review
feedback with follow-up commits that remain easy to inspect. Use a merge commit when preserving a
useful branch history; squash noisy work-in-progress history.

Renovate and the Gradle-wrapper workflow may open dependency maintenance PRs. They receive the same
review and CI requirements as human-authored changes. Parent-company review teams, CLA automation,
Danger bots, and project-board triage are not part of this repository.
