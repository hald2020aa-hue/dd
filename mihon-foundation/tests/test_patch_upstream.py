import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parents[1] / "patch_upstream.py"
spec = importlib.util.spec_from_file_location("patch_upstream", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class MihonPatchTests(unittest.TestCase):
    def make_fixture(self, root):
        (root / "app").mkdir()
        (root / "i18n/src/commonMain/moko-resources/base").mkdir(parents=True)
        (root / "app/build.gradle.kts").write_text('android {\n  defaultConfig { applicationId = "app.mihon" }\n}\n', encoding="utf-8")
        (root / "i18n/src/commonMain/moko-resources/base/strings.xml").write_text('<resources><string name="app_name" translatable="false">Mihon</string></resources>\n', encoding="utf-8")

    def test_patches_both_fields_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.make_fixture(root)
            mod.patch(root, check_commit=False)
            g = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
            s = (root / "i18n/src/commonMain/moko-resources/base/strings.xml").read_text(encoding="utf-8")
            self.assertIn(mod.NEW_APP_ID, g)
            self.assertIn(mod.NEW_APP_NAME, s)
            mod.patch(root, check_commit=False)
            self.assertEqual(g, (root / "app/build.gradle.kts").read_text(encoding="utf-8"))
            self.assertEqual(s, (root / "i18n/src/commonMain/moko-resources/base/strings.xml").read_text(encoding="utf-8"))

    def test_fails_closed_when_upstream_changes(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.make_fixture(root)
            (root / "app/build.gradle.kts").write_text('applicationId = "another.package"', encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "Unsafe upstream layout"):
                mod.patch(root, check_commit=False)

    def test_distinct_package_from_old_manga_d(self):
        self.assertNotEqual(mod.NEW_APP_ID, "com.aistudio.mangareader.kxrqzp")
        self.assertNotEqual(mod.NEW_APP_ID, "app.mihon")


if __name__ == "__main__":
    unittest.main()
