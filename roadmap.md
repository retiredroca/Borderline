# Roadmap — custom dimension borders

Status: **tabled** (2026-10-26). No implementation is in progress. The previous attempt was discarded:
its branch and worktree were deleted, and `main` contains none of that code. This file records what
was verified, what still has to be decided, and the order in which the work resumes.

## Goal

Let Borderline opt in to custom dimensions, in addition to the Nether and End:

- a `custom` map in `borderline.json`, keyed by full dimension id, with no automatic entries;
- a border size relative to the overworld (`sizeScale`);
- a relative center (`centerScale`, measured in overworld coordinates) or an absolute center in the
  dimension's own coordinates (`centerX`/`centerZ`);
- commands to list, import, tune, enable, disable and remove those dimensions;
- resetting a disabled or removed dimension's size to unbounded, leaving its center unchanged.

## Verified findings

### Command argument serialization

A custom Brigadier `ArgumentType` cannot be used here. The command tree is serialized to every
joining client, and `ArgumentTypeInfos.byClass` throws

```
IllegalArgumentException: Unrecognized argument type %s (%s)
```

for a class it does not know; `ClientboundCommandsPacket` calls `ArgumentTypeInfos.unpack`. That was
the defect in the previous attempt: `BorderlineCommand$DimensionIdArgument` was unregistered. It
surfaced as a join failure on 1.21.1 and as missing commands on 26.3. Registering a custom type is not
a fix either: Borderline is server-side only, so an unmodded client could never deserialize it.

The correct argument types are vanilla and already registered:

| Version | Type | Id extraction |
|---|---|---|
| 1.21.1 | `ResourceLocationArgument.id()` | `ResourceLocationArgument.getId(ctx, "dimension").toString()` |
| 26.3 | `IdentifierArgument.id()` | `IdentifierArgument.getId(ctx, "dimension").toString()` |

Both parse a namespaced id, including the `:`; `ResourceLocation.read` and `Identifier.read` are the
parsers, and both types appear in `ArgumentTypeInfos.bootstrap`.

The 26.3 command permission gate is not part of this problem. `Commands.LEVEL_GAMEMASTERS` is
`Require(Permissions.COMMANDS_GAMEMASTER)`, `Permissions.COMMANDS_GAMEMASTER` is
`HasCommandLevel(GAMEMASTERS)`, and `PermissionLevel.byId(2)` is `GAMEMASTERS`; the existing gate is
equivalent to vanilla.

### The 26.3 center model differs from 1.21.1

This was missing from the previous plan and must be fixed before the custom feature is ported.

- **1.21.1:** `Level` creates a per-level `WorldBorder`, wrapped in `Level$1` when
  `DimensionType.coordinateScale() != 1`. `Level$1` overrides only `getCenterX`/`getCenterZ`, which
  divide the stored center by the coordinate scale. `setCenter` stores the value unchanged.
- **26.3:** there is no `Level$1`. `ServerLevel.getWorldBorder()` returns a per-level `WorldBorder`
  from the level's own `SavedDataStorage`, and `WorldBorder.getCenterX`/`getCenterZ` return the stored
  center directly. `ClientLevel` also holds a plain `WorldBorder`; the client applies the packet
  center unchanged.

Consequences:

| Case | 1.21.1 | 26.3 |
|---|---|---|
| Relative center (`centerScale`) | store `overworldCenter * centerScale` | store `overworldCenter * centerScale / coordinateScale` |
| Absolute center (`centerX/centerZ`) | store `localCenter * coordinateScale` | store `localCenter` |
| Client wire center | `border.getCenterX() * coordinateScale`, temporary border when scale != 1 | `border.getCenterX()`, no scaling or temporary border |

The existing 26.3 `BorderSync` uses the 1.21.1 formulas, so the 26.3 Nether, End and any custom
dimension with `coordinateScale != 1` currently receive the wrong center. This is a separate,
confirmed defect from the custom-dimension feature.

### Persistence differs

- **26.3:** `WorldBorder` extends `SavedData`; each level's `ServerChunkCache` owns a
  `SavedDataStorage`, so per-dimension borders persist.
- **1.21.1:** non-overworld `DerivedLevelData.setWorldBorder` is a no-op and `getWorldBorder` returns
  the overworld settings; per-level border objects are recreated from those settings on start.
  Per-dimension border writes are not persisted.

This affects the durability of the reset on `disable`/`remove`: it persists on 26.3 and is session-only
on 1.21.1 unless startup behavior is changed. The decision is listed below.

## Design to confirm

- `custom` map in `borderline.json`; keys are full dimension ids; values use the same
  `enabled`/`sizeScale`/`centerScale` fields as `nether`/`end`, plus optional `centerX`/`centerZ`.
- `sizeScale` is always the border size compared with the overworld.
- `centerScale` is a multiplier on the overworld center, in overworld coordinates; when
  `centerX` and `centerZ` are both set they replace the relative center and are read as local
  coordinates.
- Commands: `dimensions`, `import <id> [auto|same|custom <sizeScale> [centerScale]]`,
  `import all`, `scale`, `center <id> <x> <z>`, `center <id> relative`, `enable`, `disable`, `remove`.
- `import auto` uses `1 / coordinateScale`; `same` uses `1.0`.
- `disable` and `remove` set the size to `WorldBorder.MAX_SIZE` and leave the center unchanged.
- `remove` applies only to custom dimensions; Nether and End can only be disabled.

## Plan when resumed

### Stage 0 — branch and version

- Create a new worktree/branch from `main`.
- Give the branch its own version line with the documented command:
  `python tools/release.py --mod all --bump patch --commit`.
  It writes and commits `versions.properties` and performs no build, tag or push.
- Do not use `-PallowUnbumped=true` for runtime testing; it produces release-named jars.

### Stage 1 — 26.3 center correction (separate change)

- Fix the 26.3 relative/absolute center formulas and the client wire center as above.
- Correct the class comment that claims 26.3 does not persist per-dimension borders.
- Build 26.3 and verify with the user:
  - `/worldborder center 1000 2000`; the Nether center must be `125 250`, not `1000 2000`.
  - The client's Nether center matches.
- **Stop gate:** do not continue if this fails.

### Stage 2 — 1.21.1 feature

- Extend `BorderlineConfig` with `custom` and nullable `centerX`/`centerZ`; normalize old configs.
- Extend `BorderSync` to derive enabled custom dimensions; use the 1.21.1 center and client rules.
- Add the commands with `ResourceLocationArgument.id()` only.
- Static gate before building:
  - no source implements or instantiates a custom `ArgumentType`;
  - the chosen argument type is in `ArgumentTypeInfos.bootstrap`;
  - no `ResourceKey.toString()` is used as a dimension id.
- Build and have the user verify the join and command tree first, then the command behavior.
- **Stop gate:** do not port to 26.3 until the 1.21.1 join and commands pass.

### Stage 3 — 26.3 feature

- Same config.
- Extend `BorderSync` using the corrected 26.3 center formulas.
- Add the commands with `IdentifierArgument.id()` only.
- Use `ResourceKey.identifier()` for loaded level ids; keep the tick/game-time lerp and
  `player.level()`.
- Build and verify the join and commands, including a dimension with `coordinateScale != 1`.
- **Stop gate:** do not document or commit as complete until this passes.

### Stage 4 — docs

- Update `README.md` and `description.md` for the config and commands.
- Add the confirmed traps to `GOTCHAS.md`: command-tree registration, the 26.3
  `identifier()`/`location()` split, the 26.3 center model, and 1.21.1 reset durability.

## Verification matrix

- 1.21.1 Fabric and NeoForge.
- 26.3 Fabric and NeoForge.
- Join first, with no command executed; the command tree must be present.
- Then `dimensions`, `import auto/same/custom`, `scale`, absolute center, relative center,
  `disable` reset, `enable`, `remove`, restart.
- Include a custom dimension with `coordinateScale == 1` and one with
  `coordinateScale != 1`.
- Load an existing pre-feature `borderline.json` unchanged.
- The agent stops after jars; the user launches and tests Minecraft.

## Open decisions

1. Is the design above unchanged?
2. Approve the feature-branch patch bump (`release.py --bump patch --commit`)?
3. On 1.21.1, is a session-only reset on `disable`/`remove` acceptable, or should disabled managed
   dimensions be reset to unbounded again at startup? A durable reset for removed dimensions would
   require tracking removed ids in the config.
4. New worktree/branch, or a branch in the main checkout?

## Files expected to change

- `versions/1.21.1/borderline/common/src/main/java/com/retiredroca/borderline/BorderlineConfig.java`
- `versions/1.21.1/borderline/common/src/main/java/com/retiredroca/borderline/BorderSync.java`
- `versions/1.21.1/borderline/common/src/main/java/com/retiredroca/borderline/command/BorderlineCommand.java`
- the same three files under `versions/26.3/`
- `README.md`, `description.md`, `GOTCHAS.md`

The 26.3 center correction touches only `BorderSync.java` in the 26.3 tree and is done before the
custom-dimension code.
