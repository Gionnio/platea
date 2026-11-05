-keepattributes SourceFile,LineNumberTable


-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
  <methods>;
}
-dontwarn com.google.protobuf.**

# TODO investigate using smaller scope
-keep class com.google.common.cache.** { *; }

# Media3SubtitleOverride uses reflection to access/modify these fields
-keep class androidx.media3.ui.SubtitleView {
  private androidx.media3.ui.SubtitleView$Output output;
}
-keep class androidx.media3.ui.CanvasSubtitleOutput {
  private final java.util.List painters;
}
-keep class androidx.media3.ui.SubtitlePainter {
  private final float outlineWidth;
}

# Platea: SMB client for direct reading from the NAS. smbj dispatches its events with mbassador (reflection on
# @Handler methods) and builds packet classes by reflection; Bouncy Castle provides MD4/AES-CMAC.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
