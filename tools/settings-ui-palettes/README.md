# Settings interface color palettes

The files in `app/src/main/res/values/colors_settings_ui_palettes.xml` and
`styles_settings_ui_palettes.xml` are generated role tables for the Material 3 Expressive
settings shell. The app does not include MCU as a runtime dependency. Android selects one
complete light or dark theme before an app-owned settings Activity creates its views.

## Source and parameters

- Generator source: `PaletteGenerator.java` in this directory.
- Material Color Utilities source: Google `material-color-utilities/java` at
  `dd62982dfdefdfc9232ce8d76e122fb1c6d188ae` (2026-10-18).
- License: `LICENSE-Material-Color-Utilities.txt`.
- Platform: `PHONE`.
- Contrasts: standard `0.0`, medium `0.5`, high `1.0`.
- Recipes: CMF + SPEC_2026; TONAL_SPOT, VIBRANT, NEUTRAL and EXPRESSIVE + SPEC_2025;
  TONAL_SPOT + SPEC_2021.
- CMF gets both source colors. The other recipes use the primary seed.
- Seeds and exact IDs are recorded in `palette-manifest.json`.

The `compat` roles adapt the long-standing Fcitx purple seed `#6200EE` into a complete
Material 3 role set. This records the old color intent; it does not claim that the previous
Material Components theme used the SPEC_2021 algorithm.

## Regenerate

Check out the pinned MCU revision and compile its `java` source tree with the upstream
AndroidX annotation and Error Prone annotation artifacts available on the classpath. Then
compile this generator against those classes and run it with the app values directory:

```sh
javac -source 17 -target 17 -cp "$MCU_CLASSES" -d "$MCU_CLASSES" \
  tools/settings-ui-palettes/PaletteGenerator.java
java -cp "$MCU_CLASSES" PaletteGenerator app/src/main/res/values
```

The generated XML is committed so ordinary app builds need neither the MCU checkout nor
the generator classes. Keep the resource shrinker rules in `app/src/main/res/raw/keep.xml`
in sync if resource names or prefixes change.

## Reference values

The generated CMF 2026 sea salt standard light palette contains:

| Role | ARGB |
| --- | --- |
| primary | `#FF21696C` |
| onPrimary | `#FFE3FEFF` |
| primaryContainer | `#FF397D80` |
| onPrimaryContainer | `#FFFFFFFF` |

These values are a regeneration check for the pinned source and parameters.
