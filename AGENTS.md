# Agent instructions

## Intention docs and lessons

Before changing overload handling, the chooser popup or the intentions preview, or verifying in a
running IDE, read [docs/INTENTION.md](docs/INTENTION.md): why the resolution is conservative, which
filters are dead code because the Kotlin reference already narrows the candidates, and the Windows
traps in the UI harness (`WS_EX_NOREDIRECTIONBITMAP` popups invisible to screen captures,
DPI-virtualized rects, silent focus loss, sandbox file locks).

When a debugging round teaches something the code cannot tell the next session — a platform
behaviour, an assumption that sent you the wrong way, a probe that settled the question — write it
to that doc before committing. Start a new `docs/<AREA>.md` only for a distinct area.

- Symptom (what you see), then cause, then what to do. Name the evidence: probe output, window dump,
  the test that fails without the fix. API docs alone are not a lesson.
- Only what code, git history and upstream docs cannot recover. Routine fixes and implementation
  restatements stay out; user-visible effects go to the changelog.
- Point to the doc from the relevant section here in one or two sentences: when to read it and what
  it covers. Don't copy entries — a copy goes stale first.
- Keep entries true: if a change contradicts one, rewrite or delete it in the same commit. Never
  append a correction that leaves two entries disagreeing — the next session trusts the doc.

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
