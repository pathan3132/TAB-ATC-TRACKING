// Deploy karne ke baad sirf API_URL badalna hai.
window.TAB_CONFIG = {
  // Apps Script > Deploy > Web app ka URL (…/exec par khatam hota hai)
  API_URL: "https://script.google.com/macros/s/AKfycbwouqiTx6ezP3rBYuglLGKfUzm2O0dJWilSCP_tvfnU6UhT3BfEWTdXs_pTduVSwL-e/exec",

  // Driver page har kitne second mein location bhejega
  PING_INTERVAL_SEC: 30,

  // Itne minute se purani location "stale" (orange) maani jayegi
  STALE_AFTER_MIN: 5,

  // Dashboard kitne second mein auto-refresh ho
  ADMIN_REFRESH_SEC: 15,

  // Optional: agar tracking page alag domain par ho to poora URL likhein
  // e.g. "https://track.tabsolutions.in/track.html". Khali chhodne par
  // admin.html wale folder ka track.html use hoga.
  TRACK_PAGE_URL: "",

  // Optional: Android app (APK) ka download link. Set karne par Android phone par
  // driver page par "App download karein" button dikhta hai.
  ANDROID_APK_URL: ""
};
