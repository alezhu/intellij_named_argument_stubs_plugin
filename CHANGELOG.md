<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Kotlin named arguments stubs plugin changelog

## [Unreleased]

### Added

- Ask which overload the call means when several fit instead of generating names from the first one.
- Preview the expansion in the intention popup when exactly one overload fits.
- Support functions with a `vararg` parameter.

### Fixed

- Do not offer the intention for a call without a parenthesised argument list (`target { }`).
- Do not offer the intention when a call passes more arguments than the function takes; expanding it used to drop the extra arguments silently.
- Keep comments in the call's argument list when it is expanded instead of dropping them.
- Keep a trailing lambda out of the generated named arguments instead of stubbing its parameter twice.
- Do not expand a call through a function-type value (`target(1, 2)` where `target` is a callback or a lambda), which produced `null = …` names or named arguments Kotlin prohibits.

## [1.0.8] - 2026-05-15

### Changed

- Adapt to IntelliJ Platform 262.*
- Set minimum supported version IntelliJ Platform to 2023.3

## [1.0.7] - 2026-02-28

### Changed

- Adapt to IntelliJ Platform 261.*

## [1.0.6] - 2025-05-29

### Changed

- Adapt to IntelliJ Platform 252.*
- Upgrade Gradle Wrapper to `8.14.1`
- Dependencies - upgrade `org.jetbrains.intellij.platform` to `2.6.0`
- Dependencies - upgrade `org.jetbrains.kotlin.jvm` to `2.1.21`

## [1.0.5] - 2025-01-25

### Changed

- Adapt to IntelliJ Platform 251.*
- Declare K2 support

### Fixed

- Fix Android Studio compatibility

## [1.0.4] - 2024-10-16

### Added

- Add templates for display code example before and after invoke intension

### Changed

- Adapt to IntelliJ Platform 243.*
- Upgrade Gradle Wrapper to `8.10.2`
- Adapt plugin to IntelliJ Platform Gradle Plugin (2.x)
- Disable caching context
- Move plugin to Kotlin intensions category
- Limit plugin to Kotlin language

### Fixed

- Fix plugin family name
- Fix logging
- Fix exceptions handling

## [1.0.3] - 2024-06-24

### Changed

- Adapt to IntelliJ Platform 242.*

## [1.0.2] - 2023-12-26

### Added

- README.md

### Changed

- Adapt to IntelliJ Platform 233.*

## [1.0.1] - 2023-01-12

Initial release

[Unreleased]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.8...HEAD
[1.0.8]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.7...1.0.8
[1.0.7]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.6...1.0.7
[1.0.6]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.5...1.0.6
[1.0.5]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.4...1.0.5
[1.0.4]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.3...1.0.4
[1.0.3]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.2...1.0.3
[1.0.2]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/alezhu/intellij_named_argument_stubs_plugin/commits/1.0.1
