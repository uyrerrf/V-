# ─── Fason ProGuard rules ────────────────────────────────────────────────────
# Kept lean — R8 handles the heavy lifting. Only explicit -keep for things that
# break under obfuscation: reflection targets, JS interfaces, socket callbacks.

# ── Core app ──────────────────────────────────────────────────────────────────
-keep class com.fason.app.core.** { *; }
-keep class com.fason.app.service.** { *; }
-keep class com.fason.app.receiver.** { *; }
-keep class com.fason.app.ui.MainActivity { *; }
-keep class com.fason.app.ui.ScreenCaptureProxyActivity { *; }

# ── JavaScript bridge interfaces (addJavascriptInterface) ─────────────────────
# Methods called from JS are invoked by reflection — obfuscation breaks them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Springe and any overlay bridge class
-keep class com.fason.app.features.springe.SpringeManager$SpringeBridge { *; }

# ── Socket.IO ─────────────────────────────────────────────────────────────────
-keep class io.socket.** { *; }
-keep interface io.socket.** { *; }
-dontwarn io.socket.**

# ── Accessibility services ────────────────────────────────────────────────────
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ── Notification listener ─────────────────────────────────────────────────────
-keep class * extends android.service.notification.NotificationListenerService { *; }

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── CameraX ───────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── OkHttp / Okio (Socket.IO dep) ─────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Google Play Services Location ─────────────────────────────────────────────
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# ── Keep all BroadcastReceivers registered in manifest ────────────────────────
-keep class * extends android.content.BroadcastReceiver { *; }

# ── Enums (used in switch statements via ordinal/name) ────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Serializable / Parcelable ─────────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Native methods ────────────────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Suppress harmless missing-class warnings from GMS / AndroidX ──────────────
-dontwarn com.google.errorprone.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn sun.security.**

# ── R8 full-mode: allow aggressive class merging and inlining ─────────────────
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
