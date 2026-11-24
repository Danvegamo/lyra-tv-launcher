# AGENTS.md

Guidance for AI coding agents working in this repository.

- Lyra: Android app in Kotlin + Jetpack Compose for Fire TV and Android TV (Fire OS 7, Android 9, `minSdk` 28). Single module `:app`; `buildSrc` holds a small ASM transform (`CompatUrl`).
- Build: `./gradlew assembleDebug`. Tests: `./gradlew testDebugUnitTest` (JVM tests only; Android stubs return defaults).
- Configuration comes from `config.local.properties` (git-ignored; template in `config.example.properties`) and from JSON files in `datos/`, packaged as assets.
- Code, identifiers and comments are in Spanish. Keep that convention.
- Never commit secrets, APKs, `local.properties`, `datos/gustos.json` or any personal data (screenshots, viewing history, IPs, serials).
- Never add code that disables, kills or uninstalls the packages in `app/src/main/assets/no_tocar.txt`.
