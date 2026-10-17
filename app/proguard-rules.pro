# Keep Media3 / Coil / serializers in release builds (minify currently off, rules kept for later).
-keep class androidx.media3.** { *; }
-keep class coil.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
