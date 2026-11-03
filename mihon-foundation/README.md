# Manga D Core — real Mihon foundation, phase 1

## What this branch actually does

This branch retains the previous Manga D project untouched and adds a new,
**independent GitHub Actions build** of the **real official Mihon Android app**.
The workflow checks out the complete official Mihon repository, pinned to
commit `8794e89a866a3de578c02cefac1e609bcff5f092`, rather than attempting
to replicate Mihon's Source API or `NetworkHelper` with local stubs.

Only two source-level modifications are applied before compilation:

1. Change the app ID to `com.aistudio.mangareader.mihoncore` (debug APK gets a
   `.dev` suffix from upstream's unchanged Gradle setup). This is a **separate
   app**, not an in-place update of the existing Manga D APK.
2. Change the generated application label to `Manga D Core`.

Telemetry and in-app updates are disabled for this testing build via documented
Gradle properties. Official Mihon source, plugin build logic, reader, source
loader, networking, data layer and extension manager are all built together.
The original Mihon Apache-2.0 licence remains in the upstream source checkout.

## Test without risking the existing application

1. Open the `Manga D Core — upstream Mihon build` workflow in `dd/Actions`.
2. The new workflow should produce the artifact `manga-d-mihon-core-debug-apks`.
3. Install **arm64-v8a debug** or universal debug from the artifact as a **new
   application**. Do not uninstall the existing Manga D app.
4. Open the extension store, add/load a trusted source repository as needed,
   trust the extension in the official host flow, and test an already-installed
   extension (e.g. 3Hentai) on the test app. Browser protection, region and
   server failures remain possible, regardless of engine compatibility.
5. Check source catalogue, search, manga details, chapters, reader and image
   loading. Store screenshots or failure logs for phase 2.

This is a **phase-one foundation, not a completed migration**. The previous
Manga D UI, settings and database are not merged into this APK yet. Data from
the old app is **not migrated automatically**, because the app ID is separate.

## Next phases

- Phase 2: verify upstream extension support and network operation on phone.
- Phase 3: adapt Manga D's Arabic RTL UI and reader options to official Mihon
  screens and interfaces without replacing the host's source engine.
- Phase 4: implement an explicit, versioned export/import migration of library,
  history, downloads and settings. Never directly copy private app databases.
- Phase 5: run clean CI and device regression tests, then decide whether/when
  to replace the original Manga D package in a future migration release.

## Upstream provenance

- Source: https://github.com/mihonapp/mihon
- Pinned upstream commit: `8794e89a866a3de578c02cefac1e609bcff5f092`
- Upstream licence: https://github.com/mihonapp/mihon/blob/main/LICENSE
- This overlay should not be presented as an official Mihon release.
