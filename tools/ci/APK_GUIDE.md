## أي ملف أنزّل؟ / Which APK?

### 1. `ONA-arm64-v8a_64bit-phones.apk`: أغلب الجوالات (99% من جوالات اليوم)
أي جوال من **2017 لليوم** تقريباً، ونظامه أندرويد 64 بت:
- **Samsung:** سلسلة Galaxy S (من S8 لـ S26)، وNote 8 لـ Note 20، والفولد والفليب، وسلاسل A (A15 وA25 وA35 وA55 وغيرها) وM وF
- **Xiaomi / Redmi / POCO:** Redmi Note 7 لـ Note 14، وRedmi Pad SE، وPOCO X وF وM، وXiaomi 11 لـ 15
- **Infinix / Tecno / itel:** Hot وNote وZero، وSpark وCamon وPova
- **Oppo / Realme / OnePlus / Vivo:** أغلب موديلات آخر 7 سنين
- **Huawei / Honor:** Mate وP وNova، وHonor X
- **Google Pixel** و**Motorola** و**Nokia** و**Sony** الحديثة
- **التابلت:** Galaxy Tab S6 وما بعدها، وGalaxy Tab A الحديثة، وXiaomi Pad، وRedmi Pad

**المعالجات:** Snapdragon (6xx و7xx و8xx و8 Gen)، Exynos (9810 وما بعده)، MediaTek Dimensity، Helio G (G35 لـ G99)، Kirin، Tensor، Unisoc T6xx وT7xx. فيه محرك Rust.

### 2. `ONA-armeabi-v7a_32bit-old-phones.apk`: الجوالات القديمة أو الرخيصة (32 بت)
- جوالات **قبل 2017** تقريباً
- جوالات **Android Go** الرخيصة، حتى لو جديدة، لأن نظامها 32 بت
- أمثلة: Galaxy J2 وJ5 وJ7 القديمة، وGalaxy A01 Core، وRedmi 4A وRedmi Go، وitel A وPop القديمة، وبعض Infinix Smart القديمة

### 3. `ONA-x86_64_emulators-PC.apk`: الكمبيوتر والمحاكيات
- **محاكيات:** BlueStacks وLDPlayer وMEmu وNox، والمحاكي تبع Android Studio
- **Chromebook** بمعالج Intel أو AMD
- **Waydroid** على لينكس
- مش للجوالات

### مش متأكد؟
- **جرّب arm64 أول.** لو ما رضي ينزل، أو طلع "التطبيق غير متوافق"، نزّل 32 بت.
- أو نزّل تطبيق **CPU-Z** وافتح تبويب **System**، وشوف **Supported ABIs**:
  - لو فيه **arm64-v8a**، نزّل arm64
  - لو فيه **armeabi-v7a** بس، نزّل 32 بت
  - لو فيه **x86_64**، نزّل نسخة المحاكي

---
**English:** arm64 = almost every phone and tablet since ~2017 (Snapdragon, Exynos, Dimensity, Helio, Kirin, Tensor). armeabi-v7a = old or Android Go phones with a 32-bit system. x86_64 = emulators (BlueStacks, LDPlayer...), Chromebooks and Waydroid. Not sure? Take arm64, or check "Supported ABIs" in CPU-Z.
