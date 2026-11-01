# Agent instructions

## Working on the intention

Before changing overload handling, the chooser popup or the intentions preview — and before any
verification in a running IDE — read [docs/INTENTION.md](docs/INTENTION.md). It records why the
resolution is deliberately conservative, which filters are dead code because the Kotlin reference
already narrows the candidates, and the Windows-specific traps that cost real debugging rounds in the
UI harness (`WS_EX_NOREDIRECTIONBITMAP` popups invisible to screen captures, DPI-virtualized rects,
silent focus loss, sandbox file locks).

## Releases

Before preparing a release — including creating or merging a `release/*` branch — read
[docs/RELEASING.md](docs/RELEASING.md) and follow it. It defines the `A.B.C.SNAPSHOT` version
scheme, the manual bump, and what the Build and Release workflows expect.

Keep the invariant: no Gradle task may read or write `gradle.properties` to derive or bump the
version. Version mutation belongs to the developer and to `-PpluginVersion` in release CI.
