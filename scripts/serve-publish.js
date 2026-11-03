const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(process.argv[2]);
const port = Number(process.argv[3] || 8765);
const types = { '.json': 'application/json; charset=utf-8', '.apk': 'application/vnd.android.package-archive', '.exe': 'application/vnd.microsoft.portable-executable', '.txt': 'text/plain; charset=utf-8' };

http.createServer((req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }
  let name;
  try { name = decodeURIComponent(new URL(req.url, 'http://localhost').pathname).replace(/^\/+/, ''); }
  catch { res.writeHead(400).end(); return; }
  const file = path.resolve(root, name);
  if (!file.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
  fs.stat(file, (error, info) => {
    if (error || !info.isFile()) { res.writeHead(404).end('Not found'); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Content-Length': info.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    if (req.method === 'HEAD') { res.end(); return; }
    fs.createReadStream(file).pipe(res);
  });
}).listen(port, '127.0.0.1');
