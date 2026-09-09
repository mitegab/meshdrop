# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# MeshDrop speaks a tiny hand-rolled HTTP dialect over plain sockets: keep the
# generated serialisation metadata for the JSON models we put on the wire.
-keepattributes Signature,InnerClasses,EnclosingMethod
