## أي ملف أنزّل؟ | Which APK should I download?

> **مش متأكد؟ نزّل arm64.** | **Not sure? Take arm64.**

---

### 📱 1. `ONA-arm64-v8a_64bit-phones.apk`

**عربي:** لأغلب الجوالات والتابلت (تقريباً 99% من جوالات اليوم). أي جهاز من **2017 لليوم** ونظامه أندرويد 64 بت. فيه محرك Rust.

**English:** For almost every phone and tablet today (about 99%). Any device from **2017 on** with 64-bit Android. Includes the Rust engine.

| الشركة / Brand | أمثلة / Examples |
|---|---|
| Samsung | Galaxy S8 – S26, Note 8 – Note 20, Z Fold / Flip, A15 / A25 / A35 / A55, M and F series, Galaxy Tab S6+ / Tab A (new) |
| Xiaomi / Redmi / POCO | Redmi Note 7 – Note 14, Redmi Pad / Pad SE, POCO X / F / M, Xiaomi 11 – 15, Xiaomi Pad |
| Infinix / Tecno / itel | Hot, Note, Zero, Spark, Camon, Pova |
| Oppo / Realme / OnePlus / Vivo | most models of the last 7 years / أغلب موديلات آخر 7 سنين |
| Huawei / Honor | Mate, P, Nova, Honor X |
| Others / غيرها | Google Pixel, Motorola, Nokia, Sony (recent / الحديثة) |

**المعالجات / Chips:** Snapdragon (6xx, 7xx, 8xx, 8 Gen), Exynos (9810 and newer), MediaTek Dimensity, Helio G (G35 – G99), Kirin, Google Tensor, Unisoc T6xx / T7xx.

---

### 📟 2. `ONA-armeabi-v7a_32bit-old-phones.apk`

**عربي:** للجوالات القديمة أو الرخيصة اللي نظامها **32 بت**: جوالات قبل 2017 تقريباً، وجوالات **Android Go** حتى لو جديدة.

**English:** For old or budget phones with a **32-bit** Android: phones from before ~2017 and **Android Go** phones, even new ones.

**أمثلة / Examples:** Galaxy J2 / J5 / J7 (old), Galaxy A01 Core, Redmi 4A, Redmi Go, old itel A / Pop, some old Infinix Smart.

---

### 💻 3. `ONA-x86_64_emulators-PC.apk`

**عربي:** للكمبيوتر والمحاكيات، **مش للجوالات**.

**English:** For PCs and emulators, **not for phones**.

**أمثلة / Examples:** BlueStacks, LDPlayer, MEmu, Nox, Android Studio emulator, Chromebooks with Intel / AMD, Waydroid (Linux).

---

### 🔍 كيف أعرف جهازي؟ | How do I check my device?

**عربي:**
1. جرّب **arm64** أول. لو ما رضي ينزل، أو طلع "التطبيق غير متوافق"، نزّل **32 بت**.
2. أو نزّل تطبيق **CPU-Z** ← تبويب **System** ← شوف **Supported ABIs**:
   - فيه `arm64-v8a`: نزّل **arm64**
   - فيه `armeabi-v7a` بس: نزّل **32 بت**
   - فيه `x86_64`: نزّل **نسخة المحاكي**
3. اللانشر نفسه بيحكيلك لو في نسخة أنسب لجهازك.

**English:**
1. Try **arm64** first. If it will not install, or says "app not compatible", take **32-bit**.
2. Or install **CPU-Z** → **System** tab → look at **Supported ABIs**:
   - has `arm64-v8a`: take **arm64**
   - only `armeabi-v7a`: take **32-bit**
   - has `x86_64`: take the **emulator** APK
3. The launcher itself tells you when another APK fits your device better.
