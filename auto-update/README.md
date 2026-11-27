# عقد التحديث التلقائي لتطبيقات المتجر

كل تطبيق أندرويد يُنشر على `https://medmrf-10.github.io/store/` يلتزم بعقد موحّد يضمن عمل التحديث التلقائي فوراً:

## 1. سجل التطبيق في الكتالوج المركزي (`apps.json`)
يُضاف التطبيق داخل مصفوفة `apps` مع الحقول التالية:
- `slug`: معرّف فريد للتطبيق (مثل `mirath`)
- `pkg`: معرّف حزمة أندرويد (`applicationId`)
- `vcode`: رقم `versionCode` الصحيح (يزداد مع كل إصدار — هو المعيار الحاسم لمقارنة التحديث)
- `version`: رقم الإصدار الظاهر (`versionName`)
- `url`: رابط حزمة `APK` (نسبي داخل المستودع أو رابط مباشر من `GitHub Releases`)
- `name`, `desc`, `icon`, `size`, `whatsnew`, `updated`, `autoUpdate: true`

## 2. دمج الفحص داخل التطبيق
انسخ وحدة `StoreAutoUpdater.kt` إلى التطبيق، وتأكد من وجود صلاحية `REQUEST_INSTALL_PACKAGES` في `AndroidManifest.xml`، ثم استدعِ الفحص عند التشغيل:
- `StoreAutoUpdater.check(slug = "<slug>", currentVersionCode = BuildConfig.VERSION_CODE, pkg = BuildConfig.APPLICATION_ID)`
- عند وجود إصدار أحدث (`remote.vcode > currentVersionCode`)، استدعِ `StoreAutoUpdater.downloadAndInstall(context, info)`.
