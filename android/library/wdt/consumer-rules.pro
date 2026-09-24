# Classes and members used from the native code (by name)
-keep class com.facebook.wdt.TransferReport { <init>(...); }
-keep class com.facebook.wdt.TransferProgress { <init>(...); }
-keep class com.facebook.wdt.WdtException { <init>(...); }
-keep interface com.facebook.wdt.ProgressListener { *; }
-keep class * implements com.facebook.wdt.ProgressListener { void onProgress(...); }
-keepclasseswithmembernames class com.facebook.wdt.NativeWdt { native <methods>; }
