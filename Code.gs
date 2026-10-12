/**
 * TAB Solutions – Link-Based Driver Live Tracking
 * Backend: Google Apps Script (container-bound to a Google Sheet)
 *
 * Sheets used:
 *   Trips      -> ek row per trip (details + token + latest location)
 *   Locations  -> har GPS ping ek row (history / route)
 *
 * Script Properties (Project Settings > Script properties):
 *   ADMIN_PASSWORD -> admin login password (aap set karte hain)
 *   SECRET         -> setup() khud generate karta hai (session signing key)
 */

const SHEET_TRIPS = 'Trips';
const SHEET_LOC = 'Locations';
const TRIP_HEADERS = [
  'TripID', 'Token', 'CreatedAt', 'VehicleNo', 'DriverName', 'DriverMobile',
  'PartyName', 'PartyMobile', 'LoadingLocation', 'DeliveryLocation', 'Material',
  'Freight', 'Remarks', 'Status', 'TrackingState', 'LastLat', 'LastLng',
  'LastAcc', 'LastTime', 'StartedAt', 'DeliveredAt'
];
const LOC_HEADERS = ['TripID', 'DeviceTime', 'ServerTime', 'Lat', 'Lng', 'Accuracy', 'SpeedKmh'];
const STATUSES = ['Loading', 'In Transit', 'Delivered'];
const SESSION_HOURS = 12;
const MAX_POINTS_PER_POST = 60;
const MAX_HISTORY_POINTS = 4000;
const DATE_FMT = 'dd-mmm-yyyy hh:mm:ss';

const C = {};
TRIP_HEADERS.forEach(function (h, i) { C[h] = i + 1; });

/* ------------------------------------------------------------------ */
/*  ONE-TIME SETUP  (Apps Script editor mein ek baar Run karein)       */
/* ------------------------------------------------------------------ */
function setup() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const t = getOrCreateSheet_(ss, SHEET_TRIPS, TRIP_HEADERS);
  const l = getOrCreateSheet_(ss, SHEET_LOC, LOC_HEADERS);

  const tRows = Math.max(1, t.getMaxRows() - 1);
  ['TripID', 'Token', 'VehicleNo', 'DriverMobile', 'PartyMobile'].forEach(function (h) {
    t.getRange(2, C[h], tRows, 1).setNumberFormat('@');
  });
  ['CreatedAt', 'LastTime', 'StartedAt', 'DeliveredAt'].forEach(function (h) {
    t.getRange(2, C[h], tRows, 1).setNumberFormat(DATE_FMT);
  });
  const lRows = Math.max(1, l.getMaxRows() - 1);
  l.getRange(2, 1, lRows, 1).setNumberFormat('@');
  l.getRange(2, 2, lRows, 2).setNumberFormat(DATE_FMT);

  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('SECRET')) {
    props.setProperty('SECRET', Utilities.getUuid() + Utilities.getUuid());
  }
  if (!props.getProperty('ADMIN_PASSWORD')) {
    Logger.log('ZARURI: Project Settings > Script properties mein ADMIN_PASSWORD add karein.');
  } else {
    Logger.log('Setup complete. Ab Deploy > New deployment > Web app karein.');
  }
}

function getOrCreateSheet_(ss, name, headers) {
  let sh = ss.getSheetByName(name);
  if (!sh) sh = ss.insertSheet(name);
  if (sh.getLastRow() === 0) {
    sh.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  return sh;
}

/* ------------------------------------------------------------------ */
/*  HTTP entry points                                                  */
/* ------------------------------------------------------------------ */
function doGet() {
  return json_({ ok: true, service: 'TAB Tracking API', time: Date.now() });
}

function doPost(e) {
  try {
    const raw = (e && e.postData && e.postData.contents) || '';
    if (!raw || raw.length > 200000) return json_({ ok: false, error: 'BAD_REQUEST' });
    const req = JSON.parse(raw);
    const handler = ROUTES[req.action];
    if (!handler) return json_({ ok: false, error: 'UNKNOWN_ACTION' });
    return json_(handler(req));
  } catch (err) {
    console.error(err && err.stack ? err.stack : err);
    return json_({ ok: false, error: 'SERVER_ERROR' });
  }
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

const ROUTES = {
  // public / driver (token based)
  login: login_,
  getTrip: getTrip_,
  postLocations: postLocations_,
  // admin (session based) - stop / complete sirf admin ke paas hai
  setTracking: admin_(setTracking_),
  createTrip: admin_(createTrip_),
  listTrips: admin_(listTrips_),
  setStatus: admin_(setStatus_),
  getHistory: admin_(getHistory_),
  regenerateToken: admin_(regenerateToken_)
};

function admin_(fn) {
  return function (req) {
    if (!checkSession_(req.session)) return { ok: false, error: 'UNAUTHORIZED' };
    return fn(req);
  };
}

/* ------------------------------------------------------------------ */
/*  Auth (admin)                                                       */
/* ------------------------------------------------------------------ */
function login_(req) {
  const cache = CacheService.getScriptCache();
  const fails = Number(cache.get('loginFails') || 0);
  if (fails >= 5) {
    return { ok: false, error: 'LOCKED', message: 'Bahut zyada galat attempts. 15 minute baad try karein.' };
  }
  const pw = PropertiesService.getScriptProperties().getProperty('ADMIN_PASSWORD');
  if (pw && safeEq_(String(req.password || ''), pw)) {
    cache.remove('loginFails');
    return { ok: true, session: makeSession_() };
  }
  cache.put('loginFails', String(fails + 1), 900);
  return { ok: false, error: 'BAD_PASSWORD', message: 'Password galat hai.' };
}

function sign_(s) {
  const secret = PropertiesService.getScriptProperties().getProperty('SECRET');
  return Utilities.base64EncodeWebSafe(Utilities.computeHmacSha256Signature(s, secret));
}

function makeSession_() {
  const payload = String(Date.now() + SESSION_HOURS * 3600 * 1000);
  return payload + '.' + sign_(payload);
}

function checkSession_(tok) {
  if (!tok || typeof tok !== 'string') return false;
  const parts = tok.split('.');
  if (parts.length !== 2) return false;
  if (!safeEq_(sign_(parts[0]), parts[1])) return false;
  return Number(parts[0]) > Date.now();
}

function safeEq_(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

/* ------------------------------------------------------------------ */
/*  Sheet helpers                                                      */
/* ------------------------------------------------------------------ */
function tripsSheet_() { return SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_TRIPS); }
function locSheet_() { return SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_LOC); }

function rowToTrip_(row) {
  const o = {};
  TRIP_HEADERS.forEach(function (h, i) { o[h] = row[i]; });
  return o;
}

function ms_(v) {
  if (v instanceof Date) return v.getTime();
  if (typeof v === 'number') return v;
  return null;
}

function num_(v) { return (v === '' || v === null || v === undefined) ? null : Number(v); }

function findRow_(col, value) {
  const sh = tripsSheet_();
  const last = sh.getLastRow();
  if (last < 2) return null;
  const f = sh.getRange(2, col, last - 1, 1).createTextFinder(value)
    .matchEntireCell(true).matchCase(true).findNext();
  return f ? f.getRow() : null;
}

function findRowByToken_(token) {
  if (!token || !/^[A-Za-z0-9]{32,64}$/.test(token)) return null;
  return findRow_(C.Token, token);
}

function findRowById_(id) {
  if (!id || !/^[A-Za-z0-9\-]{3,40}$/.test(String(id))) return null;
  return findRow_(C.TripID, String(id));
}

function readTrip_(rowNum) {
  return rowToTrip_(tripsSheet_().getRange(rowNum, 1, 1, TRIP_HEADERS.length).getValues()[0]);
}

function adminTrip_(t) {
  return {
    tripId: t.TripID, token: t.Token, createdAt: ms_(t.CreatedAt),
    vehicleNo: t.VehicleNo, driverName: t.DriverName, driverMobile: String(t.DriverMobile),
    partyName: t.PartyName, partyMobile: String(t.PartyMobile || ''),
    loadingLocation: t.LoadingLocation, deliveryLocation: t.DeliveryLocation,
    material: t.Material, freight: t.Freight, remarks: t.Remarks,
    status: t.Status, trackingState: t.TrackingState,
    lastLat: num_(t.LastLat), lastLng: num_(t.LastLng), lastAcc: num_(t.LastAcc),
    lastTime: ms_(t.LastTime), startedAt: ms_(t.StartedAt), deliveredAt: ms_(t.DeliveredAt)
  };
}

// Driver ko sirf zaroori details dikhti hain (freight / party mobile nahi).
function publicTrip_(t) {
  return {
    tripId: t.TripID, vehicleNo: t.VehicleNo, driverName: t.DriverName,
    partyName: t.PartyName, loadingLocation: t.LoadingLocation,
    deliveryLocation: t.DeliveryLocation, material: t.Material,
    remarks: t.Remarks, status: t.Status, trackingState: t.TrackingState
  };
}

function clean_(s, max) {
  s = String(s === null || s === undefined ? '' : s).trim().slice(0, max || 300);
  if (/^[=+\-@]/.test(s)) s = "'" + s; // spreadsheet formula injection se bachav
  return s;
}

function normPhone_(s) {
  let d = String(s || '').replace(/\D/g, '').replace(/^0+/, '');
  if (d.length === 10) d = '91' + d;
  if (d.length < 11 || d.length > 15) return null;
  return d;
}

function newToken_() {
  return (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '').slice(0, 40);
}

/* ------------------------------------------------------------------ */
/*  Admin actions                                                      */
/* ------------------------------------------------------------------ */
function createTrip_(req) {
  const t = req.trip || {};
  const vehicle = clean_(t.vehicleNo, 20).toUpperCase().replace(/\s+/g, '');
  const driverName = clean_(t.driverName, 80);
  const driverMobile = normPhone_(t.driverMobile);
  const partyName = clean_(t.partyName, 120);
  const loading = clean_(t.loadingLocation, 150);
  const delivery = clean_(t.deliveryLocation, 150);

  if (!vehicle || !driverName || !partyName || !loading || !delivery) {
    return { ok: false, error: 'VALIDATION', message: 'Vehicle, Driver, Party, Loading aur Delivery location zaruri hain.' };
  }
  if (!driverMobile) {
    return { ok: false, error: 'VALIDATION', message: 'Driver mobile number sahi nahi hai.' };
  }
  let partyMobile = '';
  if (String(t.partyMobile || '').trim()) {
    partyMobile = normPhone_(t.partyMobile);
    if (!partyMobile) return { ok: false, error: 'VALIDATION', message: 'Party mobile number sahi nahi hai.' };
  }

  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const sh = tripsSheet_();
    let tripId;
    do {
      tripId = 'TRP-' + Utilities.formatDate(new Date(), 'Asia/Kolkata', 'yyMMdd') + '-' +
        Utilities.getUuid().replace(/-/g, '').slice(0, 4).toUpperCase();
    } while (findRowById_(tripId));

    const obj = {
      TripID: tripId, Token: newToken_(), CreatedAt: new Date(), VehicleNo: vehicle,
      DriverName: driverName, DriverMobile: driverMobile, PartyName: partyName,
      PartyMobile: partyMobile, LoadingLocation: loading, DeliveryLocation: delivery,
      Material: clean_(t.material, 120), Freight: clean_(t.freight, 40),
      Remarks: clean_(t.remarks, 300), Status: 'Loading', TrackingState: 'Not started',
      LastLat: '', LastLng: '', LastAcc: '', LastTime: '', StartedAt: '', DeliveredAt: ''
    };
    sh.appendRow(TRIP_HEADERS.map(function (h) { return obj[h]; }));
    return { ok: true, trip: adminTrip_(obj) };
  } finally {
    lock.releaseLock();
  }
}

function listTrips_() {
  const sh = tripsSheet_();
  const last = sh.getLastRow();
  if (last < 2) return { ok: true, trips: [] };
  const vals = sh.getRange(2, 1, last - 1, TRIP_HEADERS.length).getValues();
  const trips = vals.map(function (r) { return adminTrip_(rowToTrip_(r)); })
    .filter(function (t) { return t.tripId; })
    .sort(function (a, b) { return (b.createdAt || 0) - (a.createdAt || 0); })
    .slice(0, 1000);
  return { ok: true, trips: trips };
}

function setStatus_(req) {
  if (STATUSES.indexOf(req.status) < 0) return { ok: false, error: 'VALIDATION', message: 'Status galat hai.' };
  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const row = findRowById_(req.tripId);
    if (!row) return { ok: false, error: 'NOT_FOUND' };
    const sh = tripsSheet_();
    const prev = readTrip_(row);
    sh.getRange(row, C.Status).setValue(req.status);
    if (req.status === 'Delivered') {
      sh.getRange(row, C.TrackingState).setValue('Stopped');
      sh.getRange(row, C.DeliveredAt).setValue(new Date());
    } else {
      sh.getRange(row, C.DeliveredAt).clearContent();
      // Delivered trip dobara kholne par tracking dobara chalu ho sakti hai
      if (prev.Status === 'Delivered') sh.getRange(row, C.TrackingState).setValue('Waiting');
    }
    return { ok: true, trip: adminTrip_(readTrip_(row)) };
  } finally {
    lock.releaseLock();
  }
}

// Admin: tracking band (enabled=false) ya dobara chalu (enabled=true).
// Driver ka page / app agle ping par khud band ho jata hai, aur chalu karne par khud shuru.
function setTracking_(req) {
  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const row = findRowById_(req.tripId);
    if (!row) return { ok: false, error: 'NOT_FOUND' };
    const trip = readTrip_(row);
    if (trip.Status === 'Delivered') {
      return { ok: false, error: 'VALIDATION', message: 'Trip Delivered hai. Pehle status badal kar trip dobara kholein.' };
    }
    tripsSheet_().getRange(row, C.TrackingState).setValue(req.enabled ? 'Waiting' : 'Stopped');
    return { ok: true, trip: adminTrip_(readTrip_(row)) };
  } finally {
    lock.releaseLock();
  }
}

// Link leak ho jaye to purana link band karke naya token banayein.
function regenerateToken_(req) {
  const lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    const row = findRowById_(req.tripId);
    if (!row) return { ok: false, error: 'NOT_FOUND' };
    tripsSheet_().getRange(row, C.Token).setValue(newToken_());
    return { ok: true, trip: adminTrip_(readTrip_(row)) };
  } finally {
    lock.releaseLock();
  }
}

function getHistory_(req) {
  const row = findRowById_(req.tripId);
  if (!row) return { ok: false, error: 'NOT_FOUND' };
  const trip = adminTrip_(readTrip_(row));
  const ls = locSheet_();
  const last = ls.getLastRow();
  let pts = [];
  if (last >= 2) {
    const v = ls.getRange(2, 1, last - 1, 7).getValues();
    for (let i = 0; i < v.length; i++) {
      if (String(v[i][0]) === trip.tripId) {
        pts.push([ms_(v[i][1]), Number(v[i][3]), Number(v[i][4]), num_(v[i][5]), num_(v[i][6])]);
      }
    }
  }
  pts.sort(function (a, b) { return a[0] - b[0]; });
  const total = pts.length;
  let downsampled = false;
  if (total > MAX_HISTORY_POINTS) {
    const step = Math.ceil(total / MAX_HISTORY_POINTS);
    const lastPt = pts[total - 1];
    pts = pts.filter(function (_, i) { return i % step === 0; });
    if (pts[pts.length - 1] !== lastPt) pts.push(lastPt);
    downsampled = true;
  }
  return { ok: true, trip: trip, points: pts, total: total, downsampled: downsampled };
}

/* ------------------------------------------------------------------ */
/*  Driver actions (token = secret link)                               */
/* ------------------------------------------------------------------ */
function getTrip_(req) {
  const row = findRowByToken_(req.token);
  if (!row) return { ok: false, error: 'INVALID_LINK' };
  return { ok: true, trip: publicTrip_(readTrip_(row)), serverTime: Date.now() };
}

function validatePoint_(p, now) {
  if (!p) return null;
  const lat = Number(p.lat), lng = Number(p.lng);
  if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  if (lat === 0 && lng === 0) return null;
  let acc = Number(p.acc);
  acc = (p.acc == null || !isFinite(acc) || acc < 0) ? '' : Math.round(acc);
  let speed = Number(p.speed); // m/s from browser
  speed = (p.speed == null || !isFinite(speed) || speed < 0) ? '' : Math.round(speed * 3.6 * 10) / 10;
  let ts = Number(p.ts);
  if (!isFinite(ts) || ts > now + 300000 || ts < now - 7 * 86400000) ts = now;
  return {
    lat: Math.round(lat * 1e6) / 1e6, lng: Math.round(lng * 1e6) / 1e6,
    acc: acc, speed: speed, ts: ts
  };
}

function postLocations_(req) {
  const now = Date.now();
  const pts = (Array.isArray(req.points) ? req.points : [])
    .slice(0, MAX_POINTS_PER_POST)
    .map(function (p) { return validatePoint_(p, now); })
    .filter(Boolean)
    .sort(function (a, b) { return a.ts - b.ts; });
  if (!pts.length) return { ok: false, error: 'NO_VALID_POINTS' };

  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const row = findRowByToken_(req.token);
    if (!row) return { ok: false, error: 'INVALID_LINK' };
    const trip = readTrip_(row);
    if (trip.Status === 'Delivered') return { ok: false, error: 'TRIP_CLOSED' };
    if (trip.TrackingState === 'Stopped') return { ok: false, error: 'TRACKING_STOPPED' };

    const ls = locSheet_();
    const start = ls.getLastRow() + 1;
    const need = start + pts.length - 1;
    if (need > ls.getMaxRows()) ls.insertRowsAfter(ls.getMaxRows(), Math.max(1000, need - ls.getMaxRows()));
    ls.getRange(start, 1, pts.length, 7).setValues(pts.map(function (p) {
      return [trip.TripID, new Date(p.ts), new Date(now), p.lat, p.lng, p.acc, p.speed];
    }));

    const sh = tripsSheet_();
    const newest = pts[pts.length - 1];
    const prevTime = ms_(trip.LastTime);
    const status = trip.Status === 'Loading' ? 'In Transit' : trip.Status;
    if (prevTime === null || newest.ts >= prevTime) {
      sh.getRange(row, C.Status, 1, 6).setValues([[
        status, 'Active', newest.lat, newest.lng, newest.acc, new Date(newest.ts)
      ]]);
    } else {
      sh.getRange(row, C.Status, 1, 2).setValues([[status, 'Active']]);
    }
    if (!trip.StartedAt) sh.getRange(row, C.StartedAt).setValue(new Date(now));
    return { ok: true, saved: pts.length, serverTime: now };
  } finally {
    lock.releaseLock();
  }
}
