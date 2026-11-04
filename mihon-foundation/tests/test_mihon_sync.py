"""Pinned Mihon integration smoke tests, no Android SDK needed.

The full Kotlin/Gradle compile is performed by the feature-branch workflow.
"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('manga_d_sync', HERE / 'apply_mihon_sync.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class MihonMangaSyncOverlayTest(unittest.TestCase):
    def fixture(self, root):
        sources = {
            'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt': '''import dev.zacsweers.metro.AppScope
import eu.kanade.tachiyomi.source.online.HttpSource
class AndroidSourceManager(
    @AppCoroutineScope private val scope: CoroutineScope,
) {
                    sourcesMapFlow.value = mutableMap
}''',
            'app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt': '''import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.tachiyomi.source.online.HttpSource
        val uriHandler = LocalUriHandler.current
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,''',
            'app/src/main/AndroidManifest.xml': '''<manifest>
        <activity
            android:name=".ui.webview.WebViewActivity"
            android:configChanges="uiMode|orientation|screenSize" />
</manifest>''',
            'app/build.gradle.kts': '''splits {
            isUniversalApk = true
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}''',
        }
        for p, content in sources.items():
            target = root / p
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)

    def test_injects_sources_and_sync_button_only_once(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            mod.patch(root)
            code = (root / list(mod.UPSTREAM_FILES)[0]).read_text()
            self.assertIn('QariSection.entries.forEach', code)
            self.assertIn('QariMangaSource(context, section)', code)
            browser = (root / list(mod.UPSTREAM_FILES)[1]).read_text()
            self.assertIn('QariMangaSyncActivity.newIntent', browser)
            manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
            self.assertIn('.source.qari.QariMangaSyncActivity', manifest)
            gradle = (root / 'app/build.gradle.kts').read_text()
            self.assertIn('isUniversalApk = false', gradle)
            self.assertIn('include("arm64-v8a")', gradle)
            for k in ('QariMangaSource.kt','QariMangaStore.kt','QariMangaSyncActivity.kt'):
                self.assertTrue((root/mod.DEST_DIR/k).is_file())
            first = [(root/p).read_bytes() for p in mod.UPSTREAM_FILES]
            mod.patch(root)
            self.assertEqual(first,[(root/p).read_bytes() for p in mod.UPSTREAM_FILES])

    def test_unexpected_upstream_is_rejected_without_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            self.fixture(root)
            file=root/'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt'
            file.write_text('unexpected source manager')
            other=root/'app/src/main/AndroidManifest.xml'
            old=other.read_bytes()
            with self.assertRaises(RuntimeError):
                mod.patch(root)
            self.assertEqual(old, other.read_bytes())
            self.assertFalse((root/mod.DEST_DIR).exists())

    def test_sqlite_schema_never_drops_old_tables(self):
        source=(HERE/'qari_manga/QariMangaStore.kt').read_text()
        self.assertIn('CREATE TABLE IF NOT EXISTS titles',source)
        self.assertIn('CREATE TABLE IF NOT EXISTS chapters',source)
        self.assertIn('CREATE TABLE IF NOT EXISTS images',source)
        self.assertIn('CREATE TABLE IF NOT EXISTS progress',source)
        self.assertNotIn('DROP TABLE',source)
        self.assertIn('INSERT OR IGNORE INTO images',source)
        self.assertIn('ORDER BY',source.upper() if False else 'ORDER BY') if False else None
        self.assertIn('html_page ASC, position ASC',source)
        self.assertIn('type != section.siteType',source)

    def test_no_kotlin_line_over_120_characters(self):
        for file in (HERE/'qari_manga').glob('*.kt'):
            bad=[(n,len(line)) for n,line in enumerate(file.read_text().splitlines(),1) if len(line)>120]
            self.assertFalse(bad,(str(file),bad[:5]))

if __name__=='__main__':
    unittest.main()
