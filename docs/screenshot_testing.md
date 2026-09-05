# Screenshot testing

SecureChat uses Paparazzi and Roborazzi visual baselines. Pull requests verify Paparazzi baselines in
`.github/workflows/tests.yml`, while `.github/workflows/recordScreenshots.yml` can record and commit
updated baselines after the `Record-Screenshots` label is applied or the workflow is run manually.

Install Git LFS and its repository hooks before working with baseline PNGs:

```bash
git lfs install --local
```

Record locally with:

```bash
./gradlew recordPaparazziDebug
./gradlew :libraries:compound:recordRoborazziDebug
```

Verify with:

```bash
./gradlew :tests:uitests:verifyPaparazziDebug
```

Review every changed image in light and dark themes before committing it, then run
`./tools/git/validate_lfs.sh`. Failure images are written under the relevant module's
`build/paparazzi/failures` or `build/roborazzi/failures` directory.

The public screenshot gallery is intentionally disabled until SecureChat baselines have been
re-recorded. `screenshots/index.html` therefore displays only a notice and has no generated data or
gallery JavaScript.
