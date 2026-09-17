# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# BpmNative's methods are resolved by their mangled Java name
# (Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen and friends), so R8 must
# not rename the class, its package, or the methods.
#
# proguard-android-optimize.txt already carries this rule, and it is restated here on
# purpose: what it prevents is an UnsatisfiedLinkError that shows up only in a minified
# release build, only at run time, and only on arm64 - far enough from whatever edit
# caused it to be worth more than an invisible default. keepclasseswithmembernames keeps
# only classes that actually declare a native method, so this is not a blanket keep.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
