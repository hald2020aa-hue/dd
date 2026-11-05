"""Safe code overlay tests. The true Android compile is the GitHub Actions job."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('qari_overlay', ROOT / 'apply_mihon_sync.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class MihonLiveIntegrationTests(unittest.TestCase):
    def fixture(self, root):
        sources = {
            'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt': '''import dev.zacsweers.metro.AppScope
import eu.kanade.tachiyomi.source.online.HttpSource
class AndroidSourceManager(
    @AppCoroutineScope private val scope: CoroutineScope,
) {
                    sourcesMapFlow.value = mutableMap
}''',
            'app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt': '''import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.tachiyomi.source.online.HttpSource
        val uriHandler = LocalUriHandler.current
        val onWebViewClick = f@{
            val httpSource = source as? HttpSource ?: return@f
            navigator.push(
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),''',
            'app/src/main/AndroidManifest.xml': '''<manifest>
        <activity
            android:name=".ui.webview.WebViewActivity"
            android:exported="false" />
</manifest>''',
            'app/build.gradle.kts': '''splits {
            isUniversalApk = true
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}''',
        }
        for path, content in sources.items():
            f = root / path
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content)

    def test_patches_real_mihon_integration_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            mod.patch(root)
            source = (root / 'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt').read_text()
            browse = (root / 'app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt').read_text()
            self.assertIn('QariSection.entries.forEach', source)
            self.assertIn('rememberLauncherForActivityResult(', browse)
            self.assertIn('qariItems.refresh()', browse)
            self.assertIn('LoadState.Error', browse)
            self.assertIn('فتح الموقع / التحقق', browse)
            self.assertIn('mangaList = qariItems', browse)
            self.assertNotIn('مزامنة العناوين والفصول والصور', browse)
            self.assertIn('include("arm64-v8a")', (root/'app/build.gradle.kts').read_text())
            for file in ['QariMangaSource.kt', 'QariMangaStore.kt', 'QariMangaSyncActivity.kt',
                         'QariLiveParser.kt', 'QariLiveCache.kt']:
                self.assertTrue((root / mod.DEST_DIR / file).is_file())
            original = {path:(root/path).read_bytes() for path in mod.UPSTREAM_FILES}
            mod.patch(root)
            self.assertEqual(original, {path:(root/path).read_bytes() for path in mod.UPSTREAM_FILES})

    def test_bad_upstream_does_not_write_partially(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            broken = root/'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt'
            broken.write_text('wrong upstream version')
            manifest = root/'app/src/main/AndroidManifest.xml'
            original = manifest.read_bytes()
            with self.assertRaises(RuntimeError):
                mod.patch(root)
            self.assertEqual(original, manifest.read_bytes())
            self.assertFalse((root/mod.DEST_DIR).exists())

    def test_no_drop_tables_and_live_cache_is_page_scoped(self):
        store = (ROOT/'qari_manga/QariMangaStore.kt').read_text()
        live = (ROOT/'qari_manga/QariLiveCache.kt').read_text()
        self.assertNotIn('DROP TABLE', store)
        self.assertNotIn('DROP TABLE', live)
        self.assertIn('PRIMARY KEY(section,page)',live)
        self.assertIn('html_page ASC, position ASC', store)
        self.assertIn('upsertLiveTitles', store)

    def test_no_long_kotlin_lines(self):
        for file in (ROOT/'qari_manga').glob('*.kt'):
            long=[(number,len(line)) for number,line in enumerate(file.read_text().splitlines(), 1)
                  if len(line)>120]
            self.assertFalse(long,(file.name,long[:10]))


if __name__ == '__main__':
    unittest.main()
