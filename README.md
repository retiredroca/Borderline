# Borderline

A Minecraft mod for multiple Minecraft versions on **Fabric** and **NeoForge**, built from the
multi-version template.

Borderline reads the **overworld world border** and derives the **Nether** and **End** borders from
it, so a single world size applies across all dimensions. It runs on the server (singleplayer uses
the integrated server) and needs no client mod. Supports Minecraft **1.21.1** and **26.3** on Fabric
and NeoForge.

## What it does

The overworld border is the source of truth. The Nether and End sizes are the overworld size
multiplied by a configurable scale (Nether `0.125`, End `0.5` by default). Borderline re-derives them
with the scales on every server start, whenever the overworld border changes, and on demand with a
command.

Vanilla does not keep a per-dimension client border, so Borderline also pushes each player the border
of the dimension they are in, using vanilla border packets. Unmodded clients therefore render the
correct border in every dimension.

The last world size set through the mod is saved in `config/borderline.json` and re-imposed on the
overworld at every start, so the configured size survives restarts even if the world data is reset.

### Config (`config/borderline.json`)

```json
{
  "enabled": true,
  "skipWhenUnbounded": true,
  "worldSize": null,
  "nether": { "enabled": true, "sizeScale": 0.125, "centerScale": 1.0 },
  "end": { "enabled": true, "sizeScale": 0.5, "centerScale": 1.0 }
}
```

- `enabled` - master switch.
- `skipWhenUnbounded` - when no `worldSize` is saved, leave Nether/End alone if the overworld border
  is still at the vanilla default (unbounded).
- `worldSize` - the last overworld size (diameter) set through the mod, or `null`.
- `nether` / `end` - per-dimension `enabled`, `sizeScale` (border size multiplier) and `centerScale`
  (center multiplier applied in overworld coordinates; vanilla then applies the dimension's own
  coordinate scale, so `1.0` matches the overworld center everywhere).

### Commands (op level 2)

- `/borderline set <diameter> [seconds]` - set the overworld world size (optionally lerping over
  `seconds`) and derive the other dimensions; the value is saved to the config.
- `/borderline sync` - re-derive from the live overworld border and save its size.
- `/borderline status` - print the current size and center of each dimension.

> **Vanilla note.** A plain `/worldborder` command still edits the overworld border. Borderline
> listens for that change and immediately re-derives the Nether/End borders with the configured
> scales, and its per-player packet push keeps each client showing its own dimension's border.

## Build

```bash
./gradlew build              # default Minecraft version (gradle.properties -> mc = 1.21.1)
./gradlew -Pmc=1.21.1 build  # 1.21.1
./gradlew -Pmc=26.3 build    # 26.3
```

> The wrapper is **Gradle 9.5.1**. Run it with a **JDK 21** `JAVA_HOME`; the build selects its own
> toolchain per version (Java 21 for 1.21.1, Java 25 for 26.3), so a **JDK 25** installation must be
> detectable by Gradle. If it is not, add it through `org.gradle.java.installations.paths`.

## Adding a Minecraft version

```bash
./gradlew newVersion -Pnew=<mc>          # copies versions/1.21.1 and resolves its pins
./gradlew -Pmc=<mc> build
./gradlew portChangedFiles -Pfrom=1.21.1 -Pto=<mc>
```

## Releases

Versions use `<major>.<minor>.<patch>.<yymmddhh>`, with the stamp taken in the timezone chosen when
the project was created (`TIMEZONE` in `tools/versioning.py`). Releasing is two local phases and a
GitHub tag workflow:

```bash
python tools/release.py --mod all --dry-run              # preview 1.21.1 and 26.3
python tools/release.py --mod all                       # stamp/build both; no commit
# commit and push the prepared files yourself
python tools/release.py --create-tags                   # create local tags; push them yourself
```

Without `--mc` both versions are prepared and tagged; `--mc 26.3` selects one. The tag push triggers
`.github/workflows/release-tag.yml`, which builds the jars in CI, creates the GitHub release with
every jar and sources jar, and publishes it. The release event then runs the platform workflow, which
uploads only universal jars to CurseForge/Modrinth. The local script never commits or pushes; the CI
never commits; commit the prepared files and the downloaded `release-state.properties` artifact
yourself.

The staged build groups (`releaseLoaderJars`, `releaseLoaderBundles`, `releaseUniversal`,
`releaseUniversalBundles`) each write their own directory; `releaseJars` unions them into
`build/release/<mc>/`. Release workflows live in `.github/workflows/`; see `PROJECT-GUIDE.md` →
**Enabling CI publishing** for the variables and secrets.

See `PROJECT-GUIDE.md` for building, adding Minecraft versions and the build layout,
`RELEASE-GUIDE.md` for the release workflow, and `AGENTS.md` for repository conventions.

## Layout

```
versions/<mc>/version.properties        # Minecraft / loader / toolchain pins (canonical)
versions/<mc>/<module>/
├─ module.properties                    # id / name / group / authors / license / description
├─ common/                              # shared sources (no loader imports)
├─ fabric/                              # Loom build; compiles common into <group>.fabric.common
└─ neoforge/                            # NeoForge build (NeoGradle on 1.21.1, ModDevGradle on 26.3)
tools/{versioning,release}.py           # version scheme + local release driver
```

The two loader builds are separate (Loom and the NeoForge plugin cannot share one Gradle project). The
shared sources are compiled once per loader and merged into `<module>-<version>-universal-<mc>.jar`,
which works on both loaders.

See the template's top-level README for the full workflow.
