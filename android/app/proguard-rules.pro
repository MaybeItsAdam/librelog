# UniFFI calls into the core through JNA, which finds classes and fields by
# reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.librelog_core.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# kotlinx.serialization wire types.
-keepclassmembers @kotlinx.serialization.Serializable class uk.co.maybeitssoftware.librelog.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit (QR pairing) creates its components by reflection from the manifest.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
