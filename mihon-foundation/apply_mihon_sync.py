#!/usr/bin/env python3
"""Integrate browser-fed Manga D catalog into official pinned Mihon ARM64 source.

This does not overwrite Mihon's database, reader, extension engine, or backups.
All anchors checked before any file is written. APK compilation happens in CI.
"""
from pathlib import Path
import argparse

HERE = Path(__file__).resolve().parent
UPSTREAM_FILES = {
    'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt': None,
    'app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt': None,
    'app/src/main/AndroidManifest.xml': None,
    'app/build.gradle.kts': None,
}
SRC_DIR = HERE / 'qari_manga'
DEST_DIR = Path('app/src/main/java/eu/kanade/tachiyomi/source/qari')


def replace_one(source: str, before: str, after: str, label: str) -> str:
    if after in source:
        if source.count(after) != 1:
            raise RuntimeError(f'Duplicated previously applied change: {label}')
        return source
    count = source.count(before)
    if count != 1:
        raise RuntimeError(f'Cannot safely patch {label}: expected one anchor, found {count}')
    return source.replace(before, after, 1)


def patch(root: Path) -> None:
    root = root.resolve()
    modified = {}
    for relative in UPSTREAM_FILES:
        f = root / relative
        if not f.is_file():
            raise FileNotFoundError(f'Missing pinned Mihon file: {f}')
        modified[relative] = f.read_text(encoding='utf-8')

    key = 'app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt'
    s = modified[key]
    s = replace_one(s, 'import dev.zacsweers.metro.AppScope\n',
        'import android.content.Context\nimport dev.zacsweers.metro.AppScope\n', 'source manager Context import')
    s = replace_one(s, 'import eu.kanade.tachiyomi.source.online.HttpSource\n',
        'import eu.kanade.tachiyomi.source.online.HttpSource\n'
        'import eu.kanade.tachiyomi.source.qari.QariMangaSource\n'
        'import eu.kanade.tachiyomi.source.qari.QariSection\n', 'catalog source imports')
    s = replace_one(s,
        '    @AppCoroutineScope private val scope: CoroutineScope,\n',
        '    @AppCoroutineScope private val scope: CoroutineScope,\n'
        '    private val context: Context,\n', 'Context injection')
    s = replace_one(s,
        '                    sourcesMapFlow.value = mutableMap\n',
        '''                    QariSection.entries.forEach { section ->
                        val source = QariMangaSource(context, section)
                        mutableMap[source.id] = source
                        registerStubSource(StubSource.from(source))
                    }
                    sourcesMapFlow.value = mutableMap
''', 'built-in Manga D source registration')
    modified[key] = s

    key = 'app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt'
    s = modified[key]
    s = replace_one(s, 'import androidx.compose.foundation.background\n',
        'import androidx.activity.compose.rememberLauncherForActivityResult\n'
        'import androidx.activity.result.contract.ActivityResultContracts\n'
        'import androidx.compose.foundation.background\n', 'activity result imports')
    s = replace_one(s, 'import androidx.compose.runtime.remember\n',
        'import androidx.compose.runtime.remember\n'
        'import androidx.compose.runtime.mutableStateOf\n'
        'import androidx.compose.runtime.setValue\n', 'live browser state imports')
    s = replace_one(s, 'import androidx.compose.ui.Modifier\n',
        'import androidx.compose.ui.Modifier\nimport androidx.paging.LoadState\n', 'paging error import')
    s = replace_one(s,
        'import androidx.compose.ui.platform.LocalConfiguration\n',
        'import androidx.compose.ui.platform.LocalConfiguration\n'
        'import androidx.compose.ui.platform.LocalContext\n', 'browse Context import')
    s = replace_one(s,
        'import eu.kanade.tachiyomi.source.online.HttpSource\n',
        'import eu.kanade.tachiyomi.source.online.HttpSource\n'
        'import eu.kanade.tachiyomi.source.qari.QariMangaSource\n'
        'import eu.kanade.tachiyomi.source.qari.QariMangaSyncActivity\n', 'browse source imports')
    s = replace_one(s,
        '        val uriHandler = LocalUriHandler.current\n',
        '        val uriHandler = LocalUriHandler.current\n'
        '        val qariContext = LocalContext.current\n'
        '        val qariItems = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()\n'
        '        val qariLauncher = rememberLauncherForActivityResult(\n'
        '            ActivityResultContracts.StartActivityForResult(),\n'
        '        ) { qariItems.refresh() }\n'
        '        var qariOpenedPage by remember(source.id) { mutableStateOf(-1) }\n'
        '        LaunchedEffect(qariItems.loadState.refresh, qariItems.loadState.append, source.id) {\n'
        '            if (source is QariMangaSource) {\n'
        '                val refreshFailure = qariItems.loadState.refresh as? LoadState.Error\n'
        '                val appendFailure = qariItems.loadState.append as? LoadState.Error\n'
        '                val reason = (refreshFailure?.error?.message.orEmpty() +\n'
        '                    appendFailure?.error?.message.orEmpty())\n'
        '                if (reason.contains("تحقق") || reason.contains("403")) {\n'
        '                    val requestedPage = source.pendingPage()\n'
        '                    if (qariOpenedPage != requestedPage) {\n'
        '                        qariOpenedPage = requestedPage\n'
        '                        qariLauncher.launch(\n'
        '                            QariMangaSyncActivity.newIntent(qariContext, source.section, requestedPage),\n'
        '                        )\n'
        '                    }\n'
        '                }\n'
        '            }\n'
        '        }\n', 'native paging verification fallback')
    s = replace_one(s,
        '''        val onWebViewClick = f@{
            val httpSource = source as? HttpSource ?: return@f
            navigator.push(''',
        '''        val onWebViewClick = f@{
            val httpSource = source as? HttpSource ?: return@f
            if (source is QariMangaSource) {
                qariLauncher.launch(QariMangaSyncActivity.newIntent(qariContext, source.section, source.pendingPage()))
                return@f
            }
            navigator.push(''', 'verified-site fallback from native browse icon')
    s = replace_one(s,
        '''                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())''',
        '''                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())''',
        'browse chip row anchor')  # harmless preflight
    s = replace_one(s,
        '''                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,''',
        '''                    ) {
                        if (source is QariMangaSource) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    qariLauncher.launch(
                                        QariMangaSyncActivity.newIntent(qariContext, source.section, source.pendingPage()),
                                    )
                                },
                                label = { Text("فتح الموقع / التحقق") },
                            )
                        }
                        FilterChip(
                            selected = state.listing == Listing.Popular,''',
        'native source sync button')
    s = replace_one(s,
        '                mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),',
        '                mangaList = qariItems,', 'refresh source after browser verification')
    modified[key] = s

    key = 'app/src/main/AndroidManifest.xml'
    s = modified[key]
    s = replace_one(s,
        '''        <activity
            android:name=".ui.webview.WebViewActivity"''',
        '''        <activity
            android:name=".source.qari.QariMangaSyncActivity"
            android:exported="false" />

        <activity
            android:name=".ui.webview.WebViewActivity"''', 'sync Activity registration')
    modified[key] = s

    key = 'app/build.gradle.kts'
    s = modified[key]
    s = replace_one(s, '            isUniversalApk = true\n',
        '            isUniversalApk = false\n', 'disable universal APK')
    s = replace_one(s, '            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")\n',
        '            include("arm64-v8a")\n', 'only ARM64 ABI')
    modified[key] = s

    code = list(SRC_DIR.glob('*.kt'))
    if len(code) != 5:
        raise RuntimeError(f'Expected 5 Kotlin files, found {len(code)}')
    resources = list(SRC_DIR.glob('*.js'))
    if len(resources) != 1:
        raise RuntimeError('Expected single JS extractor asset')
    assets = root / 'app/src/main/assets/qari_manga_scan.js'
    for f in code:
        target = root / DEST_DIR / f.name
        if target.exists() and target.read_bytes() != f.read_bytes():
            raise RuntimeError(f'Refusing to overwrite mismatched generated code: {target}')
    if assets.exists() and assets.read_bytes() != resources[0].read_bytes():
        raise RuntimeError('Refusing to overwrite mismatched qari_manga_scan.js asset')

    # No writes before all validation passes (including the generated file list).
    for relative, content in modified.items():
        (root / relative).write_text(content, encoding='utf-8')
    (root / DEST_DIR).mkdir(parents=True, exist_ok=True)
    for f in code:
        (root / DEST_DIR / f.name).write_bytes(f.read_bytes())
    assets.parent.mkdir(parents=True, exist_ok=True)
    assets.write_bytes(resources[0].read_bytes())
    print('Manga D Mihon: four on-demand native catalog sources + verification fallback; ARM64 only')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('upstream', type=Path)
    args = parser.parse_args()
    patch(args.upstream)
