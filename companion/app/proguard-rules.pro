-keep class com.xiaomi.xms.wearable.** { *; }
-keep class com.xiaomi.xms.wearable.tasks.** { *; }
-dontwarn com.xiaomi.xms.wearable.**

# Material 组件由 XML 反射加载，R8 会裁剪其 (Context, AttributeSet) 构造器导致闪退
-keep class com.google.android.material.** { *; }