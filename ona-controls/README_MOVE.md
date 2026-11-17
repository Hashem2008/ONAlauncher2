# ONA Controls — floating game buttons for Android

Floating Winlator-style buttons over any app (Winlator, emulators, games). Keys reach the game through
**Shizuku** (no root) or the accessibility service. Separate Android app, package `com.onalauncher.controls`.

## Move the project to another place (new repository / new computer)

Keep this folder layout (the build workflow expects it):

```
<your repo>/
  ona-controls/                      <- this folder (the whole Android project)
  .github/workflows/ona_controls.yml <- builds the APK on GitHub (in the zip next to this folder)
```

1. Make a new GitHub repository (private is fine) and upload both parts as shown above.
2. GitHub builds it by itself on every push (Actions tab, about 3 minutes). The APK appears in the
   release **ona-controls** and on the build page as **ONA-Controls**.
3. Or build on a computer: open the `ona-controls` folder in **Android Studio** (JDK 17+), then
   `Build > Build APK(s)`, or run `./gradlew :app:assembleDebug` inside `ona-controls`.

Signing key: `app/ona_controls.jks` (password `onacontrols`, set in `app/build.gradle.kts`). Keep the
SAME key in the new place, otherwise the phone will not install the update over the old app.

## Main files

| Path | What |
|---|---|
| `app/src/main/java/com/onalauncher/controls/service/` | overlay service, editor, button settings |
| `.../view/` | drawing of buttons, editor |
| `.../input/` | where keys go (Shizuku / accessibility, root, PC bridge) |
| `.../shizuku/` | key injection through Shizuku |
| `.../profile/` | profiles (JSON) and built-in layouts (Generals ZH) |
| `.../ui/` | the app screen (English / Arabic) |
| `app/src/main/res/values*/strings.xml` | texts EN + AR |
| `profiles/Generals-my-layout.json` | the user's own Generals layout, import it in the app |
| `pc-bridge/ona_bridge.py` | optional PC receiver |

## Import a layout

App > Profile > **Import profile** > choose `Generals-my-layout.json` > then pick it for Winlator
under "Your games".

---

# أزرار ONA — أزرار ألعاب عائمة للأندرويد

## كيف تنقل المشروع لمكان ثاني

خلي الترتيب هيك:

```
<الريبو تبعك>/
  ona-controls/                      <- هذا المجلد (المشروع كامل)
  .github/workflows/ona_controls.yml <- هذا اللي بيبني التطبيق على GitHub
```

1. اعمل ريبو جديد على GitHub (خاص عادي) وارفع الجزئين بنفس الترتيب.
2. GitHub بيبني التطبيق لحاله مع كل رفع (تبويب Actions، تقريباً 3 دقايق)، والـ APK بيطلع بالإصدار
   **ona-controls**.
3. أو ابنيه على كمبيوتر: افتح مجلد `ona-controls` بـ **Android Studio** واعمل Build APK.

مفتاح التوقيع `app/ona_controls.jks` (كلمة السر `onacontrols`): خليه نفسه، وإلا الجوال ما بيركّب
التحديث فوق التطبيق القديم.

## استيراد الترتيب

التطبيق > البروفايل > **استيراد بروفايل** > اختار `Generals-my-layout.json` > وبعدين اختاره لـ Winlator
من "التطبيقات المختارة".
