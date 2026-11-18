# Preserve TDLib native bindings
-keep class org.drinkless.tdlib.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Media3 ExoPlayer rules
-keep class androidx.media3.** { *; }

# Aggressive obfuscation for security classes
-repackageclasses
-allowaccessmodification
