const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const code = fs.readFileSync(__dirname + '/../qari_manga/qari_manga_scan.js', 'utf8');
const good = (url, title, img) => ({
  href: url, title, textContent: title,
  querySelector: k => k === 'img' ? {alt: title} : null,
  closest: () => ({querySelector: k => k === 'img' ? {
    dataset: {src: img}, getAttribute: () => img, currentSrc: img,
  } : {textContent: title}}),
});
function run(url, contents, title='Arabic manga catalog') {
  const document = {
    title, body: {innerText: 'صفحة 1 من 42'},
    scrollingElement: {scrollTop: 0, scrollHeight: 900},
    querySelector: () => null,
    querySelectorAll: selector => selector.includes('img') ? [] : contents,
  };
  const scope = {location: new URL(url), URL, URLSearchParams, document, innerHeight: 900};
  vm.runInNewContext('var __value=' + code, scope);
  return JSON.parse(scope.__value);
}
const img = 'https://cdn.example.com/covers/a.jpg';
const card = good('https://arabshentai.com/manga/story-one/', 'Test Story', img);
const next = good('https://arabshentai.com/manga/page/2/', '2', null);
const nextQuery = good('https://arabshentai.com/manga/?paged=2', '2', null);
for (const type of [null, 'manhwa', 'doujinshi', 'comic']) {
  const url = 'https://arabshentai.com/manga/' + (type ? `?type=${type}&orderby=&state=` : '');
  const result = run(url, [card, next]);
  assert.equal(result.kind, 'archive');
  assert.equal(result.items.length, 1);
  assert.equal(result.total, 42);
  assert.equal(result.atBottom, true);
  const expected = 'https://arabshentai.com/manga/page/2/' + (type ? `?type=${type}` : '');
  assert.equal(result.nextUrl, expected, type || 'manga');
}
const queryResult = run('https://arabshentai.com/manga/?type=manhwa', [card, nextQuery]);
assert.equal(queryResult.nextUrl, 'https://arabshentai.com/manga/?paged=2&type=manhwa');
const challenge = run('https://arabshentai.com/manga/?type=comic', [card, next], 'Just a moment');
assert.equal(challenge.kind, 'challenge');
console.log('PASS: 4 sections, query pagination, bottom detection, challenge recognition');
