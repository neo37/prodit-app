# Умный плеер — R8 (сжатие и обфускация; требование Google Play к оптимизации DEX).
# Активности, сервисы и приёмники из манифеста R8 сохраняет сам; библиотеки (Firebase, Media3, Play) — со своими правилами.
# Номера строк — для отчётов о падениях (CrashLog); расшифровка — dist/mapping-<code>.txt.
-keepattributes SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile
# мосты JS → Java (если появятся)
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
