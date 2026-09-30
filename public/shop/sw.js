/*
 * Kali Mata Variety Store (কালী মাতা ভ্যারাইটি স্টোর) - Service Worker
 * Stale-While-Revalidate App Shell & Catalog Image Cache
 * Strictly skips all payment, UPI, QR, Khata, and account endpoints.
 */

const SHELL_CACHE_NAME = 'kmvs-shell-v3';
const IMAGE_CACHE_NAME = 'kmvs-images-v2';
const MAX_IMAGE_ENTRIES = 60;

// Core static assets for instant offline app shell rendering
const APP_SHELL_URLS = [
  '/shop/',
  '/shop/index.html',
  '/shop/manifest.json',
  '/shop/icons/icon-192.png',
  '/shop/icons/icon-512.png',
  '/shop/icons/icon.svg',
  '/shop/icons/apple-touch-icon.png',
  'https://www.gstatic.com/firebasejs/10.8.0/firebase-app-compat.js',
  'https://www.gstatic.com/firebasejs/10.8.0/firebase-auth-compat.js',
  'https://www.gstatic.com/firebasejs/10.8.0/firebase-firestore-compat.js',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'
];

// URLs that MUST NEVER be cached (real-time payment, balance, Khata, orders, auth, QR)
const NEVER_CACHE_PATTERNS = [
  /\/khata\b/i,
  /\/pay\b/i,
  /upi/i,
  /qrcode/i,
  /qr\b/i,
  /balance/i,
  /customer_accounts/i,
  /orders\b/i,
  /order_refs/i,
  /track\.html/i,
  /payment/i,
  /checkout/i,
  /identitytoolkit/i,
  /securetoken/i,
  /firestore\.googleapis\.com/i,
  /\/__\/firebase/i
];

function isNeverCacheUrl(urlStr) {
  return NEVER_CACHE_PATTERNS.some(regex => regex.test(urlStr));
}

// Helper to trim image cache to prevent device storage overload
async function trimCache(cacheName, maxEntries) {
  try {
    const cache = await caches.open(cacheName);
    const keys = await cache.keys();
    if (keys.length > maxEntries) {
      await cache.delete(keys[0]);
      await trimCache(cacheName, maxEntries);
    }
  } catch (err) {
    // Ignore trim errors gracefully
  }
}

// 1. Install Event: Pre-cache App Shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE_NAME).then(async (cache) => {
      console.log('[SW] Pre-caching App Shell assets...');
      // Use Promise.allSettled so an optional CDN failure doesn't break installation
      const promises = APP_SHELL_URLS.map(async (url) => {
        try {
          const res = await fetch(url, { mode: url.startsWith('http') && !url.includes(self.location.hostname) ? 'cors' : 'same-origin' });
          if (res.ok) {
            await cache.put(url, res);
          }
        } catch (err) {
          console.warn('[SW] Pre-cache skipped for:', url, err);
        }
      });
      await Promise.allSettled(promises);
    }).then(() => self.skipWaiting())
  );
});

// 2. Activate Event: Clean up old cache versions & claim clients
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((name) => {
          if (name !== SHELL_CACHE_NAME && name !== IMAGE_CACHE_NAME) {
            console.log('[SW] Purging legacy cache:', name);
            return caches.delete(name);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// 3. Fetch Event
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = request.url;

  // Never intercept non-GET requests (e.g. POST, PUT, DELETE for orders)
  if (request.method !== 'GET') {
    return;
  }

  // Strictly skip all payment, UPI, QR, Khata, balance, or order requests
  if (isNeverCacheUrl(url)) {
    return; // Pass through directly to network
  }

  // Handle Product Images (Cache-first with Stale-While-Revalidate and size cap)
  const isImage = request.destination === 'image' || 
                  /\.(png|jpg|jpeg|webp|gif|svg|avif)(\?.*)?$/i.test(url) ||
                  url.includes('firebasestorage.googleapis.com');

  if (isImage) {
    event.respondWith(
      caches.open(IMAGE_CACHE_NAME).then(async (cache) => {
        const cachedRes = await cache.match(request);
        
        // Fetch from network to revalidate / cache fresh
        const networkFetch = fetch(request).then(async (networkRes) => {
          if (networkRes && (networkRes.status === 200 || networkRes.type === 'opaque')) {
            try {
              await cache.put(request, networkRes.clone());
              trimCache(IMAGE_CACHE_NAME, MAX_IMAGE_ENTRIES);
            } catch (putErr) {
              // quota exceeded or opaque put error
            }
          }
          return networkRes;
        }).catch((err) => {
          // Network failed for image
          return null;
        });

        // Return cached image immediately if present, otherwise await network
        if (cachedRes) {
          return cachedRes;
        }

        const freshRes = await networkFetch;
        if (freshRes) {
          return freshRes;
        }

        // Offline image fallback (return 1x1 transparent PNG or empty SVG)
        return new Response(
          '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100"><rect width="100" height="100" fill="#F1F5F9"/><text x="50" y="55" font-size="24" text-anchor="middle" fill="#94A3B8">📦</text></svg>',
          { headers: { 'Content-Type': 'image/svg+xml' } }
        );
      })
    );
    return;
  }

  // Handle App Shell & Navigation (Network-First for HTML to ensure live updates)
  const isNavigate = request.mode === 'navigate' || 
                     (request.headers.get('accept') && request.headers.get('accept').includes('text/html'));
  if (isNavigate) {
    event.respondWith(
      fetch(request).then(async (networkResponse) => {
        if (networkResponse && networkResponse.status === 200) {
          try {
            const cache = await caches.open(SHELL_CACHE_NAME);
            await cache.put(request, networkResponse.clone());
          } catch (e) {}
        }
        return networkResponse;
      }).catch(async () => {
        const cache = await caches.open(SHELL_CACHE_NAME);
        const cached = await cache.match(request) || await cache.match('/shop/index.html') || await cache.match('/shop/');
        if (cached) return cached;
        return new Response('Offline: Kali Mata Variety Store catalog is available from cache.', {
          status: 503,
          headers: { 'Content-Type': 'text/plain; charset=utf-8' }
        });
      })
    );
    return;
  }

  // Handle Static Assets (Stale-While-Revalidate)
  event.respondWith(
    caches.open(SHELL_CACHE_NAME).then(async (cache) => {
      const cachedResponse = await cache.match(request);

      const fetchPromise = fetch(request).then(async (networkResponse) => {
        if (networkResponse && networkResponse.status === 200) {
          try {
            await cache.put(request, networkResponse.clone());
          } catch (e) {
            // cache put error
          }
        }
        return networkResponse;
      }).catch(() => null);

      if (cachedResponse) {
        return cachedResponse;
      }

      const freshResponse = await fetchPromise;
      if (freshResponse) {
        return freshResponse;
      }

      return new Response('Offline resource unavailable', { status: 503 });
    })
  );
});
