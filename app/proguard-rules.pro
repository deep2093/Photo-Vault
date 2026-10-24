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
