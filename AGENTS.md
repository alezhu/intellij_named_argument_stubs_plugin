# Agent instructions

## Working on the intention

Before changing overload handling, the chooser popup or the intentions preview — and before any
verification in a running IDE — read [docs/INTENTION.md](docs/INTENTION.md). It records why the
resolution is deliberately conservative, which filters are dead code because the Kotlin reference
already narrows the candidates, and the Windows-specific traps that cost real debugging rounds in the
UI harness (`WS_EX_NOREDIRECTIONBITMAP` popups invisible to screen captures, DPI-virtualized rects,
silent focus loss, sandbox file locks).

## Changelog

Update the `[Unreleased]` section of [CHANGELOG.md](CHANGELOG.md) in the same commit as every
user-visible change, not at release time. User-visible means what the plugin does in the IDE: the
intention row appearing or not, what the call turns into, supported Kotlin constructs and IDE
versions. Refactors, tests, docs and CI stay out.

Write for a plugin user, not an implementer, and in English: name the effect, not the generated
expression behind it ("support vararg parameters", not `*arrayOf(...)`). No PSI, class or method
names, and no explanation of the internal cause. One line per change under the usual
`Added`/`Changed`/`Fixed` heading.

Before committing, reread `[Unreleased]` and delete entries that no longer describe the change —
reverted, replaced by another fix, or otherwise never shipped to users. A change users never saw is
not a changelog item.

## Releases

Before preparing a release — including creating or merging a `release/*` branch — read
[docs/RELEASING.md](docs/RELEASING.md) and follow it. It defines the `A.B.C.SNAPSHOT` version
scheme, the manual bump, and what the Build and Release workflows expect.

Keep the invariant: no Gradle task may read or write `gradle.properties` to derive or bump the
version. Version mutation belongs to the developer and to `-PpluginVersion` in release CI.
