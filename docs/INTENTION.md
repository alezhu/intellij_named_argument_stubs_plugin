# Developing the intention

Hard-won constraints from building the overload-aware expansion and the chooser popup. Each entry is
symptom first, because that is what you will be staring at.

## Choosing between overloads

- Never resolve a callee with `multiResolve(true)[0]`. It silently returns the first declaration, so
  with two fitting overloads the generated names can come from the one the call does not mean, and
  the result is invalid code. Use `multiResolve(false)`, dedupe by `(containing file path, textRange)`
  (merged `expect`/`actual` declarations repeat the same PSI), and treat "several fit" as a question
  for the user, not a guess.

- Apply the popup's choice through that same `(path, textRange)` key, never through `Overload.signature`.
  The signature is display text with whitespace flattened, so two declarations can read the same
  (`s: String = """a\nb"""` vs `s: String = """a b"""` both flatten to one line); matching by text then
  applies the first declaration, not the picked one. The key also survives the re-resolution: the
  callback gets new PSI elements, but their file and range are unchanged.
  `testAppliesTheChosenDeclarationWhenTwoSignaturesReadTheSame` fails on text matching.

- The Kotlin callee reference usually narrows the candidates before any filter of ours runs. A call
  like `target(1, "2")` against `target(id, name, value = 0.0)` + `target(id, title)` resolves to the
  two-parameter one on its own. Consequence: a shape filter can be pure dead code. Before believing a
  filter is load-bearing, print the candidate count before and after it in a throwaway probe test
  (`raw=2 targets=[…]` vs `raw=1 targets=[…]`). Reasoning from the API docs is not evidence here.

- Arity must be checked even when the frontend leaves a single candidate. Returning that candidate
  untouched made `target(1, "2", 3.0)` against the only `target(id, name)` report available, and
  `process()` rewrote the argument list without the third argument — silent data loss. The argument
  name filter, unlike arity, may stay chooser-only: with one candidate the frontend has already
  picked the closest declaration, and a name absent from it keeps the stubs on that declaration
  (`testExpandsToTheCandidateLeftWhenNoOverloadMatchesTheNames`).

- A vararg parameter absorbs any number of positional arguments, so its declaration never fails the
  arity check. `process()` moves the extras into `name = *arrayOf(...)` and emits `*emptyArray()`
  when the vararg got nothing (both infer their type from the parameter). Two PSI facts cost a probe
  each: `KtValueArgument.getArgumentExpression()` returns the expression *without* the spread's `*`
  (`getSpreadElement()` is what marks it), and a vararg passed by name must carry the spread —
  `names = "a"` is rejected with "Assigning single elements to varargs in named form is prohibited".

- Availability must never be stricter than the purpose of the intention. Requiring every parameter not
  filled positionally to already appear as a named argument made `expansionTargets()` empty for
  `testFun2()` — a call with missing arguments, which is the primary case this plugin exists for. The
  symptom was that the row disappeared from Alt+Enter with no error anywhere: `isAvailable()` returned
  false. Positional arguments fill the leading parameters, and everything else is exactly what the
  expansion is supposed to generate. When torn between a wrong guess and a question, ask.

- `isAvailable()` swallows only `IndexNotReadyException` and reports the intention as unavailable while
  the index is being built. Every other exception propagates to the platform as an IDE error, so a
  missing row with a clean `idea.log` means a filter returned empty, not a hidden crash.

- The fit is deliberately type-blind, so two overloads differing only in parameter types both reach
  the chooser. Do not "fix" that by pulling in the Kotlin analysis API unless it is asked for.

## Rewriting the argument list

- `process()` replaces the whole `KtValueArgumentList` (parameters may be reordered and missing ones
  stubbed), so nothing inside it survives unless the rebuilt text carries it. Comments between
  arguments are `PsiComment` children of the list (`target(1, /* one */ "2")`), while a comment inside
  an argument belongs to that argument (`value = /* before expr */ 3.0`). The first kind has to be
  copied over by hand — a line comment trails the argument before it, a block comment leads the
  argument after it — and the second survives only because an existing argument contributes its whole
  `text`, not just the expression's. Building the new list from `valueText()` dropped both.
  `testKeepsACommentBetweenArguments`, `testKeepsLineCommentsThatFollowArguments` and
  `testKeepsACommentInsideAnAlreadyNamedArgument` fail on that earlier code.

## What the availability check costs

- Measured in the fixture, `isAvailable()` on a fresh `Context`, first call and then a repeat: a callee
  position costs 40–1300 ms the first time the file is resolved and 0 ms on every repeat; a name
  reference that is not a callee yields no candidates and costs 0 ms either way. Repeats are free
  because the platform caches resolution by PSI stamp. Do not add a cache of our own: it would store
  what is already cached, the intention instance is a project singleton, and `generatePreview` runs on
  `IntentionPreviewUtils.obtainCopyForPreview` copies, where one stale entry becomes a wrong expansion.

- `Context` checks the call shape before resolving — the name must be the callee of a `KtCallExpression`
  that has a parenthesised `valueArgumentList`. That is not a speed fix (see the measurements above); it
  is what keeps availability from promising an expansion `process()` refuses to write, because
  `process()` replaces that list. A bare trailing lambda `f { }` has `valueArgumentList == null`, so the
  row is gone instead of inert.

- A trailing lambda is a `KtLambdaArgument` child of the call, next to `KtValueArgumentList`, never an
  element of `valueArgumentList.arguments` (probe: `target(1) { }` → arguments `[1]`, lambdaArguments
  `[{ }]`). It always fills the last parameter, so `process()` skips that parameter and leaves the lambda
  where it is — otherwise it appended `block = block` beside the existing lambda, a duplicate argument.
  Availability counts the lambda as one filled slot (`parameters.size != namedArguments.size + 1`), and
  arity lets positional arguments fill only the parameters before it; a leading vararg still absorbs
  them. `target(1, 2) { }` against `target(id, block)` is refused instead of dropping `2`.

- In Kotlin PSI the callee of a constructor call `Holder(1, "2")` is a plain `KtNameReferenceExpression`
  whose parent is the `KtCallExpression` — there is no `KtUserType` between them, so a callee-position
  check needs no constructor special case. Verify any such assumption with a probe dump, not with the
  grammar.

- Probing through `NamedArgumentStubsIntentionTest.source()` quietly produces broken files: the helper
  wraps the usage in `val usage = …`, so `val f = ::target` becomes `val usage = val f = ::target` and the
  PSI you dump is an error element rather than what the IDE sees. Write probe usages as complete
  expressions (`::target`, `Pair(1, target)`), and check the file parses before believing a dump.

## Preview in the intentions popup

- `PsiElementBaseIntentionAction` does not implement `generatePreview`. The inherited `IntentionAction`
  default needs a file modifier and otherwise yields no panel at all, so the expansion simply is not
  previewed. Override `generatePreview(project, editor, file)`, take the element with the base class's
  `getElement(editor, file)`, mutate the preview copy inside `IntentionPreviewUtils.write`, return
  `IntentionPreviewInfo.DIFF`, and return `EMPTY` when the outcome would be a guess (several overloads).

- `IntentionPreviewUtils` and `IntentionPreviewInfo` live in `com.intellij.codeInsight.intention.preview`,
  not in `.impl`.

- `previewSession(Editor, Runnable)` only sets a thread-local; it does not create the copy. To exercise
  a preview in a test: `obtainCopyForPreview(myFixture.file)`, then
  `previewSession(myFixture.editor) { intention.generatePreview(project, myFixture.editor, copy) }`, then
  assert on the copy's text and on the original file being untouched.

- Kotlin cannot infer the throwable parameter of `write`: call it as `IntentionPreviewUtils.write<Throwable> { … }`.

- The preview panel can be hidden until the user presses Ctrl+Q ("Press Ctrl+Q to toggle preview" in the
  popup footer). That is platform behaviour and applies to stock intentions too — not a plugin bug.

## Popup placement and lifetime

- `show(RelativePoint.getSouthWestOf(editor.contentComponent))` anchors the popup to the bottom of the
  editor viewport. `showInBestPositionFor(editor)` puts it at the caret, which is where the user is looking.

- The chooser is opened from inside `invoke()`, so the write action it triggers runs after the popup
  closes and the intentions popup is gone. Re-read the PSI in that callback (`element.isValid()` and a
  fresh `Context`) instead of reusing captured elements, and open a command of your own —
  `writeCommandAction(project).withName(text)` — because the platform's command around `invoke()` has
  already finished by then; without a name the undo entry reads "Undefined".
  `testNamesTheCommandThatAppliesThePopupChoice` fails without the name.

## Verifying in a running IDE (Windows)

The harness lives in the gitignored `build/ui-sample/`: a scratch Kotlin project plus `drive.ps1`
(window locate / keys / screenshots / popup dumps) and `win.cs` (user32 P/Invoke). It drives
`./gradlew runIdeForUiTests`.

- IDE popups are `SunAwtWindow` with `exstyle 0x8000000` (`WS_EX_NOREDIRECTIONBITMAP`). GDI
  `CopyFromScreen` cannot capture them even though they are visible and topmost. Use
  `PrintWindow(hwnd, hdc, 2)`. Do not conclude "the popup flashes and closes" from a screen shot —
  enumerate the process windows (`EnumWindows` + class + rect + exstyle) and check that the window is
  alive. That wrong conclusion cost a whole debugging round here.

- The intentions list behaves the other way: it is often painted inside the main frame, so a screen
  shot catches it while `EnumWindows` finds nothing. Capture both ways before deciding a popup is gone.

- The display is 2560x1600 at 150% and PowerShell is DPI-unaware, so window rects come back
  virtualized (1707x1067). Dump at 2x the reported rect, and never mix image pixels with window
  coordinates without doubling.

- Focus: `SetForegroundWindow` can fail silently and the keystrokes land in another application (here:
  the user's browser). Guard every keystroke batch with a foreground check and abort the action when it
  fails. The same call dismisses an already open popup, so send a whole interaction — Alt+Enter, Enter,
  Down, Enter — inside one process call, and use a no-focus-grab path for keys typed while a popup is open.

- Place the caret with the keyboard (`^g<line>{ENTER}` then `{RIGHT}` repeated), not with pixel clicks.

- The intentions popup can take more than 1.5 s to populate on first use of a file. Take a burst of
  timed shots (120/400/900/1800/3200 ms) before calling it broken.

- PowerShell 5.1 argument mode binds the comma before the arithmetic:
  `New-Object Type(a - b, c - d)` dies in `op_Subtraction` on `System.Object[]`. Hoist `[int]` locals and
  use `-ArgumentList $w, $h`. The same bug silently corrupted computed click coordinates.

- Git Bash expands `$_` inside a double-quoted `powershell -Command "…"` (it becomes `extglob`). Wrap the
  PowerShell command in single quotes.

## Sandbox lifecycle

- `prepareSandbox_runIdeForUiTests` fails copying `alpn-api-*.jar` ("файл с открытой пользователем
  сопоставленной секцией") while a sandbox IDE is still running. Kill the sandbox first. It may be a
  `java.exe` from the SDK `jbr` with no visible window — find it with
  `Get-CimInstance Win32_Process | Where-Object { $_.Name -like "*java*" }` and check `ExecutablePath`.

- Every `runIde*` task gets its own `config_*`, `system_*`, `log_*` directory under
  `.intellijPlatform/sandbox/NamedArgumentStubsIntention/<IDE>/`. `runIdeForUiTests` reopens the project
  it had last, which is why the sample lives in `build/ui-sample` and the caret position survives a restart.

- `platformVersion` must point at a GA build for which test artifacts exist, or `./gradlew test` dies at
  startup rather than at compile time.

## Ground rules

- A unit test guards a bug only if it fails without the fix. For platform behaviour, run the IDE and look
  at it; screenshots and window dumps beat reasoning about the API.

- Do not touch the developer's work in progress (the `build.gradle.kts` srcDir edits, `manual/demo.kt`)
  and do not commit unless asked.
