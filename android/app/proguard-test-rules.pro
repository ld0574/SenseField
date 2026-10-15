# AndroidX test uses these compile-time annotations; they have no Android runtime behavior.
# These rules apply only to the instrumented test APK, not the delivered application.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
