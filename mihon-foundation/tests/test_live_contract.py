"""Verify deliberate lazy-loading decisions before uploading to GitHub."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

class OnDemandSourceContract(unittest.TestCase):
    def test_native_source_loads_only_requested_pages(self):
        text=(ROOT/'qari_manga/QariMangaSource.kt').read_text()
        self.assertIn('override suspend fun getPopularManga(page: Int)',text)
        self.assertIn('QariLiveParser.archiveUrl(section, number)',text)
        self.assertIn('QariLiveParser.catalog(doc, section, number)',text)
        self.assertIn('override suspend fun getPageList(chapter: SChapter)',text)
        self.assertIn('store.importImages(section, full, htmlPage, json)',text)
        self.assertNotIn('nextUnscannedTitle',text)
        self.assertNotIn('nextUnscannedChapter',text)
        self.assertIn('CookieManager.getInstance().getCookie(url)',text)

    def test_browser_fallback_limits_to_one_catalog_page(self):
        text=(ROOT/'qari_manga/QariMangaSyncActivity.kt').read_text()
        self.assertIn('fun newIntent(context: Context, section: QariSection, page: Int = 1)',text)
        self.assertIn('cache.merge(section, page, collected, hasNext)',text)
        self.assertIn('setResult(RESULT_OK)',text)
        self.assertIn('finish()',text)
        self.assertNotIn('nextUnscannedTitle',text)
        self.assertNotIn('nextUnscannedChapter',text)

    def test_sources_are_not_replaced(self):
        code=(ROOT/'apply_mihon_sync.py').read_text()
        self.assertIn('QariSection.entries.forEach',code)
        self.assertIn('QariMangaSource(context, section)',code)
        self.assertIn('getHomeUrl()', (ROOT/'qari_manga/QariMangaSource.kt').read_text())
        self.assertEqual(5,len(list((ROOT/'qari_manga').glob('*.kt'))))

if __name__=='__main__':
    unittest.main()
