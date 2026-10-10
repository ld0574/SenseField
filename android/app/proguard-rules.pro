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
    com.openkhub.sensefield.Match3Position samplePosition(android.graphics.Bitmap,long);
    static char[][] sample(android.graphics.Bitmap,com.openkhub.sensefield.BoardGeometry,java.util.List);
    int[] elementPatch(int,int);
    int[] elementEnvelope(int,int);
    int directAnimals; int inferredAnimals; int uncertainAnimals;
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
    com.openkhub.sensefield.Match3Position$SwapPermission swapPermission;
    char code();
}
-keep enum com.openkhub.sensefield.Match3Position$Kind { *; }
-keep enum com.openkhub.sensefield.Match3Position$SwapPermission { *; }
-keep enum com.openkhub.sensefield.Match3Goals$Kind { *; }
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3Goals {
    boolean hudVerified; int steps; java.util.List targets;
    int remaining(com.openkhub.sensefield.Match3Goals$Kind);
    boolean fullyKnown();
    static com.openkhub.sensefield.Match3Goals unknown(long);
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
# Element evidence and mathematical comparisons used by the separate minified
# test APK. Preserve only these contracts, including the copied test sprites.
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance$Body {
    <init>(int[]);
    int[] copyPatch();
    float difference(com.openkhub.sensefield.Match3AnimalAppearance$Body);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance$Face {
    <init>(char,int[]);
    boolean detailed;
    float difference(com.openkhub.sensefield.Match3AnimalAppearance$Face);
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3AnimalAppearance {
    static char recognize(com.openkhub.sensefield.Match3AnimalAppearance$Face,java.util.List,int);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog {
    java.util.List animals; java.util.List animalFaces; java.util.List animalBodies; java.util.List bodyColors;
    static com.openkhub.sensefield.Match3VisualCatalog get(android.content.Context);
    com.openkhub.sensefield.Match3Position$Cell animal(com.openkhub.sensefield.Match3VisualCatalog$CellCache);
    com.openkhub.sensefield.Match3AnimalAppearance$Reference trustedReference(com.openkhub.sensefield.Match3VisualCatalog$CellCache);
    static java.lang.String recognize(java.util.List,int[],float,float);
    static int[] patch(int[],int,int,int,int,int);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog$CellCache {
    <init>(); int[] patch; int[] envelope;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3VisualCatalog$Pattern {
    java.lang.String kind; int[] pixels;
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3CellConfirmation {
    <init>();
    com.openkhub.sensefield.Match3CellConfirmation$Snapshot accept(com.openkhub.sensefield.Match3Position,long);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3CellConfirmation$Snapshot {
    com.openkhub.sensefield.Match3Position position;
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3MoveRanker {
    static java.util.List rankedMoves(com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3Goals);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3MoveValue {
    com.openkhub.sensefield.Match3Board$Swap swap;
    int directUnits; int relevantHits; java.lang.String reason;
    int collected(com.openkhub.sensefield.Match3Goals$Kind);
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3HintValidity {
    static boolean valid(com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3Position,com.openkhub.sensefield.Match3MoveValue,java.util.List);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3UnknownElements {
    <init>();
    java.util.List observe(com.openkhub.sensefield.Match3UnknownElements$Observation[][],long);
}
-keep,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3UnknownElements$Observation {
    <init>(com.openkhub.sensefield.Match3Position$Cell,int[]);
}
-keepclassmembers,allowoptimization,allowobfuscation class com.openkhub.sensefield.Match3UnknownElements$Sample {
    java.lang.String filename();
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticRecorder {
    static com.openkhub.sensefield.DiagnosticRecorder current;
    static java.util.concurrent.ExecutorService IO;
    java.io.File directory;
    java.lang.String failure;
    static *** start(android.content.Context, java.lang.String, long);
    static *** start(android.content.Context, java.lang.String, long, boolean);
    static *** start(android.content.Context, java.lang.String, long, com.openkhub.sensefield.DiagnosticGame);
    void setImagesEnabled(boolean);
    void elementSample(android.graphics.Bitmap,com.openkhub.sensefield.BoardGeometry,com.openkhub.sensefield.Match3UnknownElements$Sample);
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
    static void export(java.io.File,java.io.File,java.lang.String);
}
-keep,allowobfuscation class com.openkhub.sensefield.NativeFrameResult {
    static *** empty();
}
-keep,allowobfuscation class com.openkhub.sensefield.DiagnosticSnapshot {
    static *** parse(long[]);
}
# Activities/services and FileProvider entry points are retained by the manifest/default rules.
# No whole-application keep rule: behavior tests must also exercise the minified artifact.
