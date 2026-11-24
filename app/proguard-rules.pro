# Sin reglas especiales: Compose y Coil traen las suyas.

# NewPipeExtractor (resolución de tráilers) y sus dependencias: usan reflexión y clases opcionales.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class co.davidvega.launcher.sistema.CompatUrl { *; }
-keep class org.jsoup.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.protobuf.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
