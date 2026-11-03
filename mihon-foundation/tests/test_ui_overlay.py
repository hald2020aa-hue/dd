"""Lightweight patch tests; Gradle/Android build is separate GitHub Actions step."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("manga_d_ui", ROOT / "apply_ui.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MangaDUIOverlayTest(unittest.TestCase):
    def build_fixture(self, root):
        a = root / module.THEME_REL
        b = root / module.HOME_REL
        p = root / module.PREFERENCES_REL
        a.parent.mkdir(parents=True, exist_ok=True)
        b.parent.mkdir(parents=True, exist_ok=True)
        p.parent.mkdir(parents=True, exist_ok=True)
        a.write_text("import eu.kanade.presentation.theme.colorscheme.BaseColorScheme\nAppTheme.DEFAULT to TachiyomiColorScheme,\n        isDark = isDark,\n", encoding="utf-8")
        b.write_text("import androidx.compose.ui.platform.LocalContext\nCompositionLocalProvider(LocalNavigator provides navigator) {\n                    text = tab.options.title,\n", encoding="utf-8")
        p.write_text("        if (DeviceUtil.isDynamicColorAvailable) {\n            AppTheme.MONET\n        } else {\n            AppTheme.DEFAULT\n        },", encoding="utf-8")

    def test_overlay_changes_only_ui_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.build_fixture(root)
            module.patch(root)
            first = [(root / p).read_bytes() for p in (module.THEME_REL, module.HOME_REL, module.PALETTE_REL, module.PREFERENCES_REL)]
            module.patch(root)
            self.assertEqual(first, [(root / p).read_bytes() for p in (module.THEME_REL, module.HOME_REL, module.PALETTE_REL, module.PREFERENCES_REL)])
            self.assertIn("AppTheme.DEFAULT to MangaDColorScheme", first[0].decode())
            self.assertIn("الرئيسية", first[1].decode())
            self.assertIn("0xFF9BA8EE", first[2].decode())
            self.assertIn("AppTheme.DEFAULT", first[3].decode())
            self.assertIn("LocalLayoutDirection provides LayoutDirection.Rtl", first[1].decode())

    def test_anchor_mismatch_does_not_modify_upstream(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.build_fixture(root)
            home = root / module.HOME_REL
            home.write_text("unknown layout", encoding="utf-8")
            before = (root / module.THEME_REL).read_bytes()
            with self.assertRaises(RuntimeError):
                module.patch(root)
            self.assertEqual(before, (root / module.THEME_REL).read_bytes())


if __name__ == "__main__":
    unittest.main()
