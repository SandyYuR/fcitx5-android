/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * Palette values are generated with Material Color Utilities from the pinned upstream
 * revision documented in README.md. This generator intentionally has no runtime role.
 */
import dynamiccolor.ColorSpec.SpecVersion;
import dynamiccolor.DynamicScheme;
import dynamiccolor.MaterialDynamicColors;
import hct.Hct;
import scheme.SchemeCmf;
import scheme.SchemeExpressive;
import scheme.SchemeNeutral;
import scheme.SchemeTonalSpot;
import scheme.SchemeVibrant;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class PaletteGenerator {
  private record Palette(String id, int primary, int secondary) {}
  private record Role(String id, Function<MaterialDynamicColors, dynamiccolor.DynamicColor> color) {}

  private static final List<Palette> PALETTES = List.of(
      new Palette("sea_salt", 0xff397d80, 0xffe8794b),
      new Palette("pine", 0xff416b4a, 0xffc99843),
      new Palette("berry", 0xff994865, 0xff4278a0),
      new Palette("amber", 0xff80551f, 0xff6b79bd),
      new Palette("wisteria", 0xff75639d, 0xffc66e89),
      new Palette("graphite", 0xff59646b, 0xff92704b));

  private static final List<Recipe> RECIPES = List.of(
      new Recipe("cmf2026", SpecVersion.SPEC_2026, SchemeKind.CMF),
      new Recipe("tonal2025", SpecVersion.SPEC_2025, SchemeKind.TONAL),
      new Recipe("vibrant2025", SpecVersion.SPEC_2025, SchemeKind.VIBRANT),
      new Recipe("neutral2025", SpecVersion.SPEC_2025, SchemeKind.NEUTRAL),
      new Recipe("expressive2025", SpecVersion.SPEC_2025, SchemeKind.EXPRESSIVE),
      new Recipe("classic2021", SpecVersion.SPEC_2021, SchemeKind.TONAL));

  private enum SchemeKind { CMF, TONAL, VIBRANT, NEUTRAL, EXPRESSIVE }
  private record Recipe(String id, SpecVersion spec, SchemeKind kind) {}

  private static final List<String> CONTRAST_IDS = List.of("standard", "medium", "high");
  private static final double[] CONTRAST_VALUES = {0.0, 0.5, 1.0};

  private static final List<Role> ROLES = List.of(
      new Role("primary", MaterialDynamicColors::primary),
      new Role("on_primary", MaterialDynamicColors::onPrimary),
      new Role("primary_container", MaterialDynamicColors::primaryContainer),
      new Role("on_primary_container", MaterialDynamicColors::onPrimaryContainer),
      new Role("primary_inverse", MaterialDynamicColors::inversePrimary),
      new Role("primary_fixed", MaterialDynamicColors::primaryFixed),
      new Role("primary_fixed_dim", MaterialDynamicColors::primaryFixedDim),
      new Role("on_primary_fixed", MaterialDynamicColors::onPrimaryFixed),
      new Role("on_primary_fixed_variant", MaterialDynamicColors::onPrimaryFixedVariant),
      new Role("secondary", MaterialDynamicColors::secondary),
      new Role("on_secondary", MaterialDynamicColors::onSecondary),
      new Role("secondary_container", MaterialDynamicColors::secondaryContainer),
      new Role("on_secondary_container", MaterialDynamicColors::onSecondaryContainer),
      new Role("secondary_fixed", MaterialDynamicColors::secondaryFixed),
      new Role("secondary_fixed_dim", MaterialDynamicColors::secondaryFixedDim),
      new Role("on_secondary_fixed", MaterialDynamicColors::onSecondaryFixed),
      new Role("on_secondary_fixed_variant", MaterialDynamicColors::onSecondaryFixedVariant),
      new Role("tertiary", MaterialDynamicColors::tertiary),
      new Role("on_tertiary", MaterialDynamicColors::onTertiary),
      new Role("tertiary_container", MaterialDynamicColors::tertiaryContainer),
      new Role("on_tertiary_container", MaterialDynamicColors::onTertiaryContainer),
      new Role("tertiary_fixed", MaterialDynamicColors::tertiaryFixed),
      new Role("tertiary_fixed_dim", MaterialDynamicColors::tertiaryFixedDim),
      new Role("on_tertiary_fixed", MaterialDynamicColors::onTertiaryFixed),
      new Role("on_tertiary_fixed_variant", MaterialDynamicColors::onTertiaryFixedVariant),
      new Role("error", MaterialDynamicColors::error),
      new Role("on_error", MaterialDynamicColors::onError),
      new Role("error_container", MaterialDynamicColors::errorContainer),
      new Role("on_error_container", MaterialDynamicColors::onErrorContainer),
      new Role("background", MaterialDynamicColors::background),
      new Role("on_background", MaterialDynamicColors::onBackground),
      new Role("surface", MaterialDynamicColors::surface),
      new Role("surface_dim", MaterialDynamicColors::surfaceDim),
      new Role("surface_bright", MaterialDynamicColors::surfaceBright),
      new Role("surface_container_lowest", MaterialDynamicColors::surfaceContainerLowest),
      new Role("surface_container_low", MaterialDynamicColors::surfaceContainerLow),
      new Role("surface_container", MaterialDynamicColors::surfaceContainer),
      new Role("surface_container_high", MaterialDynamicColors::surfaceContainerHigh),
      new Role("surface_container_highest", MaterialDynamicColors::surfaceContainerHighest),
      new Role("on_surface", MaterialDynamicColors::onSurface),
      new Role("surface_variant", MaterialDynamicColors::surfaceVariant),
      new Role("on_surface_variant", MaterialDynamicColors::onSurfaceVariant),
      new Role("inverse_surface", MaterialDynamicColors::inverseSurface),
      new Role("inverse_on_surface", MaterialDynamicColors::inverseOnSurface),
      new Role("outline", MaterialDynamicColors::outline),
      new Role("outline_variant", MaterialDynamicColors::outlineVariant),
      new Role("shadow", MaterialDynamicColors::shadow),
      new Role("scrim", MaterialDynamicColors::scrim),
      new Role("surface_tint", MaterialDynamicColors::surfaceTint));

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: PaletteGenerator <app/src/main/res/values directory>");
    }
    Path values = Path.of(args[0]);
    Files.createDirectories(values);
    Path colorsPath = values.resolve("colors_settings_ui_palettes.xml");
    Path stylesPath = values.resolve("styles_settings_ui_palettes.xml");
    try (BufferedWriter colors = Files.newBufferedWriter(colorsPath);
         BufferedWriter styles = Files.newBufferedWriter(stylesPath)) {
      colors.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
      styles.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
      for (Palette palette : PALETTES) {
        for (Recipe recipe : RECIPES) {
          for (int contrast = 0; contrast < CONTRAST_IDS.size(); contrast++) {
            for (boolean dark : List.of(false, true)) {
              DynamicScheme scheme = createScheme(
                  recipe, palette, dark, CONTRAST_VALUES[contrast]);
              writeColors(colors, palette.id, recipe.id, CONTRAST_IDS.get(contrast), dark, scheme);
              writeStyle(styles, palette.id, recipe.id, CONTRAST_IDS.get(contrast), dark);
            }
          }
        }
      }

      // The compatibility theme adapts the long-standing app primary color into a complete
      // Material 3 role set. It does not assert that the previous Material Components theme
      // used the SPEC_2021 algorithm.
      for (int contrast = 0; contrast < CONTRAST_IDS.size(); contrast++) {
        for (boolean dark : List.of(false, true)) {
          Palette compatibilitySeed = new Palette("compat", 0xff6200ee, 0xff03dac6);
          DynamicScheme scheme = createScheme(
              new Recipe("compat", SpecVersion.SPEC_2021, SchemeKind.TONAL),
              compatibilitySeed, dark, CONTRAST_VALUES[contrast]);
          writeColors(colors, "compat", "compat", CONTRAST_IDS.get(contrast), dark, scheme);
          writeStyle(styles, "compat", "compat", CONTRAST_IDS.get(contrast), dark);
        }
      }
      colors.write("</resources>\n");
      styles.write("</resources>\n");
    }
    System.out.println("Generated " + colorsPath + " and " + stylesPath);
  }

  private static DynamicScheme createScheme(Recipe recipe, Palette palette, boolean dark, double contrast) {
    Hct primary = Hct.fromInt(palette.primary);
    Hct secondary = Hct.fromInt(palette.secondary);
    return switch (recipe.kind) {
      case CMF -> new SchemeCmf(List.of(primary, secondary), dark, contrast,
          SpecVersion.SPEC_2026, DynamicScheme.Platform.PHONE);
      case TONAL -> new SchemeTonalSpot(primary, dark, contrast, recipe.spec,
          DynamicScheme.Platform.PHONE);
      case VIBRANT -> new SchemeVibrant(primary, dark, contrast, recipe.spec,
          DynamicScheme.Platform.PHONE);
      case NEUTRAL -> new SchemeNeutral(primary, dark, contrast, recipe.spec,
          DynamicScheme.Platform.PHONE);
      case EXPRESSIVE -> new SchemeExpressive(primary, dark, contrast, recipe.spec,
          DynamicScheme.Platform.PHONE);
    };
  }

  private static void writeColors(BufferedWriter out, String family, String recipe,
      String contrast, boolean dark, DynamicScheme scheme) throws Exception {
    MaterialDynamicColors roles = new MaterialDynamicColors();
    String mode = dark ? "dark" : "light";
    for (Role role : ROLES) {
      String name = colorName(family, recipe, contrast, mode, role.id);
      int argb = role.color.apply(roles).getArgb(scheme);
      out.write("  <color name=\"" + name + "\">" +
          String.format(Locale.ROOT, "#%08X", argb) + "</color>\n");
    }
  }

  private static void writeStyle(BufferedWriter out, String family, String recipe,
      String contrast, boolean dark) throws Exception {
    String mode = dark ? "dark" : "light";
    String suffix = family + "." + recipe + "." + contrast + "." + mode;
    String parent = dark
        ? "Theme.FcitxSettings.Base.Dark"
        : "Theme.FcitxSettings.Base.Light";
    out.write("  <style name=\"Theme.FcitxSettings." + suffix + "\" parent=\"" + parent + "\">\n");
    item(out, "colorPrimary", family, recipe, contrast, mode, "primary");
    item(out, "colorOnPrimary", family, recipe, contrast, mode, "on_primary");
    item(out, "colorPrimaryVariant", family, recipe, contrast, mode, "primary");
    item(out, "colorPrimaryInverse", family, recipe, contrast, mode, "primary_inverse");
    item(out, "colorPrimaryContainer", family, recipe, contrast, mode, "primary_container");
    item(out, "colorOnPrimaryContainer", family, recipe, contrast, mode, "on_primary_container");
    item(out, "colorPrimaryFixed", family, recipe, contrast, mode, "primary_fixed");
    item(out, "colorPrimaryFixedDim", family, recipe, contrast, mode, "primary_fixed_dim");
    item(out, "colorOnPrimaryFixed", family, recipe, contrast, mode, "on_primary_fixed");
    item(out, "colorOnPrimaryFixedVariant", family, recipe, contrast, mode, "on_primary_fixed_variant");
    item(out, "colorSecondary", family, recipe, contrast, mode, "secondary");
    item(out, "colorSecondaryVariant", family, recipe, contrast, mode, "secondary");
    item(out, "colorOnSecondary", family, recipe, contrast, mode, "on_secondary");
    item(out, "colorSecondaryContainer", family, recipe, contrast, mode, "secondary_container");
    item(out, "colorOnSecondaryContainer", family, recipe, contrast, mode, "on_secondary_container");
    item(out, "colorSecondaryFixed", family, recipe, contrast, mode, "secondary_fixed");
    item(out, "colorSecondaryFixedDim", family, recipe, contrast, mode, "secondary_fixed_dim");
    item(out, "colorOnSecondaryFixed", family, recipe, contrast, mode, "on_secondary_fixed");
    item(out, "colorOnSecondaryFixedVariant", family, recipe, contrast, mode, "on_secondary_fixed_variant");
    item(out, "colorTertiary", family, recipe, contrast, mode, "tertiary");
    item(out, "colorOnTertiary", family, recipe, contrast, mode, "on_tertiary");
    item(out, "colorTertiaryContainer", family, recipe, contrast, mode, "tertiary_container");
    item(out, "colorOnTertiaryContainer", family, recipe, contrast, mode, "on_tertiary_container");
    item(out, "colorTertiaryFixed", family, recipe, contrast, mode, "tertiary_fixed");
    item(out, "colorTertiaryFixedDim", family, recipe, contrast, mode, "tertiary_fixed_dim");
    item(out, "colorOnTertiaryFixed", family, recipe, contrast, mode, "on_tertiary_fixed");
    item(out, "colorOnTertiaryFixedVariant", family, recipe, contrast, mode, "on_tertiary_fixed_variant");
    item(out, "colorError", family, recipe, contrast, mode, "error");
    item(out, "colorOnError", family, recipe, contrast, mode, "on_error");
    item(out, "colorErrorContainer", family, recipe, contrast, mode, "error_container");
    item(out, "colorOnErrorContainer", family, recipe, contrast, mode, "on_error_container");
    item(out, "android:colorBackground", family, recipe, contrast, mode, "background");
    item(out, "colorOnBackground", family, recipe, contrast, mode, "on_background");
    item(out, "colorSurface", family, recipe, contrast, mode, "surface");
    item(out, "colorOnSurface", family, recipe, contrast, mode, "on_surface");
    item(out, "colorSurfaceVariant", family, recipe, contrast, mode, "surface_variant");
    item(out, "colorOnSurfaceVariant", family, recipe, contrast, mode, "on_surface_variant");
    item(out, "colorSurfaceInverse", family, recipe, contrast, mode, "inverse_surface");
    item(out, "colorOnSurfaceInverse", family, recipe, contrast, mode, "inverse_on_surface");
    item(out, "colorSurfaceBright", family, recipe, contrast, mode, "surface_bright");
    item(out, "colorSurfaceDim", family, recipe, contrast, mode, "surface_dim");
    item(out, "colorSurfaceContainer", family, recipe, contrast, mode, "surface_container");
    item(out, "colorSurfaceContainerLow", family, recipe, contrast, mode, "surface_container_low");
    item(out, "colorSurfaceContainerLowest", family, recipe, contrast, mode, "surface_container_lowest");
    item(out, "colorSurfaceContainerHigh", family, recipe, contrast, mode, "surface_container_high");
    item(out, "colorSurfaceContainerHighest", family, recipe, contrast, mode, "surface_container_highest");
    item(out, "colorOutline", family, recipe, contrast, mode, "outline");
    item(out, "colorOutlineVariant", family, recipe, contrast, mode, "outline_variant");
    item(out, "colorPrimaryDark", family, recipe, contrast, mode, "primary");
    item(out, "colorControlActivated", family, recipe, contrast, mode, "primary");
    item(out, "android:colorAccent", family, recipe, contrast, mode, "primary");
    item(out, "android:statusBarColor", family, recipe, contrast, mode, "surface");
    item(out, "android:navigationBarColor", family, recipe, contrast, mode, "surface");
    out.write("  </style>\n");
  }

  private static void item(BufferedWriter out, String attr, String family, String recipe,
      String contrast, String mode, String role) throws Exception {
    out.write("    <item name=\"" + attr + "\">@color/" +
        colorName(family, recipe, contrast, mode, role) + "</item>\n");
  }

  private static String colorName(String family, String recipe, String contrast,
      String mode, String role) {
    return "settings_ui_" + family + "_" + recipe + "_" + contrast + "_" + mode + "_" + role;
  }
}
