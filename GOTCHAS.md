# Gotchas — platform and project specifics

Facts, not rules. Everything here is true of **this** machine and **this** project, and will go stale.
The general working rules live in `AGENTS.md`, the layout in `PROJECT-GUIDE.md`, and the release
workflow in `RELEASE-GUIDE.md`.

Each entry earns its place by costing time. **If you discover one, write it down here yourself — do not
wait to be asked, and do not write it retrospectively.** Include the symptom as observed, the check
that proves it, the cause with `file:line`, and the fix. An entry holding only a conclusion is nearly
worthless, because the next reader cannot tell whether it still applies. Delete the ones that stop
holding; a stale gotcha is worse than none, because it gets trusted.

## Toolchain

### Finding the required JDK, rather than guessing one

The Java version a Minecraft version needs is usually already installed on the machine by the launcher
you use to play, and the launcher records which runtime each instance runs on. Look there before
installing anything or trusting a remembered path.

- **Minecraft launchers keep their own Java installs.** For Prism Launcher / MultiMC and the Modrinth
  App that is an `appdata`/config directory beside the launcher's other data — a `java` folder holding
  one directory per installed runtime — and some builds instead place a `runtime` directory inside each
  instance. The official Mojang launcher ships exactly one runtime, under the install directory. The
  launcher's own *Java installations* / *Settings -> Java* screen lists the same installs and can add
  more.
- **Do not trust the directory name to tell you the version.** Launchers commonly name runtime
  directories by an opaque or obfuscated identifier rather than by version number, so `java-runtime-x`
  may be 17, 21 or 25. Resolve it by running the binary:

  ```bash
  "<path-to-runtime>/bin/java" -version
  ```

  Use `bin/java` rather than `bin/javaw`: `javaw` is the windowless variant the launcher points a game
  at, so the game does not open a console.
- **The instance is the authority.** A launcher records the chosen runtime per instance, in a settings
  or `instance.cfg` file, as both a `JavaPath` and a `JavaVersion`. Reading that tells you exactly what
  a given Minecraft version runs on, with no inference.
- **To obtain a version you do not have,** make an instance for a Minecraft version that requires it —
  the launcher downloads the matching runtime with the instance — then read the path back out of that
  instance's settings.
- Point the build at whatever you find through the environment variable the build tool reads
  (`JAVA_HOME` for Gradle, or the IDE's own runtime setting), not by editing build files.

- Gradle must run on JDK 17–23. If the machine default is newer, point `JAVA_HOME` at a JDK 21.
- Select the Minecraft version with `-Pmc=<version>` (default from `gradle.properties`).
- `JVM settings live in the root gradle.properties only`: `org.gradle.jvmargs` is read from a build's own
  root `gradle.properties`, so nested per-module and per-loader files repeat the same value
  deliberately. If they disagree, Gradle starts a second daemon with the wrong heap. Do not raise the
  daemon heap to fix a worker OOM — each worker is its own JVM, so `org.gradle.workers.max` is the lever,
  and it is kept below the core count.
- Do not pass `--no-daemon` locally. Each invocation cold-starts and reconfigures every included build.
  `tools/release.py` reuses the warm daemon and exposes `--no-daemon` to reproduce a CI-like cold build;
  CI always passes it (fresh container).

## Build & release

- Build the staged release groups, then the union (single-mod project: no bundles, so those groups stay
  empty but keep the uniform contract):

  ```bash
  ./gradlew -Pmc=<mc> releaseLoaderJars
  ./gradlew -Pmc=<mc> releaseLoaderBundles
  ./gradlew -Pmc=<mc> releaseUniversal
  ./gradlew -Pmc=<mc> releaseUniversalBundles releaseJars
  ```

  `build/release/` must then hold the complete set (jars × loaders + universal jars + bundle variants).
- The library module(s) must be published before dependents compile, because dependents resolve a
  version floor from the local repository:

  ```bash
  ./gradlew -Pmc=<mc> publishLibrary
  ```

  CI and `tools/release.py` publish the library as their own step.

## Layout & conventions

- Anything shared belongs in the library module (`module.properties` with `library=true`), never
  duplicated per loader or per gameplay module.
- Per-loader layout: `common` + `fabric` + `neoforge` included builds, with the shared sources relocated
  per loader so one universal jar can hold both mapping variants.
- Versions use the `<major>.<minor>.<patch>.<yymmddhh>` scheme (`tools/versioning.py`); release tags are
  `v<major>.<minor>.<patch>.<stamp>`.
- Version bumps are **declarative**: only the component named by `--mod` is re-versioned, and nothing
  infers it from which files were touched. When a change spans the library and one or more dependents,
  release with `all` so the bundles cannot mix new and old component versions.

## Gotchas

### A rename changes where names are built, not where they are read

- **Symptom:** after renaming release jars, the build is green, every jar is produced, the count is
  right, and the tests pass -- but CurseForge/Modrinth receive nothing, or receive a truncated
  version. No step reports an error. In this project `--curseforge` published **zero** of five
  bundles and logged `done`.

- **Check that proves it:** take the names actually in `build/release/` and make every consumer
  resolve against them. For each of these, a wrong answer is a silent no-op:
  - every `glob()`/shell glob, and what `sorted()[0]` actually selects (3 files match, not 1)
  - every version-extraction `sed`/slice: print the extracted string and compare to the real version
  - every constructed path: assert `(dist / name).exists()` for all of them
  - the workflow's own `allowed=` regex, run against the real names
  A count check cannot catch this: 30 stale jars and 30 current jars are both 30.

- **Cause:** the rename updated the code that *builds* a name and left the code that *reads* one.
  `bundle-<key>-*.jar` matched 3 files (fabric/neoforge/universal) where
  `universal-bundle-<key>.*.jar` had matched 1, so `sorted()[0]` changed meaning silently, and a
  `-universal.jar` suffix slice then ate 3 characters of the version. Every `if jar.exists():`
  turned each wrong path into a quiet skip. Both `dist/` folders held only pre-rename jars, so the
  stale lines looked plausible on inspection.

- **Fix:** one name per artifact built by one helper, and a glob that names the exact file rather
  than a directory plus `[0]`. Replace every `if jar.exists(): continue` on an upload path with
  `die(...)` -- a missing file there is a bug, not a reason to publish a partial set. Then add the
  shape guard: `ALLOWED_JAR` in `tools/release.py` and `allowed=` in the workflow, both
  `^[A-Za-z0-9_-]+-[0-9][0-9.]*-(fabric|neoforge|universal)\.jar$`. Dashes are required in the id
  group for a `bundle-` prefix. Clear `dist/` after a rename so nothing can resolve a stale file.

- **When auditing a rename, grep for the *old* shape, not for a word like `universal`.** A pattern
  of `universal[-_.]` also matches the new correct `...-universal.jar`, so the check reports hits
  that are fine, and reading those as failures (or as "clean") is how a stale line survives review.

Add project- and platform-specific traps here as they are found, under a heading that says what the
symptom looked like. Keep the check that proves it.

### Nether/End borders silently revert to the overworld size on every restart

- **Symptom:** a command or hook sets the Nether (or End) world border to a derived value; it looks
  correct for the session, then after a server restart it is back to the overworld's size, and the
  Nether/End `level.dat` never holds the value that was set.
- **Check that proves it:** disassemble the cached Mojang-mapped jar
  (`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/<mc>-loom.*/`):
  `javap -c -p net.minecraft.world.level.storage.DerivedLevelData` shows `setWorldBorder` is a bare
  `return` and `getWorldBorder` delegates to the wrapped `ServerLevelData`; `javap -c -p
  net.minecraft.server.MinecraftServer` shows every non-overworld dimension (skips
  `LevelStem.OVERWORLD`) is built as `new DerivedLevelData(worldData, <primary ServerLevelData>)`.
- **Cause:** non-overworld dimensions use `DerivedLevelData`, whose border accessors share the
  overworld's `PrimaryLevelData`; `setWorldBorder` is a no-op. So the border written at runtime is
  never saved, and on load `MinecraftServer` re-seeds Nether/End from the overworld's saved settings.
- **Fix:** treat non-overworld borders as re-derivable runtime state - re-apply them on every server
  start and on demand; never rely on vanilla persistence for them. Borderline does this in
  `BorderSync.onServerStarted`.

### A vanilla /worldborder change overwrites the Nether/End borders 1:1

- **Symptom:** after setting a scaled size (e.g. overworld 10000 -> Nether 1250, End 5000), running
  vanilla `/worldborder set 20000` makes **all** dimensions read 20000, not the scaled values - and
  no mod hook runs to correct it.
- **Check that proves it:** `javap -c -p net.minecraft.server.MinecraftServer` in `createLevels`
  shows, for each non-overworld dimension, the overworld border getting a listener:
  `overworldBorder.addListener(new BorderChangeListener$DelegateBorderChangeListener(newLevel.getWorldBorder()))`.
  `BorderChangeListener.DelegateBorderChangeListener.onBorderSizeSet` then calls
  `worldBorder.setSize(size)` (unscaled), `onBorderSizeLerping` -> `lerpSizeBetween`,
  `onBorderCenterSet` -> `setCenter`. Observed at runtime: `set 10000` -> 1250/5000, then vanilla
  `set 20000` -> 20000/20000/20000.
- **Cause:** vanilla links the overworld border to every other dimension 1:1. Nether/End are not
  independent at runtime - any overworld border write is mirrored onto them, unscaled, by the
  delegate listener.
- **Fix:** re-derive (with the scales) after every overworld border change. Borderline attaches its own
  `BorderChangeListener` to the overworld border at server start; because it is registered after
  vanilla's delegate listener, it runs last and overwrites the 1:1 mirror with the scaled values
  immediately. Server start and `/borderline sync` cover the initial and on-demand cases.

### <symptom as the user or a log would describe it>

- **Symptom:**
- **Check that proves it:**
- **Cause:** `file:line`
- **Fix:**
