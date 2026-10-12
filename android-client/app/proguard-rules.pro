# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.easyshopping.android.**$$serializer { *; }
-keepclassmembers class com.easyshopping.android.** { *** Companion; }
-keepclasseswithmembers class com.easyshopping.android.** { kotlinx.serialization.KSerializer serializer(...); }
