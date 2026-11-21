# ARCore native / JNI keep rules
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**
-keep class com.google.ar.core.annotations.UsedByNative
-keep @com.google.ar.core.annotations.UsedByNative class *
-keepclassmembers class * {
    @com.google.ar.core.annotations.UsedByNative *;
}
-keep class com.google.ar.core.annotations.UsedByReflection
-keep @com.google.ar.core.annotations.UsedByReflection class *
-keepclassmembers class * {
    @com.google.ar.core.annotations.UsedByReflection *;
}
