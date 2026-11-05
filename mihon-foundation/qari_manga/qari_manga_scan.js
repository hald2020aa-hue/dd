(() => {
  'use strict';
  const root = location.href;
  const here = new URL(root);
  const host = here.hostname.toLowerCase();
  if (here.protocol !== 'https:' || !['arabshentai.com', 'www.arabshentai.com'].includes(host)) {
    return JSON.stringify({kind:'invalid',url:root});
  }
  const title = document.title || '';
  if (!document.body || /just a moment|checking your browser|لحظة|جار.? التحقق/i.test(title) ||
      document.querySelector('#challenge-form,#cf-challenge-running')) {
    return JSON.stringify({kind:'challenge',url:root});
  }
  const norm = (s) => (s || '').replace(/\s+/g,' ').trim();
  const parsed = (raw) => { try { return new URL(raw,root); } catch { return null; } };
  const allowed = (url) => url && url.protocol === 'https:' &&
    ['arabshentai.com','www.arabshentai.com'].includes(url.hostname.toLowerCase());
  const rawType = new URLSearchParams(here.search).get('type');
  const path = decodeURIComponent(here.pathname).toLowerCase();
  const isArchive = /^\/manga\/?$/.test(path) || /^\/manga\/page\/\d+\/?$/.test(path);
  const candidates = (selector) => [...document.querySelectorAll(selector)];
  const imageOf = (card) => {
    const img = card?.querySelector?.('img');
    if (!img) return '';
    const link = parsed(img.dataset?.src || img.dataset?.original || img.dataset?.lazySrc ||
      img.currentSrc || img.getAttribute('src') || '');
    return link && link.protocol === 'https:' ? link.href : '';
  };
  if (isArchive) {
    const result = new Map();
    for (const a of candidates('article a[href],.item a[href],.post a[href],.manga a[href],.card a[href],a[href]')) {
      const link = parsed(a.href);
      if (!allowed(link)) continue;
      const target = decodeURIComponent(link.pathname).toLowerCase();
      if (target === '/manga/' || target.startsWith('/manga/page/') ||
          /\/page\/[0-9]+\/?$/.test(target) || /\/category\//.test(target)) continue;
      const card = a.closest('article,.page-item-detail,.c-tabs-item__content,.item,.post,.manga,.card,.poster,.manga-item') || a;
      const cover = imageOf(card) || imageOf(a);
      if (!cover) continue;
      const name = norm(card.querySelector('h2,h3,.title,.name,.entry-title,.post-title')?.textContent ||
        a.title || a.querySelector('img')?.alt || a.textContent || '');
      if (name.length < 2 || name.length > 250) continue;
      link.hash = '';
      result.set(link.href,{url:link.href,title:name,cover});
      if (result.size >= 120) break;
    }
    let current = Number(path.match(/\/page\/(\d+)/)?.[1] || here.searchParams.get('paged') || here.searchParams.get('page') || 1);
    let total = current, nextUrl = '';
    const nav = candidates('a[href]');
    const upcoming = [];
    for (const anchor of nav) {
      const link = parsed(anchor.href);
      if (!allowed(link)) continue;
      const p = decodeURIComponent(link.pathname).toLowerCase();
      if (p !== '/manga/' && !/^\/manga\/page\/\d+\/?$/.test(p)) continue;
      const n = Number(p.match(/\/page\/(\d+)/)?.[1] || link.searchParams.get('paged') || link.searchParams.get('page') || 1);
      const label = Number(norm(anchor.textContent));
      if (Number.isFinite(label) && label > 0) total = Math.max(total,label);
      if (n > current && n < 5000) {
        // Preserve the site category when a pagination link omits the type.
        if (rawType && !link.searchParams.has('type')) link.searchParams.set('type',rawType);
        if (rawType && link.searchParams.get('type') !== rawType) continue;
        upcoming.push({n,url:link.href});
      }
    }
    const text = document.body.innerText.match(/(?:page|صفحة)\s*[0-9]+\s*(?:of|من)\s*([0-9]+)/i);
    if (text) total = Math.max(total,Number(text[1]));
    upcoming.sort((a,b) => a.n - b.n);
    nextUrl = upcoming[0]?.url || '';
    const scroller = document.scrollingElement || document.documentElement;
    const atBottom = scroller.scrollTop + innerHeight >= scroller.scrollHeight - 60;
    return JSON.stringify({kind:'archive',url:root,section:rawType,items:[...result.values()],
      current,total,nextUrl,atBottom});
  }
  // Chapters are recognized from visible chapter-like links, not guessed.
  const titleUrl = path;
  const chapterLinks = new Map();
  for (const a of candidates('a[href]')) {
    const link = parsed(a.href);
    if (!allowed(link) || link.href === root) continue;
    const name = norm(a.textContent || a.title);
    if (!name || name.length > 200) continue;
    if (!/(chapter|chap-|الفصل|فصل|chapter|ch\.|episode|جزء|part|اقرأ)/i.test(name+' '+link.pathname)) continue;
    if (link.pathname === here.pathname) continue;
    chapterLinks.set(link.href,{url:link.href,title:name});
    if (chapterLinks.size >= 500) break;
  }
  const images = [], used = new Set();
  const selectors = '.reading-content img,.chapter-content img,.reader img,.page-break img,'+
    '#chapter_images img,.entry-content img,.post-content img,article img';
  const imgs = candidates(selectors);
  const base = imgs.length ? imgs : candidates('img');
  base.forEach((img,i) => {
    const raw = img.dataset?.src || img.dataset?.original || img.dataset?.lazySrc ||
      img.dataset?.url || img.currentSrc || img.getAttribute('src');
    const link = parsed(raw);
    if (!link || link.protocol !== 'https:' || used.has(link.href)) return;
    if (img.closest('header,footer,nav,.sidebar,.ads,.advertisement')) return;
    if (/logo|avatar|icon|banner|emoji|sprite|ads/i.test(link.pathname)) return;
    if (img.naturalWidth > 0 && img.naturalWidth < 180) return;
    used.add(link.href);
    images.push({url:link.href,position:i});
  });
  let nextHtml = '';
  for (const a of candidates('.page-numbers a[href],.chapter-pagination a[href],a[rel="next"][href]')) {
    const link = parsed(a.href);
    if (!allowed(link) || link.href === root) continue;
    // A next chapter is not the next HTML page of the current chapter.
    const samePath = link.pathname.replace(/\/$/,'') === here.pathname.replace(/\/$/,'');
    const hasPageParam = link.searchParams.has('page') || link.searchParams.has('paged');
    if (samePath && hasPageParam) { nextHtml = link.href; break; }
  }
  const scroll = document.scrollingElement || document.documentElement;
  const bottom = scroll.scrollTop + innerHeight >= scroll.scrollHeight - 50;
  const summary = norm(document.querySelector('.summary,.description,.entry-content p,.post-content p')?.textContent);
  return JSON.stringify({kind:chapterLinks.size ? 'details':'images',url:root,description:summary,
    chapters:[...chapterLinks.values()],images,atBottom:bottom,scrollHeight:scroll.scrollHeight,nextHtml});
})()
