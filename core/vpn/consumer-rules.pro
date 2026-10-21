# WireGuard GoBackend: JNI natives, the VpnService subclass named in the library manifest,
# and config classes parsed reflectively must keep their names (carried over from Veyra).
-keep class com.wireguard.** { *; }
