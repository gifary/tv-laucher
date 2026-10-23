# Repository Guidelines

## Project Structure & Module Organization

`launcher/` is the Android TV launcher application. Java sources live in `launcher/app/src/main/java/dev/gtv/launcher/`; layouts, drawables, fonts, and string/theme resources are under `launcher/app/src/main/res/`. Gradle configuration is in `launcher/*.gradle` and `launcher/app/build.gradle`. Device maintenance utilities are in `tools/` (Python ADB helpers and Bash backup/disable scripts). `shots/` holds screenshots, of which only the four referenced by `README.md` are tracked. `apk/`, `backup/`, and `disabled-by-us.txt` are local-only device data kept out of version control. Treat device captures and APKs as data; avoid modifying them unless the task requires it.

## Build, Test, and Development Commands

Run Gradle from the repository root with `gradle -p launcher assembleDebug` to build the debug APK; output is under `launcher/app/build/outputs/apk/`. No Gradle wrapper is included; use installed Gradle compatible with Android Gradle Plugin 8.7.3. Run `gradle -p launcher test` for available JVM tests. No test sources or framework configuration are present.

For device work, `python3 tools/tv.py 'getprop ro.build.version.sdk'` runs an ADB shell command. Defaults to `127.0.0.1:15555`; override with `TV_HOST` and `TV_PORT`. `tools/backup.sh` captures device state before changes. `tools/disable.sh <package...>` disables packages and refuses protected system packages; these commands change device state.

## Coding Style & Naming Conventions

Follow the existing Java style: four-space indentation, Java 17-compatible code, `UpperCamelCase` classes, and `lowerCamelCase` methods and fields. Keep Android resources in the appropriate `res/` type directory and use lowercase underscore names for resource files. Python tools use four spaces and `snake_case`; shell scripts use Bash and quote expansions where practical. No formatter or linter is configured.

## Testing Guidelines

There are no existing automated tests in the repository. Build launcher changes and verify remote navigation on an Android TV device when available. Review utility commands before running them against a TV.

## Commit & Pull Request Guidelines

This repository is pushed to `git@github.com:gifary/tv-laucher.git`. Use a short imperative commit subject (for example, `Fix launcher focus restoration`). Describe user-visible changes and checks; include screenshots for visual changes and note device-state effects or required configuration.

## Security & Configuration Tips

Do not commit `launcher/local.properties`, which may contain machine-specific SDK paths, or add credentials, private network details, and unredacted device captures. Review captures before sharing.
