# Agent instructions

## Releases

Before preparing a release — including creating or merging a `release/*` branch — read
[docs/RELEASING.md](docs/RELEASING.md) and follow it. It defines the `A.B.C.SNAPSHOT` version
scheme, the manual bump, and what the Build and Release workflows expect.

Keep the invariant: no Gradle task may read or write `gradle.properties` to derive or bump the
version. Version mutation belongs to the developer and to `-PpluginVersion` in release CI.
