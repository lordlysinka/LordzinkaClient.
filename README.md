# Lordzinka Client

Fabric client mod for Minecraft 1.21.11 (requires Fabric API).

Modules: HUD, Chunk Finder, Block ESP, Entity ESP, Player Radar (60m), Nametags,
Amethyst ESP, Vanish Detector, Auto Totem. Control with `/lz` (or `/lordzinka`).

## Getting the .jar (GitHub Actions)
1. Push this folder to a GitHub repository.
2. Open the **Actions** tab -> **Build Lordzinka Client** -> latest run.
3. Download the **lordzinka-client** artifact, unzip it, put the `.jar` in `.minecraft/mods`
   together with Fabric API.

Optional: push a tag (`git tag v1.0.0 && git push --tags`) to get the jar on the Releases page.

## Building locally
Install JDK 21 and Gradle 9.x, then run `gradle build`. Output: `build/libs/lordzinka-client-1.0.0.jar`.

If Gradle reports a missing dependency version, update `gradle.properties` with the values from
https://fabricmc.net/develop
