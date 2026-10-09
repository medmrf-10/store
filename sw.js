const C='irthstore-v2';
const PRECACHE=['./','./index.html','./apps.js','./apps.json','./manifest.webmanifest','./icons/icon-192.png','./icons/icon-512.png'];
const NETWORK_FIRST=[/\/$/,/\/index\.html$/,/apps\.(js|json)$/,/manifest\.webmanifest$/];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(PRECACHE)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET')return;
  const url=new URL(e.request.url);
  const fresh=NETWORK_FIRST.some(p=>p.test(url.pathname));
  if(fresh){e.respondWith(fetch(e.request).then(n=>{const cp=n.clone();if(n.ok)caches.open(C).then(c=>c.put(e.request,cp));return n}).catch(()=>caches.match(e.request)));return}
  e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request).then(n=>{const cp=n.clone();if(n.ok&&url.origin===location.origin)caches.open(C).then(c=>c.put(e.request,cp));return n})))
});
