# Borderline

A Minecraft mod for multiple Minecraft versions on **Fabric** and **NeoForge**, built from the
multi-version template.

Borderline reads the **overworld world border** and derives the **Nether** and **End** borders from
it, so a single world size applies across all dimensions. It runs on the server (singleplayer uses
the integrated server) and needs no client mod.

## What it does

The overworld border is the source of truth. The Nether and End sizes are the overworld size
multiplied by a configurable scale (Nether `0.125`, End `0.5` by default; End center stays 1:1).
Vanilla mirrors the overworld border to the other dimensions 1:1; Borderline re-derives them with the
scales on every server start, whenever the overworld border changes, and on demand with a command.

The last world size set through the mod is saved in `config/borderline.json` and re-imposed on the
overworld at every start, so the configured size survives restarts even if the world data is reset.

### Config (`config/borderline.json`)

```json
{
  "enabled": true,
  "skipWhenUnbounded": true,
  "worldSize": null,
  "nether": { "enabled": true, "sizeScale": 0.125, "centerScale": 0.125 },
  "end": { "enabled": true, "sizeScale": 0.5, "centerScale": 1.0 }
}
```

- `enabled` - master switch.
- `skipWhenUnbounded` - when no `worldSize` is saved, leave Nether/End alone if the overworld border
  is still at the vanilla default (unbounded).
- `worldSize` - the last overworld size (diameter) set through the mod, or `null`.
- `nether` / `end` - per-dimension `enabled`, `sizeScale` (border size multiplier) and `centerScale`
  (border center multiplier).

### Commands (op level 2)

- `/borderline set <diameter> [seconds]` - set the overworld world size (optionally lerping over
  `seconds`) and derive the other dimensions; the value is saved to the config.
- `/borderline sync` - re-derive from the live overworld border and save its size.
- `/borderline status` - print the current size and center of each dimension.

> **Vanilla note.** Vanilla links the overworld border to every other dimension 1:1 (via a border
> change listener). Borderline attaches its own listener on top, so any overworld border change -
> including a plain `/worldborder set` - immediately re-derives the Nether/End borders with the
> configured scales.

## Build

```bash
./gradlew build              # default Minecraft version (gradle.properties -> mc)
./gradlew -Pmc=1.21.1 build  # a specific Minecraft version
```

> Gradle 8.14 must run on JDK 17–23 (the example targets Java 21). If your default `java` is newer,
> point `JAVA_HOME` at a JDK 21 or set `org.gradle.java.home` in `gradle.properties`.

## Adding a Minecraft version

```bash
./gradlew newVersion -Pnew=<mc>          # copies versions/1.21.1 and resolves its pins
./gradlew -Pmc=<mc> build
./gradlew portChangedFiles -Pfrom=1.21.1 -Pto=<mc>
```

## Releases

Versions use `<major>.<minor>.<patch>.<yymmddhh>`, with the stamp taken in the timezone chosen when
the project was created (`TIMEZONE` in `tools/versioning.py`). One command drives a release:

```bash
python tools/release.py --mod all --dry-run   # preview
python tools/release.py --mod all             # build + GitHub release, CI publishes
```

The staged build groups (`releaseLoaderJars`, `releaseLoaderBundles`, `releaseUniversal`,
`releaseUniversalBundles`) each write their own directory; `releaseJars` unions them into
`build/release/`. Release workflows live in `.github/workflows/`; they create the GitHub release and
publish to CurseForge/Modrinth when the repository is configured for it (see `PROJECT-GUIDE.md` →
**Enabling CI publishing**).

See `PROJECT-GUIDE.md` for building, adding Minecraft versions and the build layout,
`RELEASE-GUIDE.md` for the release workflow, and `AGENTS.md` for repository conventions.

## Layout

```
versions/<mc>/version.properties        # Minecraft / loader / toolchain pins (canonical)
versions/<mc>/<module>/
├─ module.properties                    # id / name / group / authors / license / description
├─ common/                              # shared sources (no loader imports)
├─ fabric/                              # Loom build; compiles common into <group>.fabric.common
└─ neoforge/                            # NeoGradle build; compiles common into <group>.neoforge.common
tools/{versioning,release}.py           # version scheme + local release driver
```

The two loader builds are separate (Loom and NeoGradle cannot share one Gradle project). The shared
sources are compiled once per loader and merged into `<module>-<version>-universal.jar`, which works
on both loaders.

See the template's top-level README for the full workflow.
