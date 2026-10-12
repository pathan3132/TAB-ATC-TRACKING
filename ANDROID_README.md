# TAB Tracker – Android app (background location)

Yeh app wahi backend (`Code.gs`) use karti hai. Driver WhatsApp link dabata hai → app khulti hai →
tracking shuru → phone lock / app background mein bhi location aati rehti hai (Foreground Service).
Driver ke paas Stop / Complete nahi hai; yeh sirf admin dashboard se hota hai.

> **Dhyan:** Yeh source maine yahan compile ya real phone par run nahi kiya. Pehli build mein Gradle version
> ya import ki chhoti dikkat aa sakti hai; Android Studio ke suggestion se theek ho jati hai.

## Build steps (Android Studio)

1. **New Project → Empty Views Activity**. Name: `TAB Tracker`, Package: `com.tabsolutions.tracker`,
   Language: Kotlin, Minimum SDK: **API 26**.
2. Project ki default `MainActivity.kt` delete karein. Is folder ki `MainActivity.kt`, `LocationService.kt`,
   `Api.kt`, `AppConfig.kt` ko `app/src/main/java/com/tabsolutions/tracker/` mein daalein.
3. `AndroidManifest.xml` ko is folder wali file se replace karein
   (agar template mein `ic_launcher` na ho to `android:icon` line hata dein).
4. `app/build.gradle(.kts)` ke `dependencies` mein add karein:
   ```
   implementation("com.google.android.gms:play-services-location:21.3.0")
   ```
   (`androidx.appcompat` template mein pehle se hota hai.) Phir **Sync Now**.
5. `AppConfig.kt` mein `API_URL` badlein (wahi URL jo `config.js` mein hai).
6. `AndroidManifest.xml` mein `android:host="atctransportcompany.netlify.app"` apni site se match karein.
7. **Build → Build Bundle(s)/APK(s) → Build APK(s)**. APK `app/build/outputs/apk/debug/` mein milega.

## Driver tak pahunchana

- APK ko Netlify folder mein (`tab-tracker.apk`) daal dein ya Google Drive par rakhein.
- `config.js` mein `ANDROID_APK_URL` set karein → Android phone par driver page par
  "App download karein" button dikhega.
- Driver ko ek baar APK install karwana hoga (Settings mein "Install unknown apps" allow).
- Uske baad WhatsApp link dabane par "Open with" mein **TAB Tracker** chunein (Always select karein).
  Link app mein nahi khule to app kholkar link paste kar sakte hain.

## Driver ko setup mein kya allow karna hai

1. Location → Allow (while using app), phir **Allow all the time**.
2. Notifications → Allow (tracking notification ke liye zaruri).
3. Battery optimization → **Allow / Unrestricted**.
4. Xiaomi / Vivo / Oppo / Realme / Samsung par "Autostart" ya "Background activity" bhi ON karwayein
   (steps: dontkillmyapp.com).

## Dhyan dene wali baatein

- Play Store par background location ke liye Google ki strict declaration lagti hai; internal use ke liye
  APK sideload ya Firebase App Distribution theek hai.
- Driver ko tracking ki jaankari aur consent zaruri hai. App notification dikhati hai aur driver Android
  Settings se permission kabhi bhi hata sakta hai; yeh privacy ke liye jaan-boojh kar rakha gaya hai.
- iPhone drivers ke liye alag iOS app (Always-location) chahiye; Android app sirf Android ke liye hai.
- GPS signal kharab ho ya net na ho to points phone mein jama hote hain aur net aate hi upload hote hain
  (max 500 points).
