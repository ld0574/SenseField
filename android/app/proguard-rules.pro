# Native libraries export Java_<package>_<class>_<method> names. Java-only members may shrink.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# AndroidJUnitRunner shares this class with the app's AndroidX dependencies.
# Keep the concrete bridge so a minified Release can be tested without duplicating it.
-keep class androidx.tracing.Trace { *; }

# Stable Kotlin base contracts shared by OkHttp and the separate AndroidX test APK.
# In particular, a test lambda subclass still calls Lambda(int) after app shrinking.
-keep class kotlin.jvm.internal.Lambda { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__LazyJVMKt { *; }
-keep class kotlin.LazyKt__LazyKt { *; }

-keep class androidx.lifecycle.Lifecycle$State { *; }
-keep class androidx.lifecycle.Lifecycle$Event { *; }

# Preserve only the ABI exercised across the separate release-test APK boundary.
# Names may still be obfuscated; unrelated helpers, application code and resources shrink normally.
# Package-private calls from the separate test APK must retain the application's package.
# This preserves access boundaries, not classes or members; shrinking remains enabled.
-keeppackagenames com.openkhub.sensefield
-keep,allowobfuscation class com.openkhub.sensefield.CuePlayer {
    <init>(android.content.Context);
    <init>(android.content.Context, boolean);
    static *** tonePreview(android.content.Context);
    static *** speechProbe(android.content.Context);
    void setAudioAuditListener(java.util.function.Consumer);
    boolean speechPreparationFinished();
    boolean speechReady();
    public boolean speak(...);
    public boolean playTone(...);
    public void stopSpeech();
    public void cancelPendingTone();
    void close();
}
-keep,allowobfuscation class com.openkhub.sensefield.CueRequest { <init>(...); }
-keep enum com.openkhub.sensefield.CueRequest$Category { *; }
-keep,allowobfuscation interface com.openkhub.sensefield.CueDispatcher$PlaybackCallback { *; }
-keep,allowobfuscation class com.openkhub.sensefield.BundledSpeechCatalog {
    static java.lang.String[] VOICES;
    static java.util.List guide();
}
-keep,allowobfuscation class com.openkhub.sensefield.BundledSpeechAssets {
    <init>(android.content.Context, java.lang.String, int);
    void loadIndex();
    short[] load(java.lang.String, boolean);
}

# JNI wrappers and immutable configuration used by the native runtime smoke tests.
-keepclassmembers class com.openkhub.sensefield.NativeBridge {
    static native void nativeSetDetectorCache(long, boolean);
}
-keepclassmembers class com.openkhub.sensefield.NativeDiagnosticPixels { static <methods>; }
-keepclassmembers class com.openkhub.sensefield.VoiceSpeechDetector { <init>(...); <methods>; }
-keepclassmembers class com.openkhub.sensefield.OnDeviceAsr { <init>(...); <methods>; }
-keep,allowobfuscation interface com.openkhub.sensefield.OnDeviceAsr$Listener { *; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.AssistantController {
    static boolean isRequest(java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.AsrModelStore$Spec {
    <fields>;
    static *** read(android.content.Context);
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.AsrModelStore {
    static boolean verified(java.io.File, long, java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile {
    <fields>;
    static *** load(android.content.Context);
    static android.content.SharedPreferences settings(android.content.Context);
}
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile$TemplateData { <fields>; }
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile$PlayerLifeData { <fields>; }
-keep,allowobfuscation class com.openkhub.sensefield.GameProfile$RelationData { <fields>; }

# Background-image accounting is checked across the release-test APK boundary.
# Keep only the entry points/fields that test uses; recorder helpers still shrink.
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.CaptureService {
    static boolean isRunning();
}
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.Match3LiveService {
    static boolean isRunning();
}
# Fault-photo checks cross the separate Release test APK boundary. Retain only
# these typed observation/drawing contracts; the rest of Match3 still shrinks.
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Sampler {
    <init>(android.content.Context, com.openkhub.sensefield.BoardGeometry);
    static com.openkhub.sensefield.BoardGeometry autoDetectGeometry(android.graphics.Bitmap);
    static boolean isMovable(char);
    com.openkhub.sensefield.Match3Position samplePosition(android.graphics.Bitmap);
    public void close();
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.BoardGeometry {
    <init>(int,int,int,int,int,int,int,int);
    int frameWidth; int frameHeight; int left; int top; int right; int bottom; int rows; int cols;
    boolean sameGrid(com.openkhub.sensefield.BoardGeometry);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Position {
    com.openkhub.sensefield.Match3Position$Cell cell(int,int);
    char[][] matrix();
    boolean sameCells(com.openkhub.sensefield.Match3Position);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Position$Cell {
    com.openkhub.sensefield.Match3Position$Kind kind;
    char color; boolean swappable;
    char code();
}
-keep enum com.openkhub.sensefield.Match3Position$Kind { *; }
-keep enum com.openkhub.sensefield.Match3Goals$Kind { *; }
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Goals {
    boolean hudVerified; int steps; java.util.List targets;
    int remaining(com.openkhub.sensefield.Match3Goals$Kind);
    boolean fullyKnown();
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Goals$Target {
    com.openkhub.sensefield.Match3Goals$Kind kind; int remaining;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3HudReader {
    <init>(android.content.Context);
    com.openkhub.sensefield.Match3Goals read(android.graphics.Bitmap,com.openkhub.sensefield.BoardGeometry,long);
    java.lang.String status();
    public void close();
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Hint {
    <init>(java.lang.String,long,long,com.openkhub.sensefield.BoardGeometry,com.openkhub.sensefield.Match3Board$Swap);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Board$Swap {
    <init>(int,int,int,int,int);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3HintOverlay$HintView {
    <init>(android.content.Context);
    com.openkhub.sensefield.Match3Hint hint;
    void drawHint(android.graphics.Canvas);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3OverlayCaptureFilter {
    <init>(android.content.Context);
    boolean clean(android.graphics.Bitmap,com.openkhub.sensefield.Match3Hint,float);
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticRecorder {
    static com.openkhub.sensefield.DiagnosticRecorder current;
    static java.util.concurrent.ExecutorService IO;
    java.io.File directory;
    java.lang.String failure;
    static *** start(android.content.Context, java.lang.String, long);
    static *** start(android.content.Context, java.lang.String, long, boolean);
    static java.io.File root(android.content.Context);
    static org.json.JSONObject object(java.lang.Object[]);
    void frame(com.openkhub.sensefield.NativeFrameResult, com.openkhub.sensefield.DiagnosticSnapshot, java.nio.ByteBuffer, int, int, int, long, long, long);
    long[] imageWorkStats();
    void audit(java.lang.String);
    void finish(java.lang.String);
}
# Mixed-game archive tests cross the separate test-APK boundary. Preserve these
# small contracts, including the enum's identity (R8 enum unboxing is not an ABI).
-keep enum com.openkhub.sensefield.DiagnosticGame { *; }
-keepclassmembers,allowobfuscation class com.openkhub.sensefield.DiagnosticsActivity {
    static android.content.Intent intent(android.content.Context, com.openkhub.sensefield.DiagnosticGame);
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticArchive {
    static java.io.File[] sessions(java.io.File);
    static void writeJson(java.io.File, java.lang.String, java.lang.String);
    static void delete(java.io.File);
}
-keep,allowobfuscation class com.openkhub.sensefield.NativeFrameResult {
    static *** empty();
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticSnapshot {
    static *** parse(long[]);
}
# Activities/services and FileProvider entry points are retained by the manifest/default rules.
# No whole-application keep rule: behavior tests must also exercise the minified artifact.
