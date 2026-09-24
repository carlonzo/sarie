# Play Services Cronet is compileOnly on the bridge; consumers that do not package
# play-services-cronet must not fail R8 on these optional references.
-dontwarn com.google.android.gms.net.CronetProviderInstaller
-dontwarn com.google.android.gms.tasks.**
