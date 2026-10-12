# TAB Solutions – Link-Based Driver Tracking (v1)

Google Sheets + Apps Script (backend) + static HTML (frontend, HTTPS hosting par).

## Files

| File | Kaam |
|---|---|
| `Code.gs` | Backend API + Google Sheets database (Apps Script) |
| `config.js` | API URL, ping interval, stale time |
| `common.js` | Shared helpers (API call, time format) |
| `style.css` | Dono pages ki styling |
| `index.html` | Site root se admin login par bhejta hai |
| `android/` | Android app source (background location), `android/ANDROID_README.md` dekhein |
| `admin.html` | Admin dashboard: login, new trip, live map, trips, route history |
| `track.html` | Driver page (WhatsApp link se khulta hai) |

## Setup (≈ 30–45 minute)

### 1. Backend
1. Google Drive mein nayi **Google Sheet** banayein (naam: `TAB Tracking DB`).
2. **Extensions → Apps Script** kholein. `Code.gs` ka poora code paste karein, save karein.
3. **Project Settings (⚙) → Script properties → Add**: `ADMIN_PASSWORD` = aapka strong password (12+ characters).
4. Editor mein function `setup` select karke **Run** karein, permissions Allow karein. (`Trips` aur `Locations` sheets ban jayengi.)
5. **Deploy → New deployment → Web app**
   - Execute as: **Me**
   - Who has access: **Anyone** (driver ke phone par Google login nahi hoga)
   - Deploy karke `…/exec` wala **Web app URL** copy karein.
6. Code change karne par hamesha **Deploy → Manage deployments → Edit → New version** karein, warna purana code chalta rahega.

### 2. Frontend
1. `config.js` mein `API_URL` ki jagah copy kiya hua URL paste karein.
2. Poora folder kisi **HTTPS static hosting** par upload karein (GPS ke liye HTTPS zaruri hai):
   - Netlify (drag & drop folder), Cloudflare Pages, ya GitHub Pages — teeno free.
   - Apna domain chahiye to e.g. `track.tabsolutions.in` connect kar sakte hain.
3. `https://<aapki-site>/admin.html` kholein → password se login.

### 3. Pehla trip
1. **New trip** tab → details bharein → **Trip create karein**.
2. **WhatsApp par driver ko bhejein** dabayein (wa.me khulega, message ready hoga).
3. Driver link kholkar Location **Allow** kare → **Live map** par gaadi dikhne lagegi.

## Kaun kya control karta hai

- **Driver:** sirf link kholta hai aur location allow karta hai. Uske paas Stop / Complete button **nahi** hai.
- **Admin:** Live map ya Trips table se **Tracking band / chalu** aur **Trip complete** dabata hai.
  Driver ka page / app agle ping par khud band ho jata hai. Tracking dobara chalu karne par khud shuru ho jata hai.
- Delivered trip dobara kholni ho to status dropdown se `In Transit` / `Loading` chunein.

> Backend update ke baad Apps Script mein **Deploy → Manage deployments → Edit → New version → Deploy** zaroor karein.

## Security design

- Har trip ka token 40-char random hai (≈160 bit). Link sirf `track.html?t=<token>`. Token se sirf wahi trip ka data + wahi trip ki location write hoti hai.
- Driver ko freight, party mobile jaisi commercial details **nahi** dikhtin.
- Admin API har request par signed session (HMAC, 12 ghante) check karti hai. Password sirf Script Properties mein hai, code ya sheet mein nahi.
- 5 galat password ke baad 15 minute lock (brute-force se bachav).
- Delivered trip ka token location accept nahi karta. Link leak ho to **Naya link** se purana token turant band.
- Sheet mein formula-injection se bachav (`=`, `+`, `-`, `@` se shuru text neutralise).
- Google Sheet ko sirf zaruri logon ke saath share karein: wahan tokens aur mobile numbers hain.

## Practical limits (v1)

- Apps Script ~0.5–1.5 sec/request leta hai; 30 simultaneous executions ki limit hai. Sheet + 30 sec interval par **~20–30 gaadiyan** comfortable; usse zyada ho to Supabase/Firebase par migrate karein.
- `Locations` sheet mein har ping ek row hai (10 lakh+ rows tak chalega, par History load slow hoga). Mahine mein ek baar purani trips ke rows archive kar dein.
- Free Google account ki daily quotas hoti hain; zyada load par Google Workspace account behtar hai.
- OpenStreetMap ke free tiles light/internal use ke liye theek hain; heavy use par MapTiler / Mapbox / Google Maps key lein.

## Testing plan

### A. Functional (browser, 30 min)
1. Wrong password → error; 5 baar galat → lock message.
2. Trip create → Sheet mein row dikhe, status `Loading`.
3. Link kisi doosre phone par kholein → trip details sahi; location allow → status auto `In Transit`.
4. Admin map par marker, "kitne sec pehle", accuracy circle.
5. Link ka ek character badal kar kholein → "Link sahi nahi hai".
6. Trip A ka link kholkar location bhejein → Trip B ki row/location par koi asar nahi.
7. **Sharing roken** → admin par tracking `Stopped`. **Trip complete** → status `Delivered`, link dobara kholne par "Trip complete".
8. **Naya link** → purana link invalid.
9. Airplane mode 2 minute → wapas net → pending points apne aap upload.

### B. Background / lock-screen test (zaruri – real phones par)
Har phone/browser par **30 min** ka test, route History ka **"Sabse bada gap"** aur **"Gaps"** stat dekhein.

| # | Scenario | Dekhna |
|---|---|---|
| 1 | Chrome Android, screen ON, page foreground | Gap ≈ interval |
| 2 | Chrome Android, screen lock | Gap kab shuru hota hai |
| 3 | Chrome Android, WhatsApp/Maps par switch | Gap |
| 4 | Samsung Internet / Mi Browser / Vivo / Oppo (battery saver ON) | Gap |
| 5 | iPhone Safari, foreground | Gap |
| 6 | iPhone Safari, lock | Gap |
| 7 | Chalti gaadi mein (phone dashboard par) 1–2 ghante | Accuracy, signal drop, gaps |

Har test ka result (device, browser, gap minutes) ek sheet mein likhein. Isi se decide hoga ki Android app zaruri hai ya nahi.

### C. Load test
10–20 phones ya simulated requests se 1 ghanta, `ping=30s` — Apps Script **Executions** page par errors aur timeouts dekhein.

## Android background-location (v2) — agar test B mein gaps aaye

Browser lock-screen/background mein GPS reliably nahi chalaata. Solution: chhota **Android app** jo
- *Foreground Service* + persistent notification ("TAB trip tracking ON"),
- *FusedLocationProviderClient*,
- `ACCESS_BACKGROUND_LOCATION` permission + battery-optimization exemption guide,
- wahi `postLocations` API (is code ka backend jyon ka tyon use ho sakta hai) use kare, token link se deep-link ho.

Build options: Kotlin native, ya Flutter/Capacitor + background-geolocation plugin. Jaldi alternative: open-source **Traccar Client** app + Traccar server (ya uska API bridge).
iPhone drivers ke liye bhi native app (Always-location) chahiye hogi; iOS Safari par background tracking possible nahi.

## Future: scalable database

API contract (`getTrip`, `postLocations`, `createTrip`, …) frontend se alag hai. Backend ko Supabase (Postgres) / Firebase / Node + PostgreSQL se replace karne par `admin.html` aur `track.html` mein sirf `API_URL` / `api()` function badalna padega. Postgres + PostGIS par route history, geofence, ETA aur alerts aasaan ho jate hain.
