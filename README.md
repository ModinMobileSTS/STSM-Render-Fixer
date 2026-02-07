# ImageIOCompat (Maven)

This is a small ModTheSpire patch mod that prevents Android crashes caused by desktop-only `javax.imageio.*` usage
in mods like MintSkinLewdTheSpire.

## Requirements
- JDK 8 (or newer, but compile target is 1.8)
- Maven 3.x
- Slay the Spire installed via Steam (so you can reference local jars)

## Setup
1) Edit `pom.xml` and set:
   - `Steam.path` to your Steam library's `steamapps` directory.

2) Verify these jar paths exist on your machine:
   - `${Steam.path}/common/SlayTheSpire/desktop-1.0.jar`
   - `${Steam.path}/workshop/content/646570/<workshopId>/ModTheSpire.jar`

   Note: Workshop IDs differ per installation. If your path differs, update the properties in `pom.xml`.

## Build
```bash
mvn -q clean package
```

## Develop
If you see many errors in your IDE, try this:

1. Go File → Project Structure → Modules → Dependencies

2. Find and add ModTheSpire.jar desktop-1.0.jar BaseMod.jar

After packaging, the POM copies the jar to:
`{Steam.path}/common/SlayTheSpire/mods/mintskin_android_compat.jar`

If you don't want auto-copy, remove the `maven-antrun-plugin` section from the POM.
