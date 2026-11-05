/* Extracts rendered, user-accessible DOM. Never touches Cloudflare tokens or cookies.
 * The Android app substitutes a fixed mode value before evaluating this script.
 */
(() => {
  "use strict";
  const mode = "__QARI_MODE__";
  // window.__qariMangaV2Order: segment-aware, persistent HTML image order.

  const here = new URL(location.href);
  const host = here.hostname.toLowerCase().replace(/^www\./, "");
  const result = { mode, url: here.href, items: [], chapters: [], pages: [], genres: [], next: "", current: 1, total: 0, blocked: false };
  if (host !== "arabshentai.com" || here.protocol !== "https:") return JSON.stringify(result);
  const text = value => (value || "").replace(/\s+/g, " ").trim();
  const absolute = raw => { try { return new URL(raw, here.href); } catch { return null; } };
  const sourceUrl = raw => {
    const parsed = absolute(raw);
    return parsed && parsed.protocol === "https:" && parsed.hostname.replace(/^www\./, "") === host ? parsed.href : "";
  };
  const isBlocked = /just a moment|checking your browser|تحقق أنك إنسان|التحقق من الأمان|جار.*التحقق/i.test(document.title + " " + document.body?.innerText?.slice(0, 600)) &&
    (!document.querySelector("main article, .page-item-detail, .c-tabs-item__content, .reading-content"));
  result.blocked = Boolean(isBlocked || document.querySelector("#challenge-form, #cf-challenge-running"));
  if (result.blocked) return JSON.stringify(result);

  // Dynamic reader themes can use src, data-src, srcset and placeholders.
  const firstImageUrl = el => {
    const img = el?.matches?.("img") ? el : el?.querySelector?.("img");
    if (!img) return "";
    const candidates = [
      img.getAttribute("data-src"), img.getAttribute("data-lazy-src"),
      img.getAttribute("data-original"), img.getAttribute("data-cfsrc"),
      img.getAttribute("data-srcset"), img.getAttribute("srcset"),
      img.currentSrc, img.getAttribute("src"),
    ];
    for (const raw of candidates) {
      if (!raw || raw.startsWith("data:") || raw.startsWith("blob:")) continue;
      const candidate = raw.split(",")[0].trim().split(/\s+/)[0];
      const link = absolute(candidate);
      if (link?.protocol === "https:") return link.href;
    }
    return "";
  };
  const seen = new Set();

  if (mode === "genres") {
    const names = [...document.querySelectorAll(
      "#archive-content ul.genre-list li.item-genre .genre-data a"
    )].map(a => text(a.textContent)).filter(Boolean);
    result.genres = [...new Set(names)].slice(0, 400);
  } else if (mode === "archive" || mode === "filter") {
    const cards = document.querySelectorAll(
      "#archive-content .wp-manga, .search-page .result-item article, " +
      ".page-item-detail, .c-tabs-item__content, .manga-item, .manga-card, " +
      ".post-on, .page-item, .item-thumb, .manga-list .item, .main-col article, article"
    );
    let elements = [...cards];
    if (!elements.length) {
      // Fallback for non-Madara sites: require BOTH a cover image and title-like text.
      elements = [...document.querySelectorAll("a[href]")].filter(a => a.querySelector("img"));
    }
    for (const card of elements) {
      const anchor = card.matches?.("a[href]") ? card :
        card.querySelector(".data h3 a[href], .details .title a[href], a[href][title], h3 a[href], h2 a[href], .post-title a[href], a[href]");
      if (!anchor) continue;
      const url = sourceUrl(anchor.href);
      if (!url || seen.has(url)) continue;
      const u = new URL(url);
      if (/\/manga\/?$/.test(u.pathname) || /\/manga\/page\/\d+/.test(u.pathname) ||
          /\/category\/|\/tag\/|\/login\/|\/register\//i.test(u.pathname)) continue;
      const title = text(card.querySelector(".data h3,.details .title,h3,h2,h4,.post-title,.manga-title,.title")?.textContent ||
        anchor.getAttribute("title") || card.querySelector("img")?.getAttribute("alt") || anchor.textContent).slice(0, 250);
      const cover = firstImageUrl(card) || firstImageUrl(anchor);
      if (title.length < 2 || !cover) continue;
      const categoryText = text(card.querySelector(".genres,.post-content_item,.manga-type,.type")?.textContent).toLowerCase();
      // The unfiltered /manga/ view is for manga; if the card advertises another
      // category, keep it out of that category's dedicated list.
      if (mode === "archive" && !here.searchParams.get("type") && /manhwa|manhua|doujinshi|comic|مانهوا|دوجينشي|كوميكس/.test(categoryText)) continue;
      seen.add(url);
      result.items.push({ url, title, cover });
      if (result.items.length >= 500) break;
    }

    const pageFromUrl = u => {
      const pathname = u.pathname.match(/\/page\/(\d+)\/?$/i);
      const query = Number(u.searchParams.get("paged") || u.searchParams.get("page"));
      return pathname ? Number(pathname[1]) : (query > 0 ? query : 1);
    };
    result.current = pageFromUrl(here);
    result.total = result.current;
    const links = [...document.querySelectorAll("a[href]")];
    const candidatePages = [];
    for (const a of links) {
      const url = sourceUrl(a.href);
      if (!url) continue;
      const p = new URL(url);
      if (!/^\/(?:manga(?:\/(?:page\/\d+\/?)?)?|page\/\d+\/?)?$/i.test(p.pathname)) continue;
      const page = pageFromUrl(p);
      const label = text(a.textContent || a.getAttribute("aria-label"));
      const numeric = /^\d+$/.test(label) ? Number(label) : 0;
      result.total = Math.max(result.total, page, numeric);
      if (page > result.current && page < 600) candidatePages.push({ url, page });
    }
    const footer = text(document.body?.innerText?.slice(-1000));
    const match = footer.match(/(?:صفحة|page)\s*(\d+)\s*(?:من|of)\s*(\d+)/i);
    if (match) result.total = Math.max(result.total, Number(match[2]));
    candidatePages.sort((a, b) => a.page - b.page);
    result.next = candidatePages[0]?.url || "";
    if (result.total > 500) result.total = 500;
    // Scroll through lazy-loaded catalog cards without losing earlier results.
    const archiveKey = here.origin + here.pathname + here.search;
    let archiveScan = window.__qariMangaArchiveScan;
    if (!archiveScan || archiveScan.key !== archiveKey) {
      archiveScan = { key: archiveKey, cards: {}, order: [], stable: 0, passes: 0, lastSize: -1 };
      window.__qariMangaArchiveScan = archiveScan;
    }
    for (const card of result.items) {
      if (!archiveScan.cards[card.url]) archiveScan.order.push(card.url);
      archiveScan.cards[card.url] = card;
    }
    result.items = archiveScan.order.map(url => archiveScan.cards[url]).slice(0, 500);
    const archiveScroller = document.scrollingElement || document.documentElement;
    const viewport = Math.max(window.innerHeight || 0, 1);
    const bodyHeight = Math.max(archiveScroller?.scrollHeight || 0, document.body?.scrollHeight || 0);
    const scrollTop = archiveScroller?.scrollTop || window.scrollY || 0;
    const archiveBottom = scrollTop + viewport + 15 >= bodyHeight;
    archiveScan.passes++;
    if (archiveBottom && result.items.length === archiveScan.lastSize) archiveScan.stable++;
    else archiveScan.stable = 0;
    archiveScan.lastSize = result.items.length;
    if (!archiveBottom && archiveScan.passes < 360) {
      window.scrollTo(0, Math.min(bodyHeight, scrollTop + Math.max(280, viewport * 1.15)));
    }
    result.pending = (!archiveBottom || archiveScan.stable < 2) && archiveScan.passes < 360;
    result.incomplete = !archiveBottom && archiveScan.passes >= 360;
  } else if (mode === "detail") {
    const chapterNodes = document.querySelectorAll(
      "#chapter-list a[href*='/manga/'], .oneshot-reader .image-item a[href], " +
      ".wp-manga-chapter a[href], .listing-chapters_wrap a[href], .chapter-list a[href], " +
      ".chapters a[href], .main.version-chap a[href], a[href*='chapter-'], a[href*='/chapter/']"
    );
    for (const a of chapterNodes) {
      const url = sourceUrl(a.href);
      if (!url || seen.has(url) || url === here.href) continue;
      const title = text(a.querySelector(".chapternum")?.textContent ||
        a.textContent || a.getAttribute("title") || a.parentElement?.textContent).slice(0, 220) ||
        "الفصل " + (result.chapters.length + 1);
      seen.add(url);
      result.chapters.push({ url, title, position: result.chapters.length });
      if (result.chapters.length >= 2500) break;
    }
    if (!result.chapters.length && document.querySelector(".oneshot-reader")) {
      result.chapters.push({ url: here.href, title: "ونشوت", position: 0 });
    }
  } else if (mode === "chapter") {
    // Each page is a stable DOM snapshot; lazy-loaded images may appear only
    // after scrolling. Retain order and unique image URLs across JS calls.
    const key = here.origin + here.pathname + here.search;
    let scan = window.__qariMangaChapterScan;
    if (!scan || scan.key !== key) {
      scan = { key, urls: [], passes: 0, stable: 0, lastSize: -1 };
      window.__qariMangaChapterScan = scan;
    }
    const imgSelector =
      ".chapter_image img.wp-manga-chapter-img,.oneshot-reader .image-item img," +
      ".reading-content img,.page-break img,#readerarea img,.chapter-content img," +
      ".reader img,.rdminimal img,.chapter-page img,img[data-src],img[data-lazy-src]";
    // Merge each DOM snapshot into the stable list. A late image in front of
    // previously known images is inserted before the next known DOM anchor.
    const visible = [];
    const observed = new Set();
    for (const img of document.querySelectorAll(imgSelector)) {
      const address = firstImageUrl(img);
      if (!address || observed.has(address) || /logo|avatar|icon|banner|thumb/i.test(address)) continue;
      observed.add(address);
      visible.push(address);
      if (visible.length >= 5000) break;
    }
    try {
      const inline = [...document.scripts].map(s => s.textContent || "")
        .find(s => /const\s+images\s*=\s*\[/.test(s));
      const match = inline?.match(/const\s+images\s*=\s*(\[[\s\S]*?\]);/);
      if (match) {
        const entries = JSON.parse(match[1]);
        for (const entry of entries.slice(0, 5000)) {
          const decoded = atob(String(entry.url || ""));
          const link = absolute(decoded);
          if (link?.protocol === "https:" && !observed.has(link.href)) {
            observed.add(link.href);
            visible.push(link.href);
          }
        }
      }
    } catch (_) { /* Some themes do not embed an inline image list. */ }
    for (let i = 0; i < visible.length; i++) {
      const image = visible[i];
      if (scan.urls.includes(image)) continue;
      const next = visible.slice(i + 1).find(url => scan.urls.includes(url));
      if (next) scan.urls.splice(scan.urls.indexOf(next), 0, image);
      else {
        const prior = visible.slice(0, i).reverse().find(url => scan.urls.includes(url));
        if (prior) scan.urls.splice(scan.urls.indexOf(prior) + 1, 0, image);
        else scan.urls.push(image);
      }
    }
    result.pages = scan.urls.map((url, position) => ({url, position}));

    const scroller = document.scrollingElement || document.documentElement;
    const viewHeight = Math.max(window.innerHeight || 0, 1);
    const height = Math.max(scroller?.scrollHeight || 0, document.body?.scrollHeight || 0);
    const top = scroller?.scrollTop || window.scrollY || 0;
    const atBottom = (top + viewHeight + 15 >= height);
    scan.passes++;
    if (scan.urls.length === scan.lastSize && atBottom) scan.stable++;
    else scan.stable = 0;
    scan.lastSize = scan.urls.length;
    if (!atBottom && scan.passes < 600) {
      window.scrollTo(0, Math.min(height, top + Math.max(280, viewHeight * 1.15)));
    }
    result.pending = (!atBottom || scan.stable < 3) && scan.passes < 600;
    result.incomplete = !atBottom && scan.passes >= 600;

    // Reader themes may split a chapter into multiple same-path HTML pages.
    // Navigate only URLs for this exact chapter (never the next chapter).
    const currentNumber = Number(here.searchParams.get("page") || here.searchParams.get("paged") || 1);
    const chapterNext = [];
    for (const a of document.querySelectorAll(
      ".pagination a[href],.page-links a[href],a[rel='next'],a.next-page[href]"
    )) {
      const candidate = absolute(a.href);
      if (!candidate || candidate.protocol !== "https:" || candidate.hostname !== here.hostname) continue;
      if (candidate.pathname.replace(/\/$/, "") !== here.pathname.replace(/\/$/, "")) continue;
      const n = Number(candidate.searchParams.get("page") || candidate.searchParams.get("paged") || 0);
      if (n > currentNumber && n < 3001) chapterNext.push({ n, url: candidate.href });
    }
    chapterNext.sort((a, b) => a.n - b.n);
    result.next = chapterNext[0]?.url || "";
  }
  return JSON.stringify(result);
})()
