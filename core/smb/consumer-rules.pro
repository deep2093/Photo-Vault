# SMBJ uses reflection for protocol/event handlers and crypto providers (rules carried over from Veyra).
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Optional Java SE APIs that smbj references but Android's NTLM/SMB transport never uses.
-dontwarn javax.el.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
