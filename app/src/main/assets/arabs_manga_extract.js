/* Extracts rendered, user-accessible DOM. Never touches Cloudflare tokens or cookies.
 * The Android app substitutes a fixed mode value before evaluating this script.
 */
(() => {
  "use strict";
  const mode = "__QARI_MODE__";
  const here = new URL(location.href);
  const host = here.hostname.toLowerCase().replace(/^www\./, "");
  const result = { mode, url: here.href, items: [], chapters: [], pages: [], next: "", current: 1, total: 0, blocked: false };
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

  const firstImageUrl = el => {
    const img = el?.matches?.("img") ? el : el?.querySelector?.("img");
    if (!img) return "";
    let raw = img.getAttribute("data-src") || img.getAttribute("data-lazy-src") ||
      img.getAttribute("data-original") || img.getAttribute("data-cfsrc") ||
      img.getAttribute("data-srcset") || img.currentSrc || img.getAttribute("src") || "";
    raw = raw.split(",")[0].trim().split(/\s+/)[0];
    const url = absolute(raw);
    return url?.protocol === "https:" ? url.href : "";
  };
  const seen = new Set();

  if (mode === "archive") {
    const cards = document.querySelectorAll(
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
        card.querySelector("a[href][title], h3 a[href], h2 a[href], .post-title a[href], a[href]");
      if (!anchor) continue;
      const url = sourceUrl(anchor.href);
      if (!url || seen.has(url)) continue;
      const u = new URL(url);
      if (/\/manga\/?$/.test(u.pathname) || /\/manga\/page\/\d+/.test(u.pathname) ||
          /\/category\/|\/tag\/|\/login\/|\/register\//i.test(u.pathname)) continue;
      const title = text(card.querySelector("h3,h2,h4,.post-title,.manga-title,.title")?.textContent ||
        anchor.getAttribute("title") || card.querySelector("img")?.getAttribute("alt") || anchor.textContent).slice(0, 250);
      const cover = firstImageUrl(card) || firstImageUrl(anchor);
      if (title.length < 2 || !cover) continue;
      const categoryText = text(card.querySelector(".genres,.post-content_item,.manga-type,.type")?.textContent).toLowerCase();
      // The unfiltered /manga/ view is for manga; if the card advertises another
      // category, keep it out of that category's dedicated list.
      if (!here.searchParams.get("type") && /manhwa|manhua|doujinshi|comic|مانهوا|دوجينشي|كوميكس/.test(categoryText)) continue;
      seen.add(url);
      result.items.push({ url, title, cover });
      if (result.items.length >= 150) break;
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
      if (!/^\/manga(?:\/page\/\d+\/?)?$/i.test(p.pathname)) continue;
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
  } else if (mode === "detail") {
    const chapterNodes = document.querySelectorAll(
      ".wp-manga-chapter a[href], .listing-chapters_wrap a[href], .chapter-list a[href], " +
      ".chapters a[href], .main.version-chap a[href], a[href*='chapter-'], a[href*='/chapter/']"
    );
    for (const a of chapterNodes) {
      const url = sourceUrl(a.href);
      if (!url || seen.has(url) || url === here.href) continue;
      const title = text(a.textContent || a.getAttribute("title") || a.parentElement?.textContent).slice(0, 220);
      if (title.length < 2) continue;
      seen.add(url);
      result.chapters.push({ url, title, position: result.chapters.length });
      if (result.chapters.length >= 2500) break;
    }
  } else if (mode === "chapter") {
    const imgs = document.querySelectorAll(
      ".reading-content img, .page-break img, #readerarea img, .chapter-content img, " +
      ".reader img, .rdminimal img, .chapter-page img, img[data-src], img[data-lazy-src]"
    );
    for (const img of imgs) {
      const url = firstImageUrl(img);
      if (!url || seen.has(url) || /logo|avatar|icon|banner|thumb/i.test(url)) continue;
      seen.add(url);
      result.pages.push({ url, position: result.pages.length });
      if (result.pages.length >= 3000) break;
    }
    // Some reader themes split one chapter across several HTML pages.
    // Follow only pagination URLs for the SAME chapter, not the next chapter.
    const currentNumber = Number(here.searchParams.get("page") || here.searchParams.get("paged") || 1);
    const chapterNext = [];
    for (const a of document.querySelectorAll(
      ".pagination a[href], .page-links a[href], a[rel='next'], a.next-page[href]"
    )) {
      const candidate = absolute(a.href);
      if (!candidate || candidate.protocol !== "https:" || candidate.hostname !== here.hostname) continue;
      if (candidate.pathname.replace(/\/$/, "") !== here.pathname.replace(/\/$/, "")) continue;
      const n = Number(candidate.searchParams.get("page") || candidate.searchParams.get("paged") || 0);
      if (n > currentNumber && n < 3001) chapterNext.push({n, url: candidate.href});
    }
    chapterNext.sort((a,b)=>a.n-b.n);
    result.next = chapterNext[0]?.url || "";
  }
  return JSON.stringify(result);
})()
