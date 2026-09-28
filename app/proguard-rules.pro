# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep JSON
-keep class org.json.** { *; }

# Keep VPN service
-keep class com.wrapper.vpn.BrowsecVpnService { *; }
-keep class com.wrapper.vpn.BrowsecVpnService$* { *; }
