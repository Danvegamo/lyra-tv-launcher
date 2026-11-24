# Contributing

Thanks for helping. Issues and pull requests are welcome in English or Spanish.

- **Keep secrets out.** Never commit `config.local.properties`, `local.properties`, `datos/gustos.json`, APKs or keystores. They are in `.gitignore`; keep it that way.
- **No personal data.** Don't include screenshots, viewing history, IP addresses, device serials or account names in commits, tests or issues.
- **Tests.** Run `./gradlew testDebugUnitTest` (or `scripts\pruebas.ps1` on Windows) before opening a pull request, and add a test when you fix a bug.
- **Style.** Kotlin with Jetpack Compose. Code identifiers and comments are in Spanish; follow the surrounding style. Keep changes small and focused.
- **Don't break the TV.** The launcher must never touch the packages listed in `app/src/main/assets/no_tocar.txt`. Anything that runs adb commands needs a test that proves those packages are safe.
- **Test on a device** when you change UI, accessibility or adb behavior, and say in the pull request which device and Fire OS version you used.

- **License.** The project is GPL-3.0. By contributing you agree your changes are released under the same license.
