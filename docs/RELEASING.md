# Releasing

## Version scheme

`gradle.properties` holds `pluginVersion=A.B.C.SNAPSHOT`. The fourth segment is a local/CI
development marker and is never published; everything that matters — the release draft tag, the
CHANGELOG section, the Marketplace upload — addresses the release by its first three segments.

No build step rewrites `gradle.properties`, and no task increments anything automatically. The
release workflow overrides the version for the publishing build only:

```
./gradlew publishPlugin -PpluginVersion="<release tag>"
```

Bump `pluginVersion` by hand. Do not reintroduce build-time version mutation: reading or writing
`gradle.properties` from the configuration phase breaks the configuration cache and desynchronizes
artifact names from the patched `plugin.xml`.

## Local development

Nothing to do. `./gradlew buildPlugin` produces the same `…-A.B.C.SNAPSHOT.zip` and overwrites it,
and `runIde` reuses the sandbox for that version. This is expected for this scheme.

## Before every release

- Pick a version whose tag is not taken yet: `git tag -l | grep <version>` and the Releases page.
  If a release for that tag already exists, the Build workflow skips the draft with a notice (main
  normally keeps the previous version until the next bump); if only the tag exists, the Build
  workflow fails instead of creating a draft against that older commit.
- Add the release notes to the `[Unreleased]` section of `CHANGELOG.md`. `patchChangelog` stamps a
  heading with the first three segments, so a section for that version must not already exist.
- The `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` repository
  secrets must be present, otherwise `publishPlugin` fails during signing or upload.

## Release procedure

1. Raise the first three segments in `gradle.properties` (`1.0.8.SNAPSHOT` → `1.0.9.SNAPSHOT`).
2. Fill in `[Unreleased]` in `CHANGELOG.md`.
3. Merge to `main`. The Build workflow runs `buildPlugin`, then `test` runs `check` and `verify` runs
   `verifyPlugin` (IntelliJ Plugin Verifier over the IDE builds listed in `build.gradle.kts`; allow ~25 min,
   the Community targets are what catch an accidental Ultimate-only API reference), and only then
   `releaseDraft` creates a draft release tagged `1.0.9` with the notes from `[Unreleased]`. Either job
   failing means no draft. A version that is already released is skipped with a notice, so main stays
   green between releases without a version bump.
4. Review the draft and publish it. If the tag does not exist yet, `gh release create` creates it
   from `main`; `target_commitish` is ignored when the tag already exists. Publish the draft from
   the same push that bumped the version, without merging unrelated work in between, so the release
   content is unambiguous.
5. The `released` event triggers the Release workflow: it checks out the tag, runs `patchChangelog`
   and `publishPlugin` with `-PpluginVersion=1.0.9`, uploads `build/distributions/*.zip` as a
   release asset and opens a `changelog-update-<version>` pull request. The workflow is idempotent:
   once the zip is attached to the release, a re-run (or flipping a pre-release to a full release)
   skips the Marketplace upload, and a failed changelog-PR step does not fail the release.
6. Merge that pull request. `gradle.properties` keeps `1.0.9.SNAPSHOT` until the next release.

## Notes

- With an empty `[Unreleased]` section the draft has no notes, the Patch Changelog step is skipped
  by its `if:` condition, and `patchChangelog` — a dependency of `publishPlugin` — is a no-op. The
  publish itself still proceeds.
- Publishing a version that is already on the Marketplace is rejected by the Marketplace.
- Verify the resolved version without publishing anything:

  ```
  ./gradlew properties --console=plain -q | grep '^version:'
  ./gradlew getChangelog --unreleased --no-header --console=plain -q
  ```
