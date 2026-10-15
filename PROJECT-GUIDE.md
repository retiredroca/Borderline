# Project guide

Reference for building, extending and releasing a project made from the multi-version template.
`RELEASE-GUIDE.md` covers the release flow; this file also covers everyday use, adding Minecraft
versions, and adding modules.

> **single vs multi.** A `single` project has one gameplay module and no library, so there is no
> `publishLibrary` dependency to worry about and no bundles. A `multi` project has a shared library
> plus gameplay modules, and can define bundles. Wherever this guide says "module" it applies to
> both; the library/bundle steps only apply to `multi`.

## Prerequisites

- **JDK** for Gradle. Gradle 8.14 must *run* on JDK 17–23; the project *compiles* with the
  toolchain from `versions/<mc>/version.properties` (`java_version`). If your default `java` is
  newer than 23, point `JAVA_HOME` at a JDK 21 (or set `org.gradle.java.home` in
  `gradle.properties` for the IDE only — CI strips that line).
- **Python 3** (for `tools/`). No third-party packages are needed. An IANA timezone for the version
  stamp additionally needs `tzdata`, or a JDK on `JAVA_HOME`/`PATH` (the tool falls back to
  `java.time`).
- **Git**, and `gh` only if you want CI-side publishing.

## Layout

```
versions.properties                       # per-module versions + abbrev (project jar-name prefix)
bundles.properties                        # (multi) bundle.<name>=<module ids>
versions/<mc>/version.properties          # Minecraft / loader / toolchain pins (canonical)
versions/<mc>/<module>/module.properties  # module identity (id / name / group / library)
versions/<mc>/<module>/common/            # loader-agnostic sources (no loader imports)
versions/<mc>/<module>/fabric/            # Fabric Loom build (self-contained; relocates common into <group>.fabric.common)
versions/<mc>/<module>/neoforge/          # NeoGradle build (self-contained; relocates common into <group>.neoforge.common)
gradle/versions.gradle                    # versions/properties + module identity
repo/                                     # in-repo maven for library modules (committed)
tools/versioning.py                       # stamp / bump / tag / floor
tools/release.py                          # local release driver
dist/                                     # release artifacts + changelog (gitignored, .gitkeep tracked)
AGENTS.md                                 # repository conventions for agents/contributors
```

The two loader builds are separate projects (Loom and NeoGradle cannot share one Gradle project).
Each compiles the shared `common/` sources into its own relocated package, and the build merges both
into one **universal** jar that works on either loader.

## Day-to-day

```bash
./gradlew build                  # builds the default Minecraft version (gradle.properties -> mc)
./gradlew -Pmc=1.21.1 build      # builds a specific Minecraft version
./gradlew tasks --group version  # newVersion / updateVersionProps / portChangedFiles
```

The IDE shows one Minecraft version at a time. Change `mc` in `gradle.properties` (or set `-Pmc=`
in your run configuration) and **Reload Gradle**; keep two IDE windows open to port between
versions.

### Build settings and speed

`org.gradle.jvmargs`, `org.gradle.parallel` and `org.gradle.caching` are read from a build's **own
root** `gradle.properties`. For the composite build that is this file at the project root; a nested
`gradle.properties` (e.g. `versions/<mc>/<module>/neoforge/`) is ignored when the build runs as part
of the composite, and only applies if you build that directory directly with `-p`. So keep the JVM
settings in the root file — and if you do set them per module, keep the values identical, or Gradle
starts a second daemon with the smaller heap.

The defaults are `-Xmx4G`, parallel on, caching on. For a large multi-module project raise the heap
(6–12G) and/or set `org.gradle.workers.max` (each worker is its own JVM, so keep it below your core
count rather than growing the daemon heap).

### Build the release groups by hand

The release build is four ordered groups, each writing its own directory; `releaseJars` unions them
into `build/release/`:

```bash
./gradlew -Pmc=<mc> releaseLoaderJars        # build/release-loader/          (per-loader jars)
./gradlew -Pmc=<mc> releaseLoaderBundles     # build/release-loader-bundles/  (per-loader bundles, multi)
./gradlew -Pmc=<mc> releaseUniversal         # build/release-universal/       (merged universal jars)
./gradlew -Pmc=<mc> releaseUniversalBundles  # build/release-universal-bundles/ (universal bundles, multi)
./gradlew -Pmc=<mc> releaseJars              # union of all four -> build/release/
```

Groups 1 and 2 accept `-Ploader=fabric|neoforge` to build one loader only. `cleanLoader` and
`cleanUniversal` wipe a pair of group directories so a partial rebuild cannot leave a stale jar.

### Library modules (multi)

Modules with `library=true` are published to the in-repo maven (`repo/`), and dependent modules
resolve them as a floor range `[<major.minor.patch>,<upper>)` computed from `versions.properties`
at configuration time. Because that resolution happens while Gradle configures, **the library must
be in `repo/` before its dependents compile**:

```bash
./gradlew -Pmc=<mc> publishLibrary     # all library modules, both loaders
```

If `publishLibrary` itself cannot configure (a brand-new Minecraft version), publish a library
directly, then build:

```bash
./gradlew -Pmc=<mc> -p versions/<mc>/<library>/fabric publishMavenJavaPublicationToRepoRepository
./gradlew -Pmc=<mc> -p versions/<mc>/<library>/neoforge publishMavenJavaPublicationToRepoRepository
./gradlew -Pmc=<mc> build
```

`tools/release.py` and CI do this as their own step, so an ordinary release never hits the problem.

## Adding a Minecraft version

```bash
./gradlew newVersion -Pnew=1.21.11
```

This copies `versions/1.21.1` to the new directory and resolves Fabric Loader / Fabric API /
NeoForge for it, writing `versions/<mc>/version.properties` (the canonical pins). Re-resolve later
with `./gradlew updateVersionProps -Pnew=<mc>`.

Then publish each library for the new version and build:

```bash
./gradlew -Pmc=1.21.11 publishLibrary
./gradlew -Pmc=1.21.11 build
```

Port your changes across versions with:

```bash
./gradlew portChangedFiles -Pfrom=1.21.1 -Pto=1.21.11 [-Psince=<ref>]
```

…then fix the API differences. A new Minecraft version is only *declared* here until it is built;
nothing else needs to know about it.

> **Toolchain generations.** 1.21.x and 26.x are *not* the same generation: 26.1+ needs Java 25, is
> deobfuscated, and uses a renamed Loom plugin. `version.properties` carries a `toolchain_era` used
> by the version resolver; a cross-generation port needs the source changes too, not just the pins.

## Renaming or removing the module

A single-module project has exactly one module, under `versions/<mc>/<id>/` (the id you passed to
`create-project`). If you generated it with a placeholder id, or you are repurposing the project,
rename it or remove it here.

A module id is its identity everywhere — its `module.properties` `id`, the `versions.properties` key,
the release jar name, and the maven artifactId — so every place the id appears moves together.
Modules are discovered by scanning `versions/<mc>/`, so nothing outside these edits needs changing.

### Rename it

1. Rename the directory: `git mv versions/<mc>/<old> versions/<mc>/<new>`.
2. Set `id=<new>` in `versions/<mc>/<new>/module.properties`.
3. Rename the key in `versions.properties`.
4. Rename the Java package directory and its declarations — the module subpackage is the module id —
   and update the entrypoint package in `fabric.mod.json` and `neoforge.mods.toml`.

### Remove it

`git rm -r versions/<mc>/<id>` and remove its `versions.properties` key. A single-module project with
no module has nothing to build, so this is only the starting point for adding a new one — for that,
follow the multi template's module layout (a module directory with `common/`, `fabric/`, `neoforge/`
and a `module.properties`).

## Release

See **`RELEASE-GUIDE.md`**. Short version:

```bash
python tools/release.py --mod <id> --dry-run   # preview, changes nothing
python tools/release.py --mod <id>             # build + GitHub release; CI publishes
```

## Enabling CI publishing

The release workflows publish to GitHub (always), then CurseForge and Modrinth (only when
configured). Everything is inert until the repository has the values below, so a project with none of
them still gets GitHub releases.

### Required secrets

| Secret | Used for |
|---|---|
| `GITHUB_TOKEN` | provided automatically; GitHub release + commit-back |
| `CURSEFORGE_API_KEY` | the CurseForge entry |
| `MODRINTH_TOKEN` | Modrinth uploads (only when `PUBLISH_MODRINTH` is `true`) |

The Modrinth token needs the `VERSION_CREATE` and `VERSION_WRITE` scopes (`VERSION_DELETE` is never
used).

### Repository variables

| Variable | Default | Purpose |
|---|---|---|
| `CURSEFORGE_ID` | unset (no CurseForge publishing) | numeric CurseForge project id |
| `PUBLISH_MODRINTH` | unset (off) | set to `true` to publish to Modrinth |
| `MODRINTH_ID` | unset (no Modrinth publishing) | Modrinth project id or slug |

The platform ids are repository variables, not generator arguments: a new project publishes as soon
as `CURSEFORGE_ID` / `MODRINTH_ID` are set, with no code change. With `CURSEFORGE_ID` unset the
CurseForge step states that it is skipping rather than failing quietly.

### Placeholders substituted by `create-project`

| Placeholder | Meaning |
|---|---|
| `fabric-api(required){modrinth:P7dR8mSH}{curseforge:306612}` | `mc-publish` dependencies block, e.g. `fabric-api(required){modrinth:P7dR8mSH}{curseforge:306612}` |

### Layout

- **CurseForge** — one entry per release, carrying the universal jar.
- **Modrinth** — one project; each release is a new version and the previous one is archived (hence
  the version id in `release-state.properties`).
- **Pruning** — the CurseForge API can only *add* files. Archive superseded files on the website
  (Files → … → Archive) after a release; CI cannot do it.

### `release-state.properties`

Committed state written back by CI: `mr.<component>.version.<mc>` (the Modrinth version id, so the
previous version can be archived). Do not delete it; leaving the value empty is fine.

### Triggers

- **Actions → Release → Run workflow**, choosing the Minecraft version.
- **Comment `/release-all`** on an issue or pull request.
- `release: published` re-publishes an existing release (the `publish-release.yml` fallback).

A changed version is committed back to `main` by CI (`Release <tag>: update release state`), so do
not push to `main` while a release is running.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Could not find ... :api-fabric-<mc>:[1.0.0,1.1)` | The library is not published yet. Run `./gradlew -Pmc=<mc> publishLibrary`, or the two direct `publishMavenJavaPublicationToRepoRepository` invocations. |
| Gradle fails with `Unsupported class file major version 69` | Gradle is running on too-new a JDK. Point `JAVA_HOME` at a JDK 17–23. |
| `cannot resolve timezone` from `tools/versioning.py` | The configured `TIMEZONE` is an IANA name and no tzdata/JDK is available. Install `tzdata` (`pip install tzdata`) or use a fixed offset such as `-10:00`. |
| Release says "release jar set mismatch" | A group build failed or a stale jar is present. Run the four groups in order, or `cleanLoader` + `cleanUniversal`, then retry. |
| `git add` / release aborts on a dirty tree | Commit or stash first, or pass `--allow-dirty`. |
| Windows: `gradlew` not found from `release.py` | `tools/release.py` invokes `cmd /c gradlew.bat` on Windows; do not change it to `bash gradlew`, which resolves to Git's bash and mangles paths. |
