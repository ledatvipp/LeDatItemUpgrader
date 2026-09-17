# LeDatPlatform source API release build

## Status

Complete — built and verified on 2026-09-17 with the specified LeDatCanvas source and JDK 21.

## Goal

Build `LeDatItemUpgrader` against the current `LeDatCanvas` API source with Java 21, without bundling LeDatPlatform classes into the plugin artifact.

## Scope

- Add a reproducible `-PledatCanvasDir` build route that rebuilds the Platform API before compiling Paper.
- Retain an explicit `-PplatformApiJar` override for a separately supplied, Java 21-compatible API JAR.
- Reject missing, ambiguous, or Java-version-incompatible API artifacts and verify the release JAR has no Platform API classes.
- Do not enable live transactions, change player data/configuration contracts, or modify LeDatCanvas source.

## Acceptance criteria

1. A Java 21 build can produce `paper/build/libs/LeDatItemUpgrader-0.9.0-phase09a.jar` using the specified LeDatCanvas directory.
2. The build checks the API classes and Java 21 bytecode before Paper compilation.
3. The output has ItemUpgrader's plugin metadata and does not contain `vn/ledat/platform/` classes.

## Validation

- Build `:ledat-platform-api:clean :ledat-platform-api:jar` via the LeDatCanvas wrapper with JDK 21.
- Run the ItemUpgrader backend checks and Paper JAR build through the new source-API route.
- Inspect JAR entries and `javap` classfile versions.
