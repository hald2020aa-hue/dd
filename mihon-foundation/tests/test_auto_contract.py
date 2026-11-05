"""Regression checks for the on-demand browser verification flow."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

class LiveSourceTests(unittest.TestCase):
    def test_no_archive_wide_scan(self):
        browser=(ROOT/'qari_manga/QariMangaSyncActivity.kt').read_text('utf-8')
        self.assertIn('cache.merge(section, page, collected, hasNext)',browser)
        self.assertIn('setResult(RESULT_OK)',browser)
        self.assertNotIn('start(fresh = false)',browser)
        self.assertNotIn('nextUnscannedTitle',browser)
    def test_auto_browser_on_load_error(self):
        patch=(ROOT/'apply_mihon_sync.py').read_text('utf-8')
        self.assertIn('LoadState.Error',patch)
        self.assertIn('qariLauncher.launch(',patch)
        self.assertIn('qariItems.refresh()',patch)
        self.assertNotIn('qari_first_import_v2',patch)

if __name__=='__main__':
    unittest.main()
