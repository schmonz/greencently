# Greencently Reshape Plan

## Why

Today greencently plugs into JUnit 5 as a `TestExecutionListener`. That puts
it inside a framework that only sees what an orchestrator hands it. The
consequences leak everywhere:

- `JUnit5Planner.discoverTestClasspath()` guesses the orchestrator from path
  suffixes (`/test/classes`, `/test`).
- `maxParallelForks = 1` is required because a per-JVM listener cannot
  aggregate across forks.
- Multi-module Gradle builds produce N status files in N module dirs with no
  project-wide view.
- "Was a subset requested?" is inferred by counting rather than read from
  `Test.filter` / `--tests` / tag includes.
- The status file is named `junit5`, baking a framework into a contract that
  pre-commit hooks already depend on.

The right shape: an **orchestrator** extension that owns "all the tests for
this project," with thin per-framework adapters that only report
pass/fail/skip per fork. Build that first for Gradle, then mirror it for
Maven, then add a JetBrains IDE plugin (orchestrator = the IDE itself, so it
works for any framework/language the IDE supports), then add JavaScript
(Jest, Vitest), then keep going.

## Cross-cutting principles

1. **The orchestrator owns "all tests."** Never re-derive it from classpath
   scanning inside a framework listener.
2. **"Subset requested?" is read from the orchestrator's filter API**, not
   inferred from counts.
3. **One status file per project, at the project root**, framework-agnostic.
   Path: `.greencently/status`. Mtime = last moment the project was
   complete-and-green. Document this as the public contract.
4. **Per-framework adapters are dumb.** They emit `(framework, fork-id,
   passed, failed, skipped, identifiers-run)` to the orchestrator layer and
   nothing else. No file writing, no discovery, no decisions.
5. **Aggregation lives in the orchestrator plugin.** It collects all
   adapter reports across forks and modules for a single "run all tests"
   invocation, compares to the orchestrator-known full set, and writes the
   status file iff every test ran and every test passed.
6. **Every phase ships with unit tests for the pure pieces** (results
   model, status writer, filter detection) and an end-to-end smoke test
   against an example project.
7. **Never break the existing `.greencently/junit5` file** until a phase
   explicitly migrates users. When migration happens, write both the old
   and new paths for one release.

## Phase 0 — Lock down what we have

Goal: make the current behavior testable and the contract explicit before
changing shape.

- [ ] Add unit tests for `TestResults.areCompleteAndGreen()` covering: all
      green and complete; one red; expected > actual (subset run);
      expected < actual (shouldn't happen, but assert behavior); zero
      tests.
- [ ] Add unit tests for `Greencently.writeStatus()` against a temp
      directory: file appears on green, disappears on red, mtime advances
      on consecutive greens, `.gitignore` is `*`.
- [ ] Fix `TestResults.wantLogging()` `!== null` → `!= null` (referential
      vs structural; works today by accident).
- [ ] Drop the redundant `setLastModifiedTime` after `writeText("")` in
      `Greencently.yes()`; `writeText` already updates mtime.
- [ ] Write `docs/STATUS_FILE.md` documenting the on-disk contract that
      pre-commit hooks already depend on: location, semantics, mtime
      meaning, intended consumers. Mark `.greencently/junit5` as the
      legacy path and `.greencently/status` as the future path.
- [ ] Add a `CHANGELOG.md` and start tracking from this point.

Acceptance: `./gradlew check` runs unit tests; `test.sh` still passes; no
behavioral change for existing users.

## Phase 1 — Extract a framework- and orchestrator-agnostic core

Goal: separate "decide and write" from "JUnit-listen."

- [ ] Create `core/` package with:
  - `RunReport(framework, forkId, passed, failed, skipped,
    identifiersRun)` — per-fork, per-framework report. Pure data.
  - `RunVerdict.from(reports, expectedFullSet, subsetRequested)` —
    pure function returning `CompleteAndGreen | Incomplete | Red`.
  - `StatusFile(rootDir)` — writes/clears `.greencently/status`,
    manages `.gitignore`. No framework knowledge.
- [ ] Re-implement `TestListenerDelegate` on top of `RunReport` +
      `RunVerdict` + `StatusFile`. `JUnit5Listener` becomes a thin
      adapter: collect counts, emit one `RunReport` at
      `testPlanExecutionFinished`.
- [ ] Keep `JUnit5Planner` for now but mark it deprecated; it's only
      used to compute `expectedFullSet` until Phase 2 takes that over.
- [ ] Unit-test `RunVerdict.from()` exhaustively. Unit-test `StatusFile`
      against a temp dir.

Acceptance: same external behavior; `JUnit5Listener` shrinks to ~20
lines; core has no JUnit imports.

## Phase 2 — Gradle plugin (`greencently-gradle`)

Goal: the orchestrator is in charge. Drop the classpath-suffix hack and
the `maxParallelForks = 1` requirement.

- [ ] New module `gradle-plugin/` publishing
      `com.schmonz.greencently` (plugin id) and
      `com.schmonz:greencently-gradle:<version>`.
- [ ] Plugin applies to a project (root or subproject). When applied at
      the root in a multi-project build, it auto-discovers all
      subprojects with `Test` tasks.
- [ ] For each `Test` task in scope:
  - Wire the framework adapter onto the task's classpath
    (`testRuntimeOnly` injection) so per-fork `RunReport`s are emitted
    via an inter-process channel (file-per-fork in
    `build/greencently/forks/`, simplest viable; revisit if needed).
  - Capture the orchestrator-known full set:
    `Test.filter` empty + no `--tests` + no tag includes →
    `subsetRequested = false`. Compute the full identifier set from the
    `Test` task's candidate class files (Gradle already knows them).
  - Capture whether the task was actually executed (vs UP-TO-DATE /
    FROM-CACHE). Treat UP-TO-DATE as "previous green state is still
    valid" — do not clear status; do not refresh mtime.
- [ ] After the full build's test tasks finish, aggregate every fork's
      `RunReport` across every module into one `RunVerdict`. Write
      `.greencently/status` at the **root project** directory iff:
      every in-scope `Test` task ran with no filter, every adapter
      report sums to the orchestrator-known full set, and zero failures.
- [ ] Remove the need for `maxParallelForks = 1`. Verify with an
      example project that sets `maxParallelForks = 4`.
- [ ] Add `example-projects/junit5-gradle-multimodule/` with 2+
      subprojects and use it in `test.sh`.
- [ ] During this phase the legacy `testRuntimeOnly("com.schmonz:greencently:...")`
      path keeps working unchanged and keeps writing
      `.greencently/junit5`. The plugin writes the new
      `.greencently/status`. Both coexist.

Acceptance: example multi-module project with parallel forks ends with
exactly one `.greencently/status` at the root after a clean
`./gradlew test`; running `./gradlew test --tests SomeSubset` does not
create it; one failing test does not create it; `./gradlew test` a
second time (UP-TO-DATE) does not clear it.

## Phase 3 — Migration and deprecation of the framework-listener path

- [ ] README update: "Setup for Gradle" uses the plugin; the old
      `testRuntimeOnly` path moves to a "Legacy setup" appendix with a
      deprecation notice and migration steps.
- [ ] Pre-commit-hook example in README switches to
      `.greencently/status`.
- [ ] One release where the framework listener writes **both**
      `.greencently/junit5` and `.greencently/status` for users who
      haven't migrated.
- [ ] Following release: framework listener is gone from the published
      artifact; `JUnit5Planner` and `JUnit5Listener` are deleted.

Acceptance: a user with the old hook and the old `testRuntimeOnly` line
sees no breakage during the dual-write release; a user who removes the
`testRuntimeOnly` line and applies the plugin gets identical behavior.

## Phase 4 — Maven plugin (`greencently-maven`)

Mirror of the Gradle plugin against Maven's lifecycle.

- [ ] New module `maven-plugin/` producing a Maven plugin with one
      mojo bound to `verify`.
- [ ] Discover all `surefire`/`failsafe` executions configured on the
      reactor. Treat the multi-module reactor the way the Gradle plugin
      treats a multi-project build.
- [ ] Read "subset requested?" from Surefire/Failsafe properties:
      `-Dtest=`, `<includes>`/`<excludes>` deviating from defaults,
      `-Dgroups=` (JUnit 5 tags / TestNG groups).
- [ ] Reuse the same `core/` `RunVerdict` + `StatusFile`. Reuse the
      same JUnit 5 adapter (it's framework-side, not
      orchestrator-side).
- [ ] Write `.greencently/status` at the reactor root.
- [ ] Add `example-projects/junit5-maven-multimodule/` and extend
      `test.sh` (or split into `test-gradle.sh` / `test-maven.sh`).

Acceptance: equivalent guarantees to the Gradle plugin on a
multi-module Maven reactor.

## Phase 5 — JetBrains IDE plugin (`greencently-intellij`)

Goal: the IDE is the orchestrator. This unlocks every test framework and
every language the IDE supports, because the IDE already knows "all the
tests in this project."

- [ ] New module `intellij-plugin/` using the IntelliJ Platform Plugin
      SDK. Targets IntelliJ IDEA Community, Ultimate, and the JetBrains
      IDE family (Rider, PyCharm, WebStorm, GoLand, RubyMine, …) where
      applicable.
- [ ] Hook the platform's test runner notifications
      (`SMTRunnerEventsListener` / `TestStatusListener`) to collect
      per-run pass/fail/skip.
- [ ] Determine "full set" from the IDE's test scope: when the user
      runs a configuration whose scope is "all tests in project /
      module," accept it as a candidate-for-status-write; otherwise
      treat as subset.
- [ ] Detect subset directly from the run configuration (class
      pattern, method, tag, package, directory) — do not infer.
- [ ] Write `.greencently/status` at the project root (IDE project
      root, which matches the VCS root in the common case).
- [ ] Settings panel: enable/disable per project; show last green
      timestamp; show why the last run did/didn't qualify.
- [ ] Test against JUnit 5, JUnit 4, TestNG, Spock (JVM); pytest
      (PyCharm); Jest/Vitest (WebStorm); RSpec (RubyMine); Go test
      (GoLand). Each is just verifying the IDE's status events feed in
      correctly — no per-framework adapter code needed.

Acceptance: in IntelliJ, running the "All Tests" configuration on a
project ends with `.greencently/status` written iff every test passed;
running a single test class does not create it; failing tests delete
it; works across at least JUnit 5, pytest, and Jest installations of
the plugin.

## Phase 6 — JavaScript: Jest and Vitest

Goal: same shape, JavaScript orchestrator = the test runner itself,
since in the JS world Jest/Vitest *are* the orchestrator (no Gradle
equivalent in front of them).

- [ ] `@greencently/jest` package: a Jest reporter
      (`reporters: ['default', '@greencently/jest']`). Reporter sees
      the full test plan in `onRunStart` (Jest tells you), the per-
      file results, and whether `testPathPattern` / `--testNamePattern`
      / `-t` / `--findRelatedTests` were used (subset). Write
      `.greencently/status` at `process.cwd()` (or the configured
      `rootDir`) iff full set ran and all passed.
- [ ] `@greencently/vitest` package: a Vitest reporter implementing the
      `Reporter` interface. Same shape; read CLI args / config for
      subset detection.
- [ ] Both packages share a tiny `@greencently/core-js` with the same
      `RunVerdict` and `StatusFile` semantics as the JVM core, ported
      to TypeScript.
- [ ] `example-projects/jest/` and `example-projects/vitest/`; add to
      `test.sh` (or a `test-js.sh`).

Acceptance: in a Jest project, `npx jest` ends with
`.greencently/status` written; `npx jest some.test.js` does not; one
failing test deletes it. Same for Vitest.

## Phase 7 — A documented contract for new languages/frameworks

Goal: make adding the next ecosystem mechanical.

- [ ] Write `docs/INTEGRATION.md` defining what an integration must do:
  1. Identify the **orchestrator** (build tool, test runner, or IDE).
     If a framework is its own orchestrator (Jest, Vitest, pytest run
     directly), it plays both roles.
  2. From the orchestrator, obtain (a) the full candidate test set and
     (b) whether a subset was requested. Both must come from the
     orchestrator's own APIs — never inferred by counting.
  3. From per-fork / per-worker adapters, collect pass/fail/skip and
     identifiers actually executed.
  4. Aggregate, evaluate `RunVerdict`, write `.greencently/status`.
- [ ] Define the on-disk fork-report wire format (so a JVM adapter and
      a non-JVM orchestrator could in principle interoperate; this
      keeps the door open for e.g. running Kotest from a non-Gradle
      orchestrator).
- [ ] Candidate next integrations to validate the contract:
  - pytest (orchestrator = pytest itself; plugin via
    `pytest_collection_modifyitems` + `pytest_sessionfinish`).
  - Go `go test` (orchestrator = `go test`; integrate via a wrapper or
    `-json` output post-processor since `go test` has no plugin API).
  - Cargo test (Rust; similar wrapper pattern).
  - RSpec, Minitest (Ruby).
  - .NET `dotnet test` (orchestrator = `dotnet test`; plug in via a
    test logger).
  - Bazel (orchestrator = Bazel; integrate via a test result reporter
    over the Build Event Protocol).

Acceptance: each new ecosystem can be added without changing core, and
pre-commit hooks for any project (regardless of stack) check the same
`.greencently/status` file with the same semantics.

## Operating notes for whoever picks this up

- Do phases in order. Phase 0 buys safety. Phase 1 makes Phase 2
  possible without rewriting Phase 2 twice.
- Each phase ends with: green CI, updated `CHANGELOG.md`, README
  reflecting the current supported integrations, and at least one
  example project under `example-projects/` exercised by `test.sh`.
- Resist building extension points before there are two
  implementations to generalize from. The `core/` extraction in Phase
  1 is enough; don't add a plugin SPI until Phase 4 or 5 forces it.
- Keep the status file's contract sacred. It's the only thing users
  outside this repo write code against.
