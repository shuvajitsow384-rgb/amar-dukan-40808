const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.DEFAULT_APP_PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf'
};

function serveFile(filePath, res, statusCode = 200) {
  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('404 Not Found');
      return;
    }

    res.writeHead(statusCode, {
      'Content-Type': contentType,
      'Content-Length': stats.size,
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-cache, no-store, must-revalidate'
    });

    const stream = fs.createReadStream(filePath);
    stream.pipe(res);
  });
}

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = decodeURIComponent(parsedUrl.pathname);

  // Normalize path
  let safePath = path.normalize(pathname).replace(/^(\.\.[\/\\])+/, '');
  let fullPath = path.join(PUBLIC_DIR, safePath);

  // Check if direct file exists
  if (fs.existsSync(fullPath)) {
    const stat = fs.statSync(fullPath);
    if (stat.isDirectory()) {
      const indexFile = path.join(fullPath, 'index.html');
      if (fs.existsSync(indexFile)) {
        serveFile(indexFile, res);
        return;
      }
    } else {
      serveFile(fullPath, res);
      return;
    }
  }

  // SPA rewrites adhering to firebase.json
  if (pathname.startsWith('/shop/track')) {
    const trackFile = path.join(PUBLIC_DIR, 'shop', 'track.html');
    if (fs.existsSync(trackFile)) {
      serveFile(trackFile, res);
      return;
    }
  }

  if (pathname.startsWith('/shop/')) {
    const shopIndex = path.join(PUBLIC_DIR, 'shop', 'index.html');
    if (fs.existsSync(shopIndex)) {
      serveFile(shopIndex, res);
      return;
    }
  }

  if (pathname.startsWith('/khata/')) {
    const khataIndex = path.join(PUBLIC_DIR, 'khata', 'index.html');
    if (fs.existsSync(khataIndex)) {
      serveFile(khataIndex, res);
      return;
    }
  }

  if (pathname.startsWith('/pay/')) {
    const payIndex = path.join(PUBLIC_DIR, 'pay', 'index.html');
    if (fs.existsSync(payIndex)) {
      serveFile(payIndex, res);
      return;
    }
  }

  const rootIndex = path.join(PUBLIC_DIR, 'index.html');
  if (fs.existsSync(rootIndex)) {
    serveFile(rootIndex, res);
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('404 Not Found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Web Storefront server running on http://0.0.0.0:${PORT}`);
});
