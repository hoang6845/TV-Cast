# R8 rules intentionally limited to runtime reflection/JNI entry points in this app.
# SDKs (Billing, Firebase, Hilt, Room, Navigation, Ads, CameraX, Media3, etc.) ship
# their own consumer rules; do not keep entire dependency namespaces here.

# Reflection in BaseActivity/BaseFragment/BaseRecyclerViewAdapter reads generic types.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Keep retrace quality without retaining application code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebView JavaScript bridges (safe even when no bridge is currently registered).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# CastOptionsProvider is read from manifest metadata as a class name.
-keep class com.tvchromecast.screenmirroringplus.cast.CastOptionsProvider {
    public <init>();
    public *;
}

# AndroidX Startup creates this initializer from the merged manifest. Keeping its
# name and no-arg constructor protects the Billing/IAP startup path.
-keep class tpt.dev.monetization.subs.initializer.BillingInitializer {
    public <init>();
}

# These classes resolve ViewBinding and ViewModel generic arguments at runtime.
# Retain both the generic base classes and their concrete subclasses. Keeping only
# subclasses is insufficient in R8 full mode: if the generic base is optimized or
# stripped, its subclass Signature attribute can be removed even with
# -keepattributes Signature. Names may still be obfuscated because reflection starts
# from javaClass.
-keep,allowoptimization,allowobfuscation class * extends hoang.dqm.codebase.base.activity.BaseActivity {
    <init>();
}
-keep,allowoptimization,allowobfuscation class * extends hoang.dqm.codebase.base.activity.BaseFragment {
    <init>();
}
-keep,allowoptimization,allowobfuscation class * extends hoang.dqm.codebase.base.viewmodel.BaseViewModel {
    <init>(...);
}
-keep,allowobfuscation class hoang.dqm.codebase.base.adapter.BaseRecyclerViewAdapter { *; }
-keep,allowobfuscation class hoang.dqm.codebase.base.adapter.BaseListAdapter { *; }
-keep,allowobfuscation class hoang.dqm.codebase.base.adapter.BaseRecyclerViewItemAdapter { *; }
-keep,allowobfuscation class hoang.dqm.codebase.base.adapter.BaseRecyclerViewItemShimmerAdapter { *; }
-keep,allowobfuscation class * extends hoang.dqm.codebase.base.adapter.BaseRecyclerViewAdapter {
    <init>(...);
}
-keep,allowobfuscation class * extends hoang.dqm.codebase.base.adapter.BaseListAdapter {
    <init>(...);
}
-keep,allowobfuscation class * extends hoang.dqm.codebase.base.adapter.BaseRecyclerViewItemAdapter {
    <init>(...);
}
-keep,allowobfuscation class * extends hoang.dqm.codebase.base.adapter.BaseRecyclerViewItemShimmerAdapter {
    <init>(...);
}

# BindingReflex invokes these generated methods by name.
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
}

# Gson fields whose JSON names are explicit may be obfuscated, but must not be removed.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.TypeAdapter
-keep,allowobfuscation,allowoptimization class * implements com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowoptimization class * implements com.google.gson.JsonSerializer
-keep,allowobfuscation,allowoptimization class * implements com.google.gson.JsonDeserializer

# Required only for Android framework parceling and explicit Java serialization.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Kotlin metadata is used by a few runtime libraries; keep the annotation itself,
# not every Kotlin/coroutines class.
-keep class kotlin.Metadata { *; }

# WebRTC binds Java methods/classes from native code. Its upstream integration
# requires this namespace to retain names and members.
-keep class org.webrtc.** { *; }

# Native method names are part of the JNI contract.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Warnings for genuinely optional JDK/provider classes referenced by dependencies.
-dontwarn sun.misc.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.invoke.StringConcatFactory
# OkHttp optional Bouncy Castle support
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
