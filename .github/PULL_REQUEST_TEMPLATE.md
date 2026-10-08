## What changed

<!-- What the change does and why. Link the issue if there is one. -->

## How it was tested

<!-- Run both commands, then paste Gradle's BUILD line and the line the second command prints. That one is the test totals: Gradle itself prints none when every test passes. CI runs the first command with an empty secrets.properties and no app/google-services.json. Only those two lines: the rest of Gradle's output prints absolute paths with your user name in them, and the XML files carry your computer's name. -->

```
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
cat app/build/test-results/testDebugUnitTest/*.xml | grep -o '<testsuite [^>]*>' | sed -E 's/.* tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3 \4/' | awk '{t+=$1; s+=$2; f+=$3; e+=$4} END {print t " tests, " f " failures, " e " errors, " s " skipped"}'
```

- Gradle result line: <!-- "BUILD SUCCESSFUL in 1m 12s" -->
- Test totals line: <!-- e.g. "757 tests, 0 failures, 0 errors, 2 skipped" (the cases that need a relay baked into the build skip without one) -->
- Device or emulator it was verified on, with its Android version: <!-- e.g. "Pixel 9, Android 16" or "emulator, API 36 system image" -->
- What was tried there:

## Checklist

- [ ] No secrets or personal data anywhere in the diff, the tests, the screenshots or this description: no token, no Cloudflare service-token id or secret, no `secrets.properties` or `google-services.json` content, no relay address or other host name, no phone number, e-mail address, real name, user name in a path, computer name, private IP address or message text, and no APK. Fixtures use synthetic values only.
- [ ] Tests added or updated for the change (JVM unit tests under `app/src/test/`). They pass with an empty `secrets.properties` and no `google-services.json` too, which is how CI runs them: a test that needs a value from either file skips without it, as the two in `RelayOriginTest` do.
- [ ] Docs updated where behaviour, a `secrets.properties` key or a log line changed (`README.md`, `SECURITY.md`, `docs/setup.md`, `secrets.properties.example`). The README's Troubleshooting section quotes the app's texts word for word: if a text the app shows changed, it changed there too.
- [ ] UI rules kept: no colour literal (`Color(0x…)`), `.dp` or `.sp` outside `app/src/main/java/io/github/avtraang/selfbubbles/ui/theme/` (screens take colours, `Spacing`, `Dimens` and type styles from the theme), and icons from `material-icons-core` only (one it lacks is drawn in `ui/theme/Icons.kt`; no `material-icons-extended`).
- [ ] Behaviour verified on a device or an emulator, named above: the unit tests never start the app.
