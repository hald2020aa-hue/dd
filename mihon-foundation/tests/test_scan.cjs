const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const code = fs.readFileSync(__dirname+'/../qari_manga/qari_manga_scan.js','utf8');
const good = (url, title, img) => ({
  href:url, title, textContent:title,
  querySelector: k => k==='img'?{alt:title}:null,
  closest:()=>({querySelector:k=>k==='img'?{
    dataset:{src:img},getAttribute:()=>img,currentSrc:img
  }:{textContent:title}}),
});
function run(url, contents) {
 const loc=new URL(url), elems=contents;
 const document={title:'Arabic manga catalog',body:{innerText:'صفحة 1 من 42'},
  querySelector:()=>null,
  querySelectorAll:(s)=>s.includes('img')?[]:elems};
 const scope={location:loc,URL,URLSearchParams,document,innerHeight:900};
 vm.runInNewContext('var __value='+code,scope);
 return JSON.parse(scope.__value);
}
let img='https://cdn.example.com/covers/a.jpg';
let first=good('https://arabshentai.com/manga/series-one','Series One',img);
let next=good('https://arabshentai.com/manga/page/2/','2',null);
let response=run('https://arabshentai.com/manga/?type=manhwa',[first,next]);
assert.equal(response.kind,'archive');
assert.equal(response.items.length,1);
assert.equal(response.total,42);
assert.equal(response.nextUrl,'https://arabshentai.com/manga/page/2/');
console.log('Manga D Mihon JS archive pagination smoke test passed');
