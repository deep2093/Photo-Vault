# Pixallery R8 rules.

# smbj (SMB client): its event bus finds handlers by annotation, and it loads crypto by name.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**

# Bouncy Castle (used by smbj for SMB 3 signing and encryption): providers are looked up by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Logging bridges pulled in by smbj.
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

# OpenCV (People): Java classes are bound to native code by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# TensorFlow Lite (People test model): native code finds these classes by name.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
# The Places map (MapLibre, 7B-2).
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
