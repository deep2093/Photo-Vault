# WireGuard GoBackend: JNI natives, the VpnService subclass named in the library manifest,
# and config classes parsed reflectively must keep their names (carried over from Veyra).
-keep class com.wireguard.** { *; }
# The WireGuard library marks classes with JSR-305 annotations (javax.annotation.Nonnull etc.), which are
# compile-time only and not shipped on Android. Found by the PhotoVault release build.
-dontwarn javax.annotation.**
