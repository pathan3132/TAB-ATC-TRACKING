(function () {
  const cfg = window.TAB_CONFIG || {};

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // Apps Script ko CORS preflight se bachane ke liye Content-Type text/plain rakha hai.
  async function api(action, payload, opts) {
    if (!cfg.API_URL || cfg.API_URL.indexOf('PASTE_') === 0) {
      throw new Error('config.js mein API_URL set nahi hai.');
    }
    const ctrl = new AbortController();
    const timer = setTimeout(function () { ctrl.abort(); }, (opts && opts.timeout) || 25000);
    try {
      const res = await fetch(cfg.API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify(Object.assign({ action: action }, payload || {})),
        signal: ctrl.signal,
        redirect: 'follow'
      });
      return await res.json();
    } finally {
      clearTimeout(timer);
    }
  }

  function timeAgo(ms) {
    if (!ms) return '—';
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return s + ' sec pehle';
    const m = Math.floor(s / 60);
    if (m < 60) return m + ' min pehle';
    const h = Math.floor(m / 60);
    if (h < 48) return h + ' hr ' + (m % 60) + ' min pehle';
    return Math.floor(h / 24) + ' din pehle';
  }

  function fmtDT(ms) {
    if (!ms) return '—';
    return new Date(ms).toLocaleString('en-IN', {
      day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true
    });
  }

  function fmtDur(ms) {
    if (ms == null || !isFinite(ms)) return '—';
    const s = Math.round(ms / 1000);
    if (s < 60) return s + ' sec';
    const m = Math.floor(s / 60);
    if (m < 60) return m + ' min ' + (s % 60) + ' sec';
    return Math.floor(m / 60) + ' hr ' + (m % 60) + ' min';
  }

  function distKm(lat1, lng1, lat2, lng2) {
    const R = 6371, r = Math.PI / 180;
    const dLat = (lat2 - lat1) * r, dLng = (lng2 - lng1) * r;
    const a = Math.sin(dLat / 2) ** 2 +
      Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(a));
  }

  window.TAB = { cfg: cfg, esc: esc, api: api, timeAgo: timeAgo, fmtDT: fmtDT, fmtDur: fmtDur, distKm: distKm };
})();
