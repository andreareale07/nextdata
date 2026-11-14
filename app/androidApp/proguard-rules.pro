# JNI: the engine looks these up by name from native code.
-keep class app.nextdata.bridge.NativeBridge { *; }
-keep interface app.nextdata.bridge.EventListener { *; }
-keepclasseswithmembernames class * { native <methods>; }
# JNA (Windows only code path, harmless on Android)
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
