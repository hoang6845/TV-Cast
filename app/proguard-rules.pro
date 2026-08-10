############################################################
# COMMON ATTRIBUTES
############################################################

# Giữ thông tin cần thiết cho reflection, Gson, Retrofit, Kotlin...
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions

# Giữ thông tin dòng để Crashlytics đọc stacktrace chính xác
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Giữ tên tham số method
-keepparameternames


############################################################
# ANDROID WEBVIEW
############################################################

# Giữ các method được gọi từ JavaScript trong WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}


############################################################
# GOOGLE CAST
############################################################

-keep class com.tvchromecast.screenmirroringplus.cast.CastOptionsProvider {
    public <init>();
    public *;
}

# Nếu CastOptionsProvider của bạn ở package khác thì đổi package phía trên
# Ví dụ:
# -keep class com.example.base.cast.CastOptionsProvider { *; }


############################################################
# VIEW BINDING
############################################################

-keep class hoang.dqm.codebase.utils.BindingReflex { *; }

-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(
        android.view.LayoutInflater,
        android.view.ViewGroup,
        boolean
    );
}


############################################################
# PARCELABLE / SERIALIZABLE
############################################################

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


############################################################
# ENUM
############################################################

-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


############################################################
# NATIVE METHODS
############################################################

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}


############################################################
# CUSTOM VIEW
############################################################

-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(
        android.content.Context,
        android.util.AttributeSet,
        int
    );
    public <init>(
        android.content.Context,
        android.util.AttributeSet,
        int,
        int
    );
}


############################################################
# LOCALIZATION ACTIVITY
############################################################

-keep class com.akexorcist.localizationactivity.** { *; }
-dontwarn com.akexorcist.localizationactivity.**


############################################################
# CONNECT SDK
############################################################

# ConnectSDK sử dụng reflection khá nhiều
-keep class com.connectsdk.** { *; }
-dontwarn com.connectsdk.**


############################################################
# GSON
############################################################

-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }

# Giữ field có annotation Gson
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Giữ TypeAdapter
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.TypeAdapter
-keep,allowobfuscation,allowoptimization class * implements com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowoptimization class * implements com.google.gson.JsonSerializer
-keep,allowobfuscation,allowoptimization class * implements com.google.gson.JsonDeserializer


############################################################
# KOTLIN / COROUTINES
############################################################

-keep class kotlin.Metadata { *; }

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn java.lang.invoke.StringConcatFactory


############################################################
# JWT
############################################################

-keep class io.jsonwebtoken.** { *; }
-keepnames class io.jsonwebtoken.** { *; }
-dontwarn io.jsonwebtoken.**


############################################################
# BOUNCY CASTLE
############################################################

-keep class org.bouncycastle.** { *; }
-keepnames class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**


############################################################
# SCREEN STREAM
############################################################

-keepnames class info.dvkr.screenstream.** { *; }


############################################################
# RESOURCE IDs
############################################################

-keep class **.R
-keep class **.R$* {
    <fields>;
}


############################################################
# DAIMAJIA EASING
############################################################

-keep class com.daimajia.easing.** { *; }
-keep interface com.daimajia.easing.** { *; }

-dontwarn com.daimajia.easing.Glider
-dontwarn com.daimajia.easing.Skill


############################################################
# LOADING INDICATOR
############################################################

-keep class com.wang.avi.** { *; }
-keep class com.wang.avi.indicators.** { *; }


############################################################
# GSY VIDEO PLAYER
############################################################

-keep class com.shuyu.gsyvideoplayer.** { *; }
-dontwarn com.shuyu.gsyvideoplayer.**

-keep class com.shuyu.alipay.** { *; }
-keep interface com.shuyu.alipay.** { *; }
-dontwarn com.shuyu.alipay.**


############################################################
# IJK PLAYER
############################################################

-keep class tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**


############################################################
# ALIYUN / ALIVC / CICADA PLAYER
############################################################

-keep class com.alivc.** { *; }
-keep class com.aliyun.** { *; }
-keep class com.cicada.** { *; }

-dontwarn com.alivc.**
-dontwarn com.aliyun.**
-dontwarn com.cicada.**


############################################################
# SSL PROVIDERS
############################################################

-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.reflect.AnnotatedType


############################################################
# FIREBASE
############################################################

# Firebase đã có consumer ProGuard rules riêng.
# Không cần giữ toàn bộ Firebase.
-dontwarn com.google.firebase.ktx.Firebase


############################################################
# OPTIONAL: MODEL PACKAGE
############################################################

# Chỉ bật nếu các model Gson của bạn bị parse thành null ở bản release.
# Thay package bên dưới bằng package model thật của project.

# -keep class com.tvchromecast.screenmirroringplus.model.** { *; }
# -keep class com.example.base.model.** { *; }


############################################################
# LOG REMOVAL - OPTIONAL
############################################################

# Bỏ comment nếu muốn xóa Log ở bản release.
# Không nên bật trong khi đang kiểm tra lỗi.

# -assumenosideeffects class android.util.Log {
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
# }