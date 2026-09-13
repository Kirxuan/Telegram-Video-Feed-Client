# TDLib's generated Java object graph crosses JNI. Native code depends on these
# binary names and fields, so this is the one deliberately broad keep boundary.
# Everything outside the official TDLib bridge remains eligible for shrinking.
-keep class org.drinkless.tdlib.Client { *; }
-keep class org.drinkless.tdlib.TdApi { *; }
-keep class org.drinkless.tdlib.TdApi$* { *; }
