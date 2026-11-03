#!/usr/bin/env python3
"""Manga D UI phase 1 overlay for the pinned upstream Mihon source checkout.

Applies only a color palette, navigation labels and preference-independent default
appearance. Does NOT replace Mihon data, extension, reader, or networking code.
"""
import argparse
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
THEME_SOURCE = HERE / "ui/MangaDColorScheme.kt"
THEME_REL = "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt"
HOME_REL = "app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt"
PREFERENCES_REL = "app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt"
PALETTE_REL = "app/src/main/java/eu/kanade/presentation/theme/colorscheme/MangaDColorScheme.kt"


def replace_once_or_already(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        if text.count(new) != 1:
            raise RuntimeError(f"Unexpected duplicate {label}")
        return text
    if text.count(old) != 1:
        raise RuntimeError(f"Upstream layout mismatch: {label}; expected exactly one anchor, got {text.count(old)}")
    return text.replace(old, new, 1)


def patch(root: Path) -> None:
    root = Path(root).resolve()
    theme_file = root / THEME_REL
    home_file = root / HOME_REL
    pref_file = root / PREFERENCES_REL
    palette = root / PALETTE_REL
    for p in (theme_file, home_file, pref_file):
        if not p.is_file():
            raise RuntimeError(f"Missing upstream source file: {p}")
    if not THEME_SOURCE.is_file():
        raise RuntimeError("Missing Manga D color palette file")
    # Check every transformation before writing any file: fail atomically.
    theme = theme_file.read_text(encoding="utf-8")
    theme = replace_once_or_already(
        theme,
        "import eu.kanade.presentation.theme.colorscheme.BaseColorScheme\n",
        "import eu.kanade.presentation.theme.colorscheme.BaseColorScheme\nimport eu.kanade.presentation.theme.colorscheme.MangaDColorScheme\n",
        "palette import",
    )
    theme = replace_once_or_already(
        theme,
        "AppTheme.DEFAULT to TachiyomiColorScheme,",
        "AppTheme.DEFAULT to MangaDColorScheme,",
        "default app palette",
    )
    theme = replace_once_or_already(
        theme,
        "        isDark = isDark,\n",
        "        isDark = isDark || appTheme == AppTheme.DEFAULT,\n",
        "dark default appearance",
    )
    prefs = pref_file.read_text(encoding="utf-8")
    prefs = replace_once_or_already(
        prefs,
        """        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },""",
        "        AppTheme.DEFAULT,",
        "default theme preference",
    )
    home = home_file.read_text(encoding="utf-8")
    home = replace_once_or_already(
        home,
        "import androidx.compose.ui.platform.LocalContext\n",
        "import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalLayoutDirection\nimport androidx.compose.ui.unit.LayoutDirection\n",
        "home RTL imports",
    )
    home = replace_once_or_already(
        home,
        "CompositionLocalProvider(LocalNavigator provides navigator) {",
        "CompositionLocalProvider(LocalNavigator provides navigator, LocalLayoutDirection provides LayoutDirection.Rtl) {",
        "home screen RTL",
    )
    home = replace_once_or_already(
        home,
        "                    text = tab.options.title,\n",
        """                    text = when (tab) {
                        LibraryTab -> "الرئيسية"
                        UpdatesTab -> "التحديثات"
                        HistoryTab -> "السجل"
                        BrowseTab -> "المصادر"
                        MoreTab -> "المزيد"
                        else -> tab.options.title
                    },
""",
        "Arabic navigation labels",
    )
    colors = THEME_SOURCE.read_text(encoding="utf-8")
    if palette.exists() and palette.read_text(encoding="utf-8") != colors:
        raise RuntimeError("Refusing to overwrite an incompatible MangaDColorScheme.kt")
    palette.parent.mkdir(parents=True, exist_ok=True)
    theme_file.write_text(theme, encoding="utf-8")
    home_file.write_text(home, encoding="utf-8")
    pref_file.write_text(prefs, encoding="utf-8")
    palette.write_text(colors, encoding="utf-8")
    print("Manga D UI phase 1 applied: periwinkle dark theme and Arabic navigation")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    args = parser.parse_args()
    patch(args.upstream)
